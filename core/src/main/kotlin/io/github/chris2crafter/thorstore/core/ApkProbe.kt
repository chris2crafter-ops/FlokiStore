package io.github.chris2crafter.thorstore.core

import io.github.chris2crafter.thorstore.core.Bytes.i32
import io.github.chris2crafter.thorstore.core.Bytes.u16
import io.github.chris2crafter.thorstore.core.Bytes.u8

/** String pool chunk shared by binary XML and resources.arsc. */
internal class StringPool(private val b: ByteArray, private val p: Int) {
    private val count = i32(b, p + 8)
    private val utf8 = i32(b, p + 16) and 0x100 != 0
    private val stringsStart = i32(b, p + 20)
    private val headerSize = u16(b, p + 2)
    private val cache = HashMap<Int, String>()

    operator fun get(i: Int): String? {
        if (i < 0 || i >= count) return null
        return cache.getOrPut(i) {
            val off = p + stringsStart + i32(b, p + headerSize + i * 4)
            if (utf8) {
                var o = off
                o += if (u8(b, o) and 0x80 != 0) 2 else 1 // length in chars, unused
                val len: Int
                if (u8(b, o) and 0x80 != 0) { len = ((u8(b, o) and 0x7f) shl 8) or u8(b, o + 1); o += 2 } else { len = u8(b, o); o += 1 }
                String(b, o, len, Charsets.UTF_8)
            } else {
                var len = u16(b, off)
                var o = off + 2
                if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or u16(b, off + 2); o = off + 4 }
                String(b, o, len * 2, Charsets.UTF_16LE)
            }
        }
    }
}

/** Android binary XML (AndroidManifest.xml, compiled res/ xml files). */
class Axml(b: ByteArray) {
    class Attr(val name: String, val resId: Int, val raw: String?, val type: Int, val data: Int)

    class Element(val name: String, val depth: Int, val attrs: List<Attr>) {
        fun attr(resId: Int, name: String): Attr? =
            attrs.firstOrNull { it.resId == resId } ?: attrs.firstOrNull { it.name == name }
    }

    val elements = ArrayList<Element>()

    init {
        var strings: StringPool? = null
        var resIds = IntArray(0)
        var depth = 0
        var p = u16(b, 2)
        while (p + 8 <= b.size) {
            val type = u16(b, p)
            val hs = u16(b, p + 2)
            val size = i32(b, p + 4)
            if (size <= 0) break
            when (type) {
                0x0001 -> strings = StringPool(b, p)
                0x0180 -> resIds = IntArray((size - hs) / 4) { i32(b, p + hs + it * 4) }
                0x0102 -> {
                    val ext = p + 16
                    val nameIdx = i32(b, ext + 4)
                    val attrStart = u16(b, ext + 8)
                    val attrSize = u16(b, ext + 10)
                    val count = u16(b, ext + 12)
                    val attrs = (0 until count).map { i ->
                        val a = ext + attrStart + i * attrSize
                        val n = i32(b, a + 4)
                        val raw = i32(b, a + 8)
                        Attr(
                            name = strings?.get(n) ?: "",
                            resId = if (n in resIds.indices) resIds[n] else 0,
                            raw = if (raw >= 0) strings?.get(raw) else null,
                            type = u8(b, a + 15),
                            data = i32(b, a + 16),
                        )
                    }
                    elements.add(Element(strings?.get(nameIdx) ?: "", depth, attrs))
                    depth++
                }
                0x0103 -> depth--
            }
            p += size
        }
    }
}

/** Just enough of resources.arsc to resolve a resource id to its per-density values. */
class Arsc(private val b: ByteArray) {
    class Value(val type: Int, val data: Int, val str: String?, val density: Int, val lang: String = "")

    private var global: StringPool? = null
    private val packages = HashMap<Int, Int>()

    init {
        var p = u16(b, 2)
        while (p + 8 <= b.size) {
            val type = u16(b, p)
            val size = i32(b, p + 4)
            if (size <= 0) break
            when (type) {
                0x0001 -> global = StringPool(b, p)
                0x0200 -> packages[i32(b, p + 8)] = p
            }
            p += size
        }
    }

    fun resolve(resId: Int): List<Value> {
        val pp = packages[resId ushr 24] ?: packages.values.firstOrNull() ?: return emptyList()
        val typeId = (resId shr 16) and 0xff
        val entry = resId and 0xffff
        val end = pp + i32(b, pp + 4)
        var p = pp + u16(b, pp + 2)
        val out = ArrayList<Value>()
        while (p + 8 <= end) {
            val t = u16(b, p)
            val hs = u16(b, p + 2)
            val size = i32(b, p + 4)
            if (size <= 0) break
            if (t == 0x0201 && u8(b, p + 8) == typeId) {
                val flags = u8(b, p + 9)
                val count = i32(b, p + 12)
                val entriesStart = i32(b, p + 16)
                val density = u16(b, p + 20 + 14)
                // two ASCII letters, zero for the default configuration
                val lang = if (u8(b, p + 20 + 8) in 0x61..0x7a) String(b, p + 20 + 8, 2, Charsets.US_ASCII) else ""
                val offsets = p + hs
                val off = when {
                    flags and 1 != 0 -> (0 until count).firstOrNull { u16(b, offsets + it * 4) == entry }
                        ?.let { u16(b, offsets + it * 4 + 2) * 4 } ?: -1
                    flags and 2 != 0 -> if (entry < count) u16(b, offsets + entry * 2).let { if (it == 0xffff) -1 else it * 4 } else -1
                    else -> if (entry < count) i32(b, offsets + entry * 4) else -1
                }
                if (off >= 0) {
                    val e = p + entriesStart + off
                    val eflags = u16(b, e + 2)
                    if (eflags and 0x8 != 0) {
                        val dt = eflags ushr 8
                        val data = i32(b, e + 4)
                        out.add(Value(dt, data, if (dt == 3) global?.get(data) else null, density, lang))
                    } else if (eflags and 1 == 0) {
                        val v = e + u16(b, e)
                        val dt = u8(b, v + 3)
                        val data = i32(b, v + 4)
                        out.add(Value(dt, data, if (dt == 3) global?.get(data) else null, density, lang))
                    }
                }
            }
            p += size
        }
        return out
    }
}

data class ApkMeta(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val iconRes: Int,
    /** SHA-256 of the signing certificate, see [RemoteZip.signerCertSha256]. */
    val signer: String? = null,
)

sealed class IconData {
    /** A ready bitmap (png/webp). */
    class Bitmap(val bytes: ByteArray, val ext: String) : IconData()

    /** Adaptive icon layers; the device composes them. [bgColor] is ARGB. */
    class Adaptive(val fg: ByteArray, val fgExt: String, val bgColor: Int?, val bg: ByteArray?, val bgExt: String?) : IconData()
}

/** Reads package name, version and launcher icon straight out of an APK. */
object ApkProbe {
    private const val ATTR_LABEL = 0x01010001
    private const val ATTR_ICON = 0x01010002
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_VERSION_CODE_MAJOR = 0x01010576
    private const val ATTR_DRAWABLE = 0x01010199

    fun meta(zip: RemoteZip): ApkMeta? {
        val manifest = zip.read("AndroidManifest.xml") ?: return null
        val ax = Axml(manifest)
        val man = ax.elements.firstOrNull { it.name == "manifest" } ?: return null
        val pkg = man.attr(0, "package")?.raw ?: return null
        val low = man.attr(ATTR_VERSION_CODE, "versionCode")?.data?.toLong()?.and(0xFFFFFFFFL) ?: 0
        val high = man.attr(ATTR_VERSION_CODE_MAJOR, "versionCodeMajor")?.data?.toLong() ?: 0
        val vn = man.attr(ATTR_VERSION_NAME, "versionName")?.raw
        val app = ax.elements.firstOrNull { it.name == "application" }
        val icon = app?.attr(ATTR_ICON, "icon")?.data ?: 0
        val signer = runCatching { zip.signerCertSha256() }.getOrNull()
        return ApkMeta(pkg, (high shl 32) or low, vn, icon, signer)
    }

    fun arsc(zip: RemoteZip): Arsc? = runCatching { zip.read("resources.arsc", 96L shl 20)?.let(::Arsc) }.getOrNull()

    fun icon(zip: RemoteZip, meta: ApkMeta, arsc: Arsc? = null): IconData? {
        val fromRes = runCatching {
            if (meta.iconRes == 0) return@runCatching null
            fromResource(zip, arsc ?: arsc(zip) ?: return@runCatching null, meta.iconRes)
        }.getOrNull()
        return fromRes ?: heuristic(zip)
    }

    /** The app name shown under the launcher icon, German or default text preferred. */
    fun label(zip: RemoteZip, arsc: Arsc?): String? = runCatching {
        val ax = Axml(zip.read("AndroidManifest.xml") ?: return null)
        val attr = ax.elements.firstOrNull { it.name == "application" }?.attr(ATTR_LABEL, "label") ?: return null
        if (attr.type == 3) return attr.raw
        if (attr.type != 1 || arsc == null) return null
        val vals = values(arsc, attr.data).filter { !it.str.isNullOrBlank() }
        (vals.firstOrNull { it.lang == "de" } ?: vals.firstOrNull { it.lang == "" } ?: vals.firstOrNull { it.lang == "en" } ?: vals.firstOrNull())?.str
    }.getOrNull()

    private fun isBitmap(path: String?) = path != null && (path.endsWith(".png") || path.endsWith(".webp")) && !path.endsWith(".9.png")

    private fun rank(density: Int) = when (density) {
        0 -> 160
        0xFFFE, 0xFFFF -> 1
        else -> density
    }

    /** Follows references and returns all values of the final resource. */
    private fun values(arsc: Arsc, resId: Int, depth: Int = 0): List<Arsc.Value> {
        val vals = arsc.resolve(resId)
        if (depth < 4 && vals.isNotEmpty() && vals.all { it.type == 1 || it.type == 7 }) {
            return vals.flatMap { values(arsc, it.data, depth + 1) }
        }
        return vals
    }

    private fun bestBitmap(vals: List<Arsc.Value>) = vals.filter { isBitmap(it.str) }.maxByOrNull { rank(it.density) }?.str

    private fun fromResource(zip: RemoteZip, arsc: Arsc, resId: Int): IconData? {
        val vals = values(arsc, resId)
        bestBitmap(vals)?.let { path -> return zip.read(path)?.let { IconData.Bitmap(it, path.substringAfterLast('.')) } }
        val xmlPath = vals.firstOrNull { it.str?.endsWith(".xml") == true }?.str ?: return null
        val ax = Axml(zip.read(xmlPath) ?: return null)
        if (ax.elements.firstOrNull()?.name != "adaptive-icon") return null

        fun layer(name: String): Arsc.Value? {
            val i = ax.elements.indexOfFirst { it.name == name }
            if (i < 0) return null
            val el = ax.elements[i]
            // the drawable sits on the layer itself or on a nested <inset>
            val attr = el.attr(ATTR_DRAWABLE, "drawable")
                ?: ax.elements.drop(i + 1).takeWhile { it.depth > el.depth }.firstNotNullOfOrNull { it.attr(ATTR_DRAWABLE, "drawable") }
                ?: return null
            return when (attr.type) {
                1, 7 -> values(arsc, attr.data).let { v -> v.filter { isBitmap(it.str) }.maxByOrNull { rank(it.density) } ?: v.firstOrNull() }
                else -> Arsc.Value(attr.type, attr.data, null, 0)
            }
        }

        val fg = layer("foreground")?.takeIf { isBitmap(it.str) } ?: return null
        val fgBytes = zip.read(fg.str!!) ?: return null
        val bg = layer("background")
        val bgColor = bg?.takeIf { it.type in 0x1c..0x1f }?.data
        val bgBytes = bg?.str?.takeIf { isBitmap(it) }?.let { zip.read(it) }
        return IconData.Adaptive(
            fgBytes, fg.str.substringAfterLast('.'),
            bgColor ?: if (bgBytes == null) 0xFFFFFFFF.toInt() else null,
            bgBytes, bg?.str?.substringAfterLast('.'),
        )
    }

    /** Last resort: the biggest launcher-looking bitmap anywhere under res/. */
    private fun heuristic(zip: RemoteZip): IconData? {
        val pattern = Regex("^res/[^/]+/[^/]*(ic_launcher|app_icon|appicon|icon|logo)[^/]*\\.(png|webp)$", RegexOption.IGNORE_CASE)
        val candidates = zip.entries.values.filter { pattern.matches(it.name) && !it.name.endsWith(".9.png") }
        val best = candidates.filter { !Regex("foreground|background|monochrome", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            .maxByOrNull { it.size } ?: return null
        return zip.read(best.name)?.let { IconData.Bitmap(it, best.name.substringAfterLast('.')) }
    }
}
