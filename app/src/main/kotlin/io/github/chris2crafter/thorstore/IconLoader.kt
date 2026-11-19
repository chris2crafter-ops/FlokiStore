package io.github.chris2crafter.thorstore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.IconData
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Icon sources, best first: an icon the user picked, the installed app, the icon taken from
 * the release APK, the icon bundled with the catalog (plain or adaptive layers), a letter tile.
 */
object IconLoader {
    private val cache = LruCache<String, Bitmap>(160)
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, app: CatalogApp, size: Int) {
        val key = key(app)
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageBitmap(letterTile(app, size))
        val ctx = view.context.applicationContext
        exec.execute {
            val bmp = runCatching { produce(ctx, app, size) }.getOrNull() ?: return@execute
            cache.put(key, bmp)
            main.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    /** Blocking variant for notifications. */
    fun bitmap(ctx: Context, app: CatalogApp, size: Int): Bitmap =
        cache.get(key(app)) ?: runCatching { produce(ctx, app, size) }.getOrNull() ?: letterTile(app, size)

    private val versions = ConcurrentHashMap<String, Int>()

    fun invalidate(app: CatalogApp) {
        versions.merge(app.id, 1, Int::plus)
    }

    private fun key(app: CatalogApp) = app.id + ":" + (if (Store.installed(app) != null) 1 else 0) + ":" + (versions[app.id] ?: 0)

    fun savedIconFile(ctx: Context, app: CatalogApp) = File(ctx.filesDir, "icons/${app.id}.png")

    fun customIconFile(ctx: Context, app: CatalogApp) = File(ctx.filesDir, "custom_icons/${app.id}.png")

    fun hasCustomIcon(ctx: Context, app: CatalogApp) = customIconFile(ctx, app).exists()

    /** Saves [bmp] as the icon the user picked, cropped to a square of 256 px. */
    fun saveCustom(ctx: Context, app: CatalogApp, bmp: Bitmap) {
        writePng(customIconFile(ctx, app), square(bmp, 256))
        invalidate(app)
    }

    /** Saves an icon read out of a release APK (for apps the user added). */
    fun saveFromApk(ctx: Context, app: CatalogApp, data: IconData) {
        fromIconData(data, 192)?.let { writePng(savedIconFile(ctx, app), it) }
        invalidate(app)
    }

    fun fromIconData(data: IconData, size: Int): Bitmap? = when (data) {
        is IconData.Bitmap -> BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size)?.let { scale(it, size) }
        is IconData.Adaptive -> BitmapFactory.decodeByteArray(data.fg, 0, data.fg.size)?.let { fg ->
            adaptive(fg, data.bg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }, data.bgColor, size)
        }
    }

    private fun writePng(f: File, bmp: Bitmap) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(f)
    }

    /** Center crop to a square, then scale. */
    fun square(b: Bitmap, size: Int): Bitmap {
        val side = minOf(b.width, b.height)
        val crop = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return if (side == size) crop else Bitmap.createScaledBitmap(crop, size, size, true)
    }

    /** Blocking: the icon the app would have without the user's own picture. */
    fun withoutCustom(ctx: Context, app: CatalogApp, size: Int): Bitmap =
        runCatching { produce(ctx, app, size, custom = false) }.getOrNull() ?: letterTile(app, size)

    private fun produce(ctx: Context, app: CatalogApp, size: Int, custom: Boolean = true): Bitmap? {
        if (custom) customIconFile(ctx, app).takeIf { it.exists() }?.let { f ->
            BitmapFactory.decodeFile(f.path)?.let { return scale(it, size) }
        }
        app.packageName?.let { pkg ->
            runCatching { return render(ctx.packageManager.getApplicationIcon(pkg), size) }
        }
        savedIconFile(ctx, app).takeIf { it.exists() }?.let { f ->
            BitmapFactory.decodeFile(f.path)?.let { return scale(it, size) }
        }
        app.icon?.let { path -> asset(ctx, path)?.let { return scale(it, size) } }
        if (app.iconFg != null) {
            val fg = asset(ctx, app.iconFg!!) ?: return null
            val bg = app.iconBg
            return adaptive(fg, bg?.takeIf { it.startsWith("icons/") }?.let { asset(ctx, it) }, bg?.takeIf { it.startsWith("#") }?.let { parseColor(it) }, size)
        }
        return null
    }

    private fun asset(ctx: Context, path: String): Bitmap? =
        runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()

    private fun parseColor(s: String) = runCatching { java.lang.Long.parseLong(s.removePrefix("#"), 16).toInt() }.getOrDefault(Color.WHITE)

    fun render(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    private fun scale(b: Bitmap, size: Int): Bitmap =
        if (b.width == size && b.height == size) b else Bitmap.createScaledBitmap(b, size, size, true)

    /** Adaptive layers are 108 units with the visible 72 in the middle, masked to a rounded square. */
    private fun adaptive(fg: Bitmap, bg: Bitmap?, bgColor: Int?, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = size * 0.22f
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, Path.Direction.CW) })
        val ext = size / 4
        val dst = Rect(-ext, -ext, size + ext, size + ext)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (bg != null) c.drawBitmap(bg, null, dst, p) else c.drawColor(bgColor ?: Color.WHITE)
        c.drawBitmap(fg, null, dst, p)
        return out
    }

    private val tileColors = intArrayOf(0xFF1F6FEB.toInt(), 0xFF8957E5.toInt(), 0xFFDB6D28.toInt(), 0xFF238636.toInt(), 0xFFBF4B8A.toInt(), 0xFF1B7C83.toInt())

    fun letterTile(app: CatalogApp, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (app.isDriver) 0xFF2D333B.toInt() else tileColors[Math.floorMod(app.id.hashCode(), tileColors.size)]
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, p)
        val label = if (app.isDriver) "GPU" else app.name.first().uppercase()
        p.color = if (app.isDriver) C.ACCENT else Color.WHITE
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = size * (if (app.isDriver) 0.3f else 0.5f)
        p.textAlign = Paint.Align.CENTER
        c.drawText(label, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        return out
    }
}
