package io.github.chris2crafter.thorstore

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.chris2crafter.thorstore.core.CatalogApp
import io.github.chris2crafter.thorstore.core.t

object Notifications {
    const val CH_UPDATES = "updates"
    const val CH_PROGRESS = "progress"
    const val CH_INSTALL = "install"
    const val ID_PROGRESS = 1
    private const val ID_SUMMARY = 2
    private const val GROUP = "thorstore.updates"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun createChannels(ctx: Context) {
        nm(ctx).createNotificationChannels(listOf(
            NotificationChannel(CH_UPDATES, t("App-Updates", "App updates"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = t("Neue Versionen installierter Apps", "New versions of installed apps")
            },
            NotificationChannel(CH_PROGRESS, "Downloads", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_INSTALL, "Installation", NotificationManager.IMPORTANCE_HIGH).apply {
                description = t("Bestätigung und Ergebnis von Installationen", "Confirmation and result of installs")
            },
        ))
    }

    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openStore(ctx: Context, tab: String?, req: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun builder(ctx: Context, channel: String) =
        Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_stat).setColor(C.ACCENT)

    fun progress(ctx: Context, title: String, text: String, percent: Int): Notification =
        builder(ctx, CH_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setContentIntent(openStore(ctx, null, 0))
            .build()

    fun showProgress(ctx: Context, title: String, text: String, percent: Int) {
        if (allowed(ctx)) nm(ctx).notify(ID_PROGRESS, progress(ctx, title, text, percent))
    }

    /** One notification per app with an install button, plus a summary. */
    fun updates(ctx: Context, apps: List<CatalogApp>) {
        if (!allowed(ctx) || apps.isEmpty()) return
        val nm = nm(ctx)
        for (app in apps) {
            val install = Notification.Action.Builder(null as android.graphics.drawable.Icon?, t("Installieren", "Install"), DownloadService.pending(ctx, app)).build()
            val n = builder(ctx, CH_UPDATES)
                .setContentTitle(t("Update für ${app.name}", "Update for ${app.name}"))
                .setContentText(Store.release(app)?.tag?.let { t("Neue Version $it", "New version $it") } ?: t("Neue Version verfügbar", "New version available"))
                .setLargeIcon(IconLoader.bitmap(ctx, app, ctx.dp(64)))
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setContentIntent(openStore(ctx, "updates", app.id.hashCode()))
                .addAction(install)
                .build()
            nm.notify(tagFor(app), 0, n)
        }
        val names = apps.joinToString(", ") { it.name }
        nm.notify(ID_SUMMARY, builder(ctx, CH_UPDATES)
            .setContentTitle(if (apps.size == 1) t("1 Update verfügbar", "1 update available") else t("${apps.size} Updates verfügbar", "${apps.size} updates available"))
            .setContentText(names)
            .setStyle(Notification.BigTextStyle().bigText(names))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openStore(ctx, "updates", 1))
            .build())
    }

    fun clearUpdate(ctx: Context, app: CatalogApp) = nm(ctx).cancel(tagFor(app), 0)

    private fun tagFor(app: CatalogApp) = "update:${app.id}"

    fun confirmInstall(ctx: Context, app: CatalogApp?, confirm: Intent) {
        if (!allowed(ctx)) return
        val pi = PendingIntent.getActivity(ctx, (app?.id ?: "x").hashCode(), confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm(ctx).notify("confirm:${app?.id}", 0, builder(ctx, CH_INSTALL)
            .setContentTitle(t("${app?.name ?: "App"} ist bereit", "${app?.name ?: "App"} is ready"))
            .setContentText(t("Tippen, um die Installation zu bestätigen", "Tap to confirm the installation"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .apply { app?.let { setLargeIcon(IconLoader.bitmap(ctx, it, ctx.dp(64))) } }
            .build())
    }

    fun clearConfirm(ctx: Context, app: CatalogApp) = nm(ctx).cancel("confirm:${app.id}", 0)

    fun result(ctx: Context, app: CatalogApp, title: String, text: String?) {
        if (!allowed(ctx)) return
        nm(ctx).notify("result:${app.id}", 0, builder(ctx, CH_INSTALL)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(openStore(ctx, null, 3))
            .build())
    }
}
