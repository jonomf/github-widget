package com.jono.githubwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ImageView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Setup screen, and the "settings" entry in the widget's long-press menu.
 * Sections: GitHub token, options, updates, matched apps, your widgets.
 */
class MainActivity : Activity() {
    private lateinit var tokenStatus: TextView
    private lateinit var tokenInput: EditText
    private lateinit var forget: Button
    private lateinit var updateStatus: TextView
    private lateinit var matched: TextView
    private lateinit var sizes: TextView
    private lateinit var errorBox: LinearLayout
    private var ready = false
    private val redraw: () -> Unit = { runOnUiThread { refreshInfo() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opened from the long-press menu's widget settings: nothing to configure, just say OK.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        }

        val pad = dp(24)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        // Title, with the installed GitHub app's own icon beside it.
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            Apps.githubPkg(this@MainActivity)?.let { pkg ->
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(packageManager.getApplicationIcon(pkg))
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            }
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
            })
        })
        col.addView(TextView(this).apply {
            text = "Your GitHub repos, most recently pushed first, with each one's latest CI run and commit. " +
                "Tap a repo to open it in the GitHub app. If an app built from a repo is installed, its icon " +
                "shows (tap it to open the app), and an Update chip appears when a newer build is on GitHub."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(8))
        })

        // --- Last error, if any: shown first so it's visible even if the rest fails ---
        errorBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(errorBox)
        showLastError()

        try {
            buildSections(col)
        } catch (e: Exception) {
            Crash.record(this, e, fatal = false)
            showLastError()
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun buildSections(col: LinearLayout) {
        // --- Token ---
        col.addView(heading("GitHub token"))
        tokenStatus = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        col.addView(tokenStatus)
        col.addView(note(
            "The widget reads your repos with a read-only fine-grained token. The button below opens " +
                "GitHub with the permissions filled in (Contents: read, Actions: read). There, pick " +
                "Repository access → All repositories so new repos show up too, choose an expiry, " +
                "tap Generate, copy the token and paste it here. It stays on this phone."
        ))
        col.addView(Button(this).apply {
            text = "Create a token on GitHub"
            setOnClickListener { Apps.start(this@MainActivity, Apps.browserIntent(this@MainActivity, TOKEN_URL)) }
        })
        tokenInput = EditText(this).apply {
            hint = "Paste token (github_pat_…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
        }
        col.addView(tokenInput)
        col.addView(Button(this).apply {
            text = "Save token"
            setOnClickListener { saveToken() }
        })
        forget = Button(this).apply {
            text = "Remove token"
            setOnClickListener {
                Store.setToken(this@MainActivity, null, null)
                WidgetRenderer.renderAll(this@MainActivity)
                refreshInfo()
            }
        }
        col.addView(forget)

        // --- Options ---
        col.addView(heading("Options"))
        col.addView(CheckBox(this).apply {
            text = "Hide forks and archived repos"
            isChecked = Store.hideForks(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setHideForks(this@MainActivity, on)
                WidgetRenderer.renderAll(this@MainActivity)
            }
        })
        col.addView(CheckBox(this).apply {
            text = "Include repos from organizations and ones shared with me"
            isChecked = Store.includeOthers(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setIncludeOthers(this@MainActivity, on)
                RefreshJob.now(this@MainActivity)
                WidgetRenderer.renderAll(this@MainActivity)
            }
        })
        col.addView(note(
            "Organization repos only appear if the token was made with that organization as its owner. " +
                "The list refreshes about every 15 minutes, and when you tap ↻ on the widget."
        ))

        // --- Updates ---
        col.addView(heading("Updates"))
        updateStatus = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        col.addView(updateStatus)
        col.addView(Button(this).apply {
            text = "Get the latest build"
            setOnClickListener { Apps.start(this@MainActivity, Apps.browserIntent(this@MainActivity, RELEASES_URL)) }
        })
        col.addView(Button(this).apply {
            text = "Add widget to home screen"
            setOnClickListener { pinWidget() }
        })

        // --- Apps matched to repos ---
        col.addView(heading("Apps from your repos"))
        matched = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        col.addView(matched)

        // --- Your widgets ---
        col.addView(heading("Your widgets"))
        sizes = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        col.addView(sizes)
        ready = true
    }

    /** The saved error, with a copy button so it can be pasted to Claude. */
    private fun showLastError() {
        errorBox.removeAllViews()
        val text = Crash.last(this) ?: return
        errorBox.addView(heading("Last error"))
        errorBox.addView(note("Something went wrong. Copy this and send it to Claude."))
        errorBox.addView(TextView(this).apply {
            this.text = text.lines().take(14).joinToString("\n")
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        errorBox.addView(Button(this).apply {
            this.text = "Copy error"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Repos error", text))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        errorBox.addView(Button(this).apply {
            this.text = "Clear"
            setOnClickListener {
                Crash.clear(this@MainActivity)
                showLastError()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        WidgetRenderer.listeners.add(redraw)
        try {
            RefreshJob.schedule(this)
            RefreshJob.now(this)
        } catch (e: Exception) {
            Crash.record(this, e, fatal = false)
        }
        WidgetRenderer.renderAll(this)
        refreshInfo()
    }

    override fun onPause() {
        WidgetRenderer.listeners.remove(redraw)
        super.onPause()
    }

    private fun refreshInfo() {
        try {
            fillInfo()
        } catch (e: Exception) {
            Crash.record(this, e, fatal = false)
        }
        showLastError()
    }

    private fun fillInfo() {
        if (!ready) return
        val token = Store.token(this)
        val login = Store.login(this)
        val error = Store.error(this)
        val count = Store.visibleRepos(this).size
        tokenStatus.text = when {
            token == null -> "No token yet, so the widget can't list anything."
            error != null -> "⚠ $error"
            login != null -> "✓ Signed in as $login · $count repos" +
                (if (Store.refreshing(this)) " · updating…" else "")
            else -> "Checking the token…"
        }
        forget.visibility = if (token == null) android.view.View.GONE else android.view.View.VISIBLE

        val build = packageManager.getPackageInfo(packageName, 0).longVersionCode
        updateStatus.text = "You're on build $build. This widget's own repo shows an Update chip " +
            "when a newer build is out; download it and open it to install over this version."

        val gh = Apps.githubPkg(this)
        val ghLine = if (gh != null) "✓ GitHub app found ($gh)\n" else "GitHub app not found, so repos open in the browser\n"
        val apps = Store.visibleRepos(this).mapNotNull { r -> Apps.installed(this, r)?.let { r to it } }
        matched.text = ghLine + if (apps.isEmpty()) {
            "None found yet. An app matches a repo when its package is com.jono.<repo name without dashes>."
        } else {
            apps.joinToString("\n") { (r, app) ->
                val latest = r.lastBuild?.let { if (it > app.build) " → build $it on GitHub" else " (latest)" } ?: ""
                "• ${r.name}: build ${app.build} installed$latest"
            }
        }
        sizes.text = WidgetRenderer.describeSizes(this)
    }

    private fun saveToken() {
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) return
        tokenStatus.text = "Checking the token…"
        Thread {
            val result = try {
                Result.success(GitHubApi.login(token))
            } catch (e: Exception) {
                Result.failure<String>(e)
            }
            runOnUiThread {
                val login = result.getOrNull()
                if (login != null) {
                    Store.setToken(this, token, login)
                    tokenInput.setText("")
                    RefreshJob.now(this)
                    WidgetRenderer.renderAll(this)
                    Toast.makeText(this, "Signed in as $login", Toast.LENGTH_SHORT).show()
                    refreshInfo()
                } else {
                    val e = result.exceptionOrNull()
                    tokenStatus.text = "⚠ That token didn't work: " +
                        (if (e is GitHubApi.HttpError) e.message else "couldn't reach GitHub")
                }
            }
        }.start()
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.7f
        setPadding(0, 0, 0, dp(8))
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, ReposWidget::class.java), null, null)
        } else {
            Toast.makeText(this, "Long-press your home screen → Widgets → Repos", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val RELEASES_URL = "https://github.com/jonomf/github-widget/releases/latest"
        const val TOKEN_URL = "https://github.com/settings/personal-access-tokens/new" +
            "?name=Repos+widget&description=Read-only+repo+list+for+my+Android+widget" +
            "&contents=read&actions=read"
    }
}
