package com.jono.githubwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Settings and the last fetched repo list, kept in the app's private storage. */
object Store {

    /** Latest GitHub Actions run of a repo (any workflow). */
    class Run(
        val status: String, // queued, in_progress, completed…
        val conclusion: String?, // success, failure, cancelled…
        val number: Int,
        val message: String?,
    )

    class Repo(
        val fullName: String,
        val owner: String,
        val name: String,
        val htmlUrl: String,
        val description: String?,
        val language: String?,
        val pushedAt: Long,
        val isPrivate: Boolean,
        val fork: Boolean,
        val archived: Boolean,
        /** Whether runs/commit were looked up (only the most recent repos get that). */
        val checked: Boolean = false,
        val fetchedAt: Long = 0,
        val run: Run? = null,
        /** Run number of the latest successful build.yml run = the newest APK's versionCode. */
        val lastBuild: Int? = null,
        /** Latest commit message, when the repo has no Actions runs. */
        val lastCommit: String? = null,
        /** The Claude Code session that last pushed here (a commit's "Claude-Session:" line). */
        val session: String? = null,
        /** Which version of the details lookup filled this in; older ones get looked up again. */
        val ver: Int = 0,
    ) {
        val releasesUrl get() = "$htmlUrl/releases/latest"

        fun withDetails(
            fetchedAt: Long, run: Run?, lastBuild: Int?, lastCommit: String?, session: String?, ver: Int = DETAILS_VER,
        ) = Repo(
            fullName, owner, name, htmlUrl, description, language, pushedAt, isPrivate, fork, archived,
            true, fetchedAt, run, lastBuild, lastCommit, session, ver,
        )

        fun withDetailsOf(prev: Repo) =
            withDetails(prev.fetchedAt, prev.run, prev.lastBuild, prev.lastCommit, prev.session, prev.ver)

        fun toJson(): JSONObject = JSONObject().apply {
            put("full", fullName); put("owner", owner); put("name", name); put("url", htmlUrl)
            put("desc", description ?: JSONObject.NULL); put("lang", language ?: JSONObject.NULL)
            put("pushed", pushedAt); put("private", isPrivate); put("fork", fork); put("archived", archived)
            put("checked", checked); put("fetched", fetchedAt)
            put("lastBuild", lastBuild ?: JSONObject.NULL); put("lastCommit", lastCommit ?: JSONObject.NULL)
            put("session", session ?: JSONObject.NULL); put("ver", ver)
            run?.let {
                put("run", JSONObject().apply {
                    put("status", it.status); put("conclusion", it.conclusion ?: JSONObject.NULL)
                    put("number", it.number); put("message", it.message ?: JSONObject.NULL)
                })
            }
        }

        companion object {
            fun fromJson(j: JSONObject) = Repo(
                fullName = j.getString("full"),
                owner = j.getString("owner"),
                name = j.getString("name"),
                htmlUrl = j.getString("url"),
                description = j.str("desc"),
                language = j.str("lang"),
                pushedAt = j.optLong("pushed"),
                isPrivate = j.optBoolean("private"),
                fork = j.optBoolean("fork"),
                archived = j.optBoolean("archived"),
                checked = j.optBoolean("checked"),
                fetchedAt = j.optLong("fetched"),
                run = j.optJSONObject("run")?.let {
                    Run(it.getString("status"), it.str("conclusion"), it.optInt("number"), it.str("message"))
                },
                lastBuild = if (j.isNull("lastBuild") || !j.has("lastBuild")) null else j.getInt("lastBuild"),
                lastCommit = j.str("lastCommit"),
                session = j.str("session"),
                ver = j.optInt("ver"),
            )
        }
    }

    /** Bumped when the details lookup learns something new (2: Claude session links). */
    const val DETAILS_VER = 2

    /** A string field, or null when missing, JSON null or blank. */
    fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key).takeIf { it.isNotBlank() }

    private fun p(ctx: Context) = ctx.getSharedPreferences("repos", Context.MODE_PRIVATE)

    fun token(ctx: Context): String? = p(ctx).getString("token", null)?.takeIf { it.isNotBlank() }

    fun setToken(ctx: Context, token: String?, login: String?) {
        val e = p(ctx).edit().putString("token", token).putString("login", login).remove("error")
        if (token == null) e.remove("repos").remove("updated")
        e.apply()
    }

    fun login(ctx: Context): String? = p(ctx).getString("login", null)

    fun includeOthers(ctx: Context) = p(ctx).getBoolean("others", false)
    fun setIncludeOthers(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("others", on).apply()

    fun hideForks(ctx: Context) = p(ctx).getBoolean("hideForks", true)
    fun setHideForks(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("hideForks", on).apply()

    fun updatedAt(ctx: Context) = p(ctx).getLong("updated", 0)
    fun error(ctx: Context): String? = p(ctx).getString("error", null)
    fun setError(ctx: Context, msg: String?) = p(ctx).edit().putString("error", msg).commit()

    /** A refresh counts as running for 2 minutes at most, so a stuck one doesn't show forever. */
    fun refreshing(ctx: Context) = System.currentTimeMillis() - p(ctx).getLong("refreshing", 0) < 2 * 60_000
    fun markRefreshing(ctx: Context, on: Boolean) =
        p(ctx).edit().putLong("refreshing", if (on) System.currentTimeMillis() else 0).commit()

    fun saveRepos(ctx: Context, repos: List<Repo>, login: String) {
        val arr = JSONArray()
        repos.forEach { arr.put(it.toJson()) }
        p(ctx).edit()
            .putString("repos", arr.toString())
            .putLong("updated", System.currentTimeMillis())
            .putString("login", login)
            .remove("error")
            .commit()
    }

    fun repos(ctx: Context): List<Repo> = try {
        val arr = JSONArray(p(ctx).getString("repos", null) ?: "[]")
        (0 until arr.length()).map { Repo.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    /** What the widget lists: newest push first, optionally without forks and archived repos. */
    fun visibleRepos(ctx: Context): List<Repo> {
        val hide = hideForks(ctx)
        return repos(ctx).filter { !hide || (!it.fork && !it.archived) }.sortedByDescending { it.pushedAt }
    }
}
