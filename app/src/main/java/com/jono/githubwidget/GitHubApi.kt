package com.jono.githubwidget

import android.content.Context
import com.jono.githubwidget.Store.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** The few GitHub REST calls the widget needs, with your token. Read-only. */
object GitHubApi {
    class HttpError(val code: Int, message: String) : IOException(message)

    private const val BASE = "https://api.github.com"

    fun get(token: String, path: String): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "repos-android-widget")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code, describe(code))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun describe(code: Int) = when (code) {
        401 -> "GitHub didn't accept the token (expired or revoked?)"
        403 -> "GitHub refused (missing permission or rate limit)"
        404 -> "Not found, or the token can't see it"
        else -> "GitHub error $code"
    }

    fun login(token: String): String = JSONObject(get(token, "/user")).getString("login")
}

/** Fetches the repo list and, for the most recent ones, their latest CI run and commit. */
object Refresher {
    /** Only the newest repos get the extra per-repo calls; older ones keep what they had. */
    private const val DETAILED = 20

    fun refresh(ctx: Context) {
        val token = Store.token(ctx) ?: run { Store.markRefreshing(ctx, false); return }
        try {
            val login = GitHubApi.login(token)
            val affiliation = if (Store.includeOthers(ctx)) "owner,collaborator,organization_member" else "owner"
            val arr = JSONArray(
                GitHubApi.get(token, "/user/repos?sort=pushed&direction=desc&per_page=60&affiliation=$affiliation")
            )
            val base = (0 until arr.length()).map { parse(arr.getJSONObject(it)) }.sortedByDescending { it.pushedAt }
            val previous = Store.repos(ctx).associateBy { it.fullName }

            val pool = Executors.newFixedThreadPool(6)
            try {
                val jobs = base.mapIndexed { i, repo ->
                    pool.submit(Callable { details(token, repo, previous[repo.fullName], i < DETAILED) })
                }
                Store.saveRepos(ctx, jobs.map { it.get() }, login)
            } finally {
                pool.shutdown()
            }
        } catch (e: GitHubApi.HttpError) {
            Store.setError(ctx, e.message)
        } catch (e: Exception) {
            Store.setError(ctx, "Couldn't reach GitHub")
        } finally {
            Store.markRefreshing(ctx, false)
        }
    }

    private fun parse(j: JSONObject) = Store.Repo(
        fullName = j.getString("full_name"),
        owner = j.getJSONObject("owner").getString("login"),
        name = j.getString("name"),
        htmlUrl = j.getString("html_url"),
        description = j.str("description"),
        language = j.str("language"),
        pushedAt = j.str("pushed_at")?.let { Instant.parse(it).toEpochMilli() } ?: 0L,
        isPrivate = j.optBoolean("private"),
        fork = j.optBoolean("fork"),
        archived = j.optBoolean("archived"),
    )

    private fun details(token: String, repo: Store.Repo, prev: Store.Repo?, detailed: Boolean): Store.Repo {
        // Nothing new since a settled look (finished CI, checked well after the push): reuse it.
        val settled = prev != null && prev.checked && prev.pushedAt == repo.pushedAt &&
            prev.fetchedAt - prev.pushedAt > 10 * 60_000 &&
            (prev.run == null || prev.run.status == "completed")
        if (settled) return repo.withDetailsOf(prev!!)
        if (!detailed) return if (prev != null && prev.checked) repo.withDetailsOf(prev) else repo

        var run: Store.Run? = null
        var lastBuild: Int? = null
        try {
            val runs = JSONObject(GitHubApi.get(token, "/repos/${repo.fullName}/actions/runs?per_page=10"))
                .getJSONArray("workflow_runs")
            val list = (0 until runs.length()).map { runs.getJSONObject(it) }
            list.firstOrNull()?.let { r ->
                val message = r.optJSONObject("head_commit")?.str("message") ?: r.str("display_title")
                run = Store.Run(r.getString("status"), r.str("conclusion"), r.optInt("run_number"), message)
            }
            // The APK workflow is build.yml; its run number is the build's versionCode.
            lastBuild = list.firstOrNull {
                (it.str("path") ?: "").endsWith("build.yml") && it.str("conclusion") == "success"
            }?.optInt("run_number")
        } catch (e: GitHubApi.HttpError) {
            // No Actions access or no Actions: fall through to the commit.
        }

        var lastCommit: String? = null
        if (run == null) {
            try {
                val commits = JSONArray(GitHubApi.get(token, "/repos/${repo.fullName}/commits?per_page=1"))
                lastCommit = commits.optJSONObject(0)?.optJSONObject("commit")?.str("message")
            } catch (e: GitHubApi.HttpError) {
                // Empty repo (409) or no access.
            }
        }
        return repo.withDetails(System.currentTimeMillis(), run, lastBuild, lastCommit)
    }
}
