package com.jono.githubwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

object WidgetRenderer {

    /** How a list row looks: just the icon (1 column), compact (2 columns), or wide (3+ columns). */
    private enum class Item(val layout: Int, val sub: Boolean, val text: Boolean) {
        ICON(R.layout.item_icon, sub = false, text = false),
        COMPACT(R.layout.item_compact, sub = false, text = true),
        WIDE(R.layout.item_wide, sub = true, text = true),
    }

    /**
     * Every layout, the size (dp) it's designed for, and what it contains. Android shows the layout
     * whose size fits inside the widget and is closest to it. Typical 4-column grid: 1 cell ≈ 80dp
     * wide, 2 ≈ 180, 3 ≈ 270, 4 ≈ 360; rows ≈ 80–100dp. The setup screen lists the real sizes.
     *
     * 1-row sizes show the most recently pushed repo (tap for the full list) plus the next few as
     * icons; 2+ rows show a scrolling list of every repo.
     */
    private enum class Kind(
        val layout: Int,
        val size: SizeF,
        val label: String,
        val text: Boolean = false,
        val sub: Boolean = false,
        val grid: Int = 0,
        val item: Item? = null,
        val header: Boolean = false,
        val expands: Boolean = true,
    ) {
        TILE(R.layout.widget_tile, SizeF(50f, 50f), "latest repo's icon"),
        STRIP_S(R.layout.widget_strip_s, SizeF(150f, 50f), "latest repo: icon, name, CI + commit, details",
            text = true, sub = true),
        STRIP(R.layout.widget_strip, SizeF(220f, 50f), "latest repo | its text | next 4 as a 2×2 grid",
            text = true, sub = true, grid = 4),
        STRIP_L(R.layout.widget_strip_l, SizeF(320f, 50f), "latest repo, its text, next 4 icons",
            text = true, sub = true, grid = 4),
        COL(R.layout.widget_list, SizeF(50f, 110f), "scrolling list of icons", item = Item.ICON),
        LIST(R.layout.widget_list, SizeF(150f, 110f), "scrolling list: name, age, CI + commit",
            item = Item.COMPACT, header = true),
        LIST_W(R.layout.widget_list, SizeF(220f, 110f), "scrolling list with everything",
            item = Item.WIDE, header = true, expands = false),
    }

    private class Row(val repo: Store.Repo, val icon: Uri, val app: Apps.Installed?) {
        /** A newer build of an app installed from this repo is on GitHub. */
        val update get() = app != null && repo.lastBuild != null && repo.lastBuild > app.build
    }

    private class Model(
        val rows: List<Row>,
        val login: String?,
        val needsToken: Boolean,
        /** Shown instead of the list when there's nothing to list. */
        val message: String,
        /** Header: when the list was last refreshed, or "updating…". */
        val status: String,
        val ghIcon: Uri?,
    )

    private val GRID = intArrayOf(R.id.g1, R.id.g2, R.id.g3, R.id.g4)

    /** In-app views (the expand panel) that mirror the widget and redraw with it. */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Redraws every placed widget. Never throws: a drawing bug is saved for the setup screen instead. */
    fun renderAll(ctx: Context) {
        try {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ReposWidget::class.java))
            if (ids.isNotEmpty()) {
                mgr.updateAppWidget(ids, build(ctx))
                @Suppress("DEPRECATION")
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.list) // re-reads the list rows
            }
        } catch (e: Exception) {
            Crash.record(ctx, e, fatal = false)
        }
        listeners.forEach { it() }
    }

    /** The biggest view, for the expand panel (drawn in this app, so rows go straight in). */
    fun buildPanel(ctx: Context): RemoteViews =
        fill(ctx, RemoteViews(ctx.packageName, Kind.LIST_W.layout), model(ctx), Kind.LIST_W, inApp = true)

    /** Rows for one list style, for ListService: (stable id, row view). */
    fun listRows(ctx: Context, itemName: String?): List<Pair<Long, RemoteViews>> {
        val item = Item.values().firstOrNull { it.name == itemName } ?: Item.WIDE
        val m = model(ctx)
        return m.rows.map { it.repo.fullName.hashCode().toLong() to itemView(ctx, item, it, m.login) }
    }

    private fun build(ctx: Context): RemoteViews {
        val m = model(ctx)
        return RemoteViews(
            Kind.values().associate { kind ->
                kind.size to fill(ctx, RemoteViews(ctx.packageName, kind.layout), m, kind)
            }
        )
    }

    /** Same rule Android uses to pick a layout, so the setup screen can say which one you'll get. */
    private fun bestFit(w: Float, h: Float): Kind {
        val fitting = Kind.values().filter { it.size.width <= w && it.size.height <= h }
        return fitting.minByOrNull {
            val dw = w - it.size.width
            val dh = h - it.size.height
            dw * dw + dh * dh
        } ?: Kind.TILE
    }

    /** One line per widget on the home screen: its portrait size and the layout it shows. */
    fun describeSizes(ctx: Context): String {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, ReposWidget::class.java))
        if (ids.isEmpty()) return "No widget on your home screen yet."
        return ids.joinToString("\n") { id ->
            val o = mgr.getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            "• ${w}×${h} dp → ${bestFit(w.toFloat(), h.toFloat()).label}"
        }
    }

    private fun model(ctx: Context): Model {
        val token = Store.token(ctx)
        val repos = if (token == null) emptyList() else Store.visibleRepos(ctx)
        val rows = repos.map { r ->
            val app = Apps.installed(ctx, r)
            Row(r, Apps.repoIcon(ctx, r, app), app)
        }
        val error = Store.error(ctx)
        val refreshing = Store.refreshing(ctx)
        val updated = Store.updatedAt(ctx)
        val message = when {
            token == null -> "Tap to add a GitHub token"
            rows.isEmpty() && error != null -> error
            rows.isEmpty() && (refreshing || updated == 0L) -> "Loading…"
            else -> "No repos"
        }
        val status = when {
            token == null -> ""
            refreshing -> "updating…"
            updated == 0L -> ""
            error != null -> "⚠ " + age(updated)
            else -> age(updated)
        }
        return Model(rows, Store.login(ctx), token == null, message, status, Apps.githubIcon(ctx))
    }

    private fun fill(ctx: Context, rv: RemoteViews, m: Model, kind: Kind, inApp: Boolean = false): RemoteViews {
        val setup = activity(ctx, Intent(ctx, MainActivity::class.java), 1)
        val panel = activity(ctx, Intent(ctx, PanelActivity::class.java), 2)

        if (kind.item != null) {
            fillList(ctx, rv, m, kind, kind.item, setup, panel, inApp)
            return rv
        }

        // --- 1-row sizes: the newest repo, then the next few as icons ---
        val top = m.rows.firstOrNull()
        val tap = if (top == null) setup else if (kind.expands) panel else open(ctx, OpenActivity.REPO, top.repo.htmlUrl)
        if (top != null) rv.setImageViewUri(R.id.art, top.icon) else rv.setImageViewResource(R.id.art, R.drawable.tile_placeholder)
        rv.setOnClickPendingIntent(R.id.art, tap)

        if (kind.text) {
            rv.setOnClickPendingIntent(R.id.content, tap)
            ghButton(ctx, rv, m)
            if (top != null) {
                bindText(rv, top, m.login, kind.sub)
                if (top.update) rv.setOnClickPendingIntent(R.id.update, open(ctx, OpenActivity.UPDATE, top.repo.releasesUrl))
            } else {
                rv.setTextViewText(R.id.name, if (m.needsToken) "Repos" else m.message)
                rv.setTextViewText(R.id.msg, if (m.needsToken) m.message else "")
                rv.setTextViewText(R.id.age, "")
                rv.setViewVisibility(R.id.ci, View.GONE)
                rv.setViewVisibility(R.id.update, View.GONE)
                rv.setViewVisibility(R.id.sub, View.GONE)
            }
        }

        val rest = m.rows.drop(1)
        for (i in 0 until kind.grid) {
            val row = rest.getOrNull(i)
            if (row == null) {
                rv.setViewVisibility(GRID[i], View.INVISIBLE)
                continue
            }
            rv.setViewVisibility(GRID[i], View.VISIBLE)
            rv.setImageViewUri(GRID[i], row.icon)
            rv.setContentDescription(GRID[i], row.repo.name)
            rv.setOnClickPendingIntent(GRID[i], open(ctx, OpenActivity.REPO, row.repo.htmlUrl))
        }
        return rv
    }

    private fun fillList(
        ctx: Context, rv: RemoteViews, m: Model, kind: Kind, item: Item,
        setup: PendingIntent, panel: PendingIntent, inApp: Boolean,
    ) {
        ghButton(ctx, rv, m)
        val headerVis = if (kind.header) View.VISIBLE else View.GONE
        rv.setViewVisibility(R.id.title, headerVis)
        rv.setViewVisibility(R.id.updated, headerVis)
        rv.setTextViewText(R.id.updated, m.status)
        if (kind.expands) rv.setOnClickPendingIntent(R.id.title, panel)
        rv.setOnClickPendingIntent(
            R.id.refresh,
            PendingIntent.getBroadcast(
                ctx, 3,
                Intent(ctx, ReposWidget::class.java).setAction(ReposWidget.ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

        rv.setTextViewText(R.id.empty, m.message)
        if (m.needsToken) rv.setOnClickPendingIntent(R.id.empty, setup)
        rv.setEmptyView(R.id.list, R.id.empty)

        if (inApp) {
            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            m.rows.forEach { items.addItem(it.repo.fullName.hashCode().toLong(), itemView(ctx, item, it, m.login)) }
            rv.setRemoteAdapter(R.id.list, items.build())
        } else {
            // The launcher asks ListService for the rows, so a long list never makes the widget
            // update too big to send (which crashes the app). The URI says which row style.
            @Suppress("DEPRECATION")
            rv.setRemoteAdapter(
                R.id.list,
                Intent(ctx, ListService::class.java).setData(Uri.parse("repos://list/${item.name}")),
            )
        }
        // Rows say what to open; this sends it to OpenActivity. Must be mutable so rows can fill it in.
        rv.setPendingIntentTemplate(
            R.id.list,
            PendingIntent.getActivity(
                ctx, 4, Intent(ctx, OpenActivity::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    private fun itemView(ctx: Context, item: Item, row: Row, login: String?): RemoteViews {
        val v = RemoteViews(ctx.packageName, item.layout)
        v.setImageViewUri(R.id.icon, row.icon)
        v.setContentDescription(R.id.icon, row.repo.name)
        v.setOnClickFillInIntent(R.id.row, fill(OpenActivity.REPO, row.repo.htmlUrl))
        row.app?.let {
            v.setOnClickFillInIntent(
                R.id.icon,
                fill(OpenActivity.APP, row.repo.htmlUrl).putExtra(OpenActivity.EXTRA_PKG, it.pkg),
            )
        }
        if (item.text) {
            bindText(v, row, login, item.sub)
            if (row.update) v.setOnClickFillInIntent(R.id.update, fill(OpenActivity.UPDATE, row.repo.releasesUrl))
        }
        return v
    }

    /** The GitHub app's own icon as a button that opens it. */
    private fun ghButton(ctx: Context, rv: RemoteViews, m: Model) {
        if (m.ghIcon != null) rv.setImageViewUri(R.id.gh, m.ghIcon) else rv.setImageViewResource(R.id.gh, R.drawable.ic_open)
        rv.setOnClickPendingIntent(R.id.gh, open(ctx, OpenActivity.GITHUB, null))
    }

    /** Name + age, CI result + latest commit, and (wide) installed build · language · description. */
    private fun bindText(rv: RemoteViews, row: Row, login: String?, withSub: Boolean) {
        val r = row.repo
        rv.setTextViewText(R.id.name, if (login == null || r.owner == login) r.name else "${r.owner}/${r.name}")
        rv.setTextViewText(R.id.age, if (r.pushedAt > 0) age(r.pushedAt) else "")
        rv.setViewVisibility(R.id.update, if (row.update) View.VISIBLE else View.GONE)

        val run = r.run
        if (run == null) {
            rv.setViewVisibility(R.id.ci, View.GONE)
        } else {
            val (glyph, color) = when {
                run.status != "completed" -> "●" to R.color.ci_run
                run.conclusion == "success" -> "✓" to R.color.ci_ok
                run.conclusion in setOf("failure", "timed_out", "startup_failure") -> "✗" to R.color.ci_fail
                else -> "⊘" to R.color.ci_off
            }
            rv.setViewVisibility(R.id.ci, View.VISIBLE)
            rv.setTextViewText(R.id.ci, "$glyph #${run.number}")
            rv.setColor(R.id.ci, "setTextColor", color)
        }

        val commit = (run?.message ?: r.lastCommit)?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        rv.setTextViewText(R.id.msg, commit ?: r.description ?: r.language ?: "")

        if (withSub) {
            val sub = listOfNotNull(
                row.app?.let { "build ${it.build} installed" },
                r.language.takeIf { commit != null || r.description != null },
                r.description.takeIf { commit != null },
            ).joinToString(" · ")
            rv.setTextViewText(R.id.sub, sub)
            rv.setViewVisibility(R.id.sub, if (sub.isEmpty()) View.GONE else View.VISIBLE)
        }
    }

    /** "now", "5m", "3h", "2d", "Oct 12", "Mar 2024". */
    fun age(ms: Long): String {
        val mins = (System.currentTimeMillis() - ms) / 60_000
        return when {
            mins < 1 -> "now"
            mins < 60 -> "${mins}m"
            mins < 24 * 60 -> "${mins / 60}h"
            mins < 7 * 24 * 60 -> "${mins / (24 * 60)}d"
            else -> {
                val then = Calendar.getInstance().apply { timeInMillis = ms }
                val sameYear = then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
                SimpleDateFormat(if (sameYear) "MMM d" else "MMM yyyy", Locale.getDefault()).format(Date(ms))
            }
        }
    }

    private fun fill(action: String, url: String) = Intent(action).setData(Uri.parse(url))

    /** A tap that goes through OpenActivity. Action + URL keep each one distinct. */
    private fun open(ctx: Context, action: String, url: String?): PendingIntent {
        val i = Intent(ctx, OpenActivity::class.java).setAction(action)
        if (url != null) i.data = Uri.parse(url)
        return activity(ctx, i, 0)
    }

    private fun activity(ctx: Context, intent: Intent, code: Int) = PendingIntent.getActivity(
        ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
