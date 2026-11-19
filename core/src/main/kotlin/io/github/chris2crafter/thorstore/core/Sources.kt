package io.github.chris2crafter.thorstore.core

import java.io.IOException

/** One entry point for releases, whether a project lives on GitHub or on its own server. */
object Sources {

    fun latest(c: GithubClient, app: CatalogApp): Release = when {
        app.repo != null -> withFile(c, app, c.resolve(app.repo, app.prerelease))
        app.releaseJson != null -> c.jsonRelease(app.releaseJson, app.id) ?: throw IOException(t("Release-Info nicht lesbar", "Release info not readable"))
        else -> throw IOException(t("${app.name} hat keine Release-Quelle", "${app.name} has no release source"))
    }

    /**
     * Some authors publish a release first and upload the files later. If the newest release
     * has nothing usable, take the newest of the next few releases that has.
     */
    private fun withFile(c: GithubClient, app: CatalogApp, newest: Release): Release {
        if (usable(app, newest)) return newest
        val older = runCatching { c.releaseList(app.repo!!, 1) }.getOrDefault(emptyList())
            .map { it.tag }.filter { it != newest.tag }.take(5)
        for (tag in older) {
            val r = runCatching { c.release(app.repo!!, tag) }.getOrNull() ?: continue
            if (usable(app, r)) return r
        }
        return newest
    }

    private fun usable(app: CatalogApp, r: Release) =
        if (app.isDriver) ApkPicker.files(r.assets, app).isNotEmpty() else ApkPicker.best(r.assets, app) != null

    fun release(c: GithubClient, app: CatalogApp, tag: String): Release = when {
        app.repo != null -> c.release(app.repo, tag)
        app.releaseJsonTag != null ->
            c.jsonRelease(app.releaseJsonTag.replace("{tag}", GithubClient.enc(tag)), app.id) ?: Release(app.id, tag, emptyList())
        else -> latest(c, app).takeIf { it.tag == tag } ?: Release(app.id, tag, emptyList())
    }

    /** Release history, newest first; [page] starts at 1. */
    fun list(c: GithubClient, app: CatalogApp, page: Int): List<ReleaseSummary> = when {
        app.repo != null -> c.releaseList(app.repo, page)
        app.forgejo != null -> c.forgejoList(app.forgejo, page)
        page == 1 -> latest(c, app).let { listOf(ReleaseSummary(it.tag, it.tag, it.date, false)) }
        else -> emptyList()
    }

    fun notes(c: GithubClient, app: CatalogApp, tag: String): String? = when {
        app.repo != null -> c.notes(app.repo, tag)
        else -> runCatching { release(c, app, tag).notes }.getOrNull()
    }
}
