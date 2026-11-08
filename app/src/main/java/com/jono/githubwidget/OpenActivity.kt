package com.jono.githubwidget

import android.app.Activity
import android.os.Bundle

/**
 * Invisible step between a tap in the widget and what it opens. List rows can only share one
 * tap target, so each row says what it wants in the intent's action:
 *  - REPO: the repo in the GitHub app (browser if it isn't installed)
 *  - APP: the app built from that repo
 *  - UPDATE: the repo's latest release in the browser, to download the APK
 *  - GITHUB: the GitHub app itself
 */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.data?.toString()
        val target = when (intent.action) {
            APP -> intent.getStringExtra(EXTRA_PKG)?.let { packageManager.getLaunchIntentForPackage(it) }
                ?: url?.let { Apps.repoIntent(this, it) }
            UPDATE -> url?.let { Apps.browserIntent(this, it) }
            GITHUB -> Apps.githubIntent(this)
            else -> url?.let { Apps.repoIntent(this, it) }
        }
        target?.let { Apps.start(this, it) }
        finish()
    }

    companion object {
        const val REPO = "com.jono.githubwidget.open.REPO"
        const val APP = "com.jono.githubwidget.open.APP"
        const val UPDATE = "com.jono.githubwidget.open.UPDATE"
        const val GITHUB = "com.jono.githubwidget.open.GITHUB"
        const val EXTRA_PKG = "pkg"
    }
}
