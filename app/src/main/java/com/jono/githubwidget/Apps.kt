package com.jono.githubwidget

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import java.io.File
import java.security.MessageDigest

/** Installed apps built from the repos, the icons the widget shows, and opening things. */
object Apps {
    const val GITHUB = "com.github.android"
    private const val SIZE = 96

    class Installed(val pkg: String, val build: Long, val updatedAt: Long)

    /** Jono's apps use package com.jono.<repo name without dashes>, e.g. com.jono.audiobookwidget. */
    fun pkgFor(repo: Store.Repo) = "com.jono." + repo.name.lowercase().filter { it.isLetterOrDigit() }

    fun installed(ctx: Context, pkg: String): Installed? = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        Installed(pkg, pi.longVersionCode, pi.lastUpdateTime)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun installed(ctx: Context, repo: Store.Repo) = installed(ctx, pkgFor(repo))

    // --- Icons, served to the launcher by IconProvider ---

    /** The installed app's own icon, or a lettered tile coloured by the repo's main language. */
    fun repoIcon(ctx: Context, repo: Store.Repo, app: Installed?): Uri =
        if (app != null) {
            saved(ctx, "app:${app.pkg}:${app.updatedAt}") { appBitmap(ctx, app.pkg) }
        } else {
            saved(ctx, "tile:${repo.name}:${repo.language}") { tile(repo.name, repo.language) }
        }

    /**
     * The GitHub app's package: normally com.github.android, else any app whose home-screen
     * name is "GitHub" (so a differently packaged build still gets found).
     */
    fun githubPkg(ctx: Context): String? {
        if (installed(ctx, GITHUB) != null) return GITHUB
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return ctx.packageManager.queryIntentActivities(launcher, 0).firstOrNull {
            it.activityInfo.packageName != ctx.packageName &&
                it.loadLabel(ctx.packageManager).toString().trim().equals("GitHub", ignoreCase = true)
        }?.activityInfo?.packageName
    }

    /** The GitHub app's icon, or null if it isn't installed. */
    fun githubIcon(ctx: Context): Uri? {
        val gh = githubPkg(ctx)?.let { installed(ctx, it) } ?: return null
        return saved(ctx, "app:${gh.pkg}:${gh.updatedAt}") { appBitmap(ctx, gh.pkg) }
    }

    fun iconFile(ctx: Context, id: String) = File(File(ctx.filesDir, "icons").apply { mkdirs() }, "$id.png")

    private fun saved(ctx: Context, key: String, draw: () -> Bitmap): Uri {
        val id = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val file = iconFile(ctx, id)
        if (!file.exists()) {
            try {
                val tmp = File(file.parentFile, "$id.tmp")
                tmp.outputStream().use { draw().compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(file)
            } catch (e: Exception) {
                // Leave it missing; the launcher shows the placeholder.
            }
        }
        return Uri.parse("content://${ctx.packageName}.icons/$id")
    }

    private fun appBitmap(ctx: Context, pkg: String): Bitmap {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, SIZE, SIZE)
        d.draw(Canvas(b))
        return b
    }

    /** GitHub's colours for common languages; anything else gets a stable colour from its name. */
    private val LANG = mapOf(
        "Kotlin" to "#A97BFF", "Java" to "#B07219", "JavaScript" to "#F1E05A", "TypeScript" to "#3178C6",
        "Python" to "#3572A5", "C#" to "#178600", "C++" to "#F34B7D", "C" to "#555555", "HTML" to "#E34C26",
        "CSS" to "#563D7C", "GDScript" to "#355570", "ShaderLab" to "#222C37", "Swift" to "#F05138",
        "Go" to "#00ADD8", "Rust" to "#DEA584", "Shell" to "#89E051", "Dart" to "#00B4AB",
        "Svelte" to "#FF3E00", "Vue" to "#41B883", "Lua" to "#000080", "HLSL" to "#AACE60",
    )

    private fun tile(name: String, lang: String?): Bitmap {
        val bg = LANG[lang]?.let { Color.parseColor(it) }
            ?: Color.HSVToColor(floatArrayOf(((name.hashCode() % 360) + 360) % 360f, 0.45f, 0.62f))
        val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        c.drawRoundRect(RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), SIZE * 0.22f, SIZE * 0.22f, paint)

        val parts = name.split('-', '_', '.', ' ').filter { it.isNotEmpty() }
        val letters = parts.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
        val lum = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000
        paint.color = if (lum > 170) Color.parseColor("#24292F") else Color.WHITE
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = SIZE * (if (letters.length > 1) 0.40f else 0.50f)
        paint.textAlign = Paint.Align.CENTER
        val y = SIZE / 2f - (paint.descent() + paint.ascent()) / 2
        c.drawText(letters, SIZE / 2f, y, paint)
        return b
    }

    // --- Opening things ---

    /** A repo page: in the GitHub app when it's installed and takes the link, else the browser. */
    fun repoIntent(ctx: Context, url: String): Intent {
        val gh = githubPkg(ctx) ?: return browserIntent(ctx, url)
        val inApp = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(gh)
        return if (ctx.packageManager.resolveActivity(inApp, 0) != null) inApp else browserIntent(ctx, url)
    }

    /** Always the browser (APK downloads), even though the GitHub app also claims github.com links. */
    fun browserIntent(ctx: Context, url: String): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val browser = ctx.packageManager
            .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (browser != null && browser != "android" && browser != githubPkg(ctx)) intent.setPackage(browser)
        return intent
    }

    /** The GitHub app's home, or github.com in the browser if it isn't installed. */
    fun githubIntent(ctx: Context): Intent =
        githubPkg(ctx)?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
            ?: browserIntent(ctx, "https://github.com")

    fun start(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Nothing installed can open that", Toast.LENGTH_SHORT).show()
        }
    }
}
