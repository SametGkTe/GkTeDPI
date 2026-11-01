package com.sametgkte.gktedpi.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class GkTeCompactWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        GkTeWidgetUpdater.updateCompactWidgets(context, appWidgetManager, appWidgetIds)
    }
}
