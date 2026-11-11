package com.jono.githubwidget

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.FrameLayout
import kotlin.math.min

/**
 * The expand panel: tap a small widget and this floats over the home screen showing the
 * the full list, so it looks and works exactly like the widget at its biggest.
 * It redraws whenever the widget does. Tap outside to close.
 */
class PanelActivity : Activity() {
    private lateinit var card: FrameLayout
    private val redraw: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        val dp = dm.density
        val width = min(dm.widthPixels - (32 * dp).toInt(), (420 * dp).toInt())
        val height = min(dm.heightPixels - (160 * dp).toInt(), (520 * dp).toInt())

        val root = FrameLayout(this).apply { setOnClickListener { finish() } }
        card = FrameLayout(this).apply { isClickable = true } // taps inside don't close it
        root.addView(card, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        WidgetRenderer.listeners.add(redraw)
        render()
    }

    override fun onPause() {
        WidgetRenderer.listeners.remove(redraw)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        finish() // opening an app from the panel closes it
    }

    private fun render() {
        if (isFinishing) return
        card.removeAllViews()
        val view = WidgetRenderer.buildPanel(this).apply(this, card)
        card.addView(view)

        // The header comes from the widget's own layout; the rows are added here, since list
        // rows drawn inside an app can't use the widget's tap handling.
        val rows = WidgetRenderer.panelRows(this)
        val list = view.findViewById<ListView>(R.id.list) ?: return
        list.emptyView = view.findViewById(R.id.empty)
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val r = rows[position]
                val v = r.views.apply(this@PanelActivity, parent)
                val ctx = this@PanelActivity
                v.setOnClickListener { Apps.start(ctx, Apps.repoIntent(ctx, r.repoUrl)) }
                r.appPkg?.let { pkg ->
                    v.findViewById<View>(R.id.icon)?.setOnClickListener {
                        Apps.start(ctx, packageManager.getLaunchIntentForPackage(pkg) ?: Apps.repoIntent(ctx, r.repoUrl))
                    }
                }
                r.pages?.let { url ->
                    v.findViewById<View>(R.id.web)?.setOnClickListener {
                        Apps.start(ctx, Apps.browserIntent(ctx, url))
                    }
                }
                r.session?.let { url ->
                    v.findViewById<View>(R.id.claude)?.setOnClickListener {
                        Apps.start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
                if (r.update) {
                    v.findViewById<View>(R.id.update)?.setOnClickListener {
                        Apps.start(ctx, Apps.browserIntent(ctx, r.releasesUrl))
                    }
                }
                return v
            }
        }
    }
}
