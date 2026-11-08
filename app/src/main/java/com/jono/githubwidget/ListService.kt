package com.jono.githubwidget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

/** Hands the launcher the list rows one at a time, instead of packing them all into the widget update. */
class ListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.data?.lastPathSegment)

    private class Factory(val ctx: Context, val style: String?) : RemoteViewsFactory {
        private var rows: List<Pair<Long, RemoteViews>> = emptyList()

        override fun onCreate() {}
        override fun onDataSetChanged() {
            rows = try {
                WidgetRenderer.listRows(ctx, style)
            } catch (e: Exception) {
                Crash.record(ctx, e, fatal = false)
                emptyList()
            }
        }
        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewAt(position: Int): RemoteViews? = rows.getOrNull(position)?.second
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = rows.getOrNull(position)?.first ?: position.toLong()
        override fun hasStableIds() = true
    }
}
