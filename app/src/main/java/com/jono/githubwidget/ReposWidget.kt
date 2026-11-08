package com.jono.githubwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class ReposWidget : AppWidgetProvider() {

    /** Placed, rebooted, or this app updated: redraw with the saved list, then fetch a fresh one. */
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.renderAll(ctx)
        RefreshJob.schedule(ctx)
        RefreshJob.now(ctx)
    }

    override fun onDisabled(ctx: Context) = RefreshJob.cancel(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            RefreshJob.now(ctx)
            WidgetRenderer.renderAll(ctx) // shows "updating…"
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.jono.githubwidget.REFRESH"
    }
}
