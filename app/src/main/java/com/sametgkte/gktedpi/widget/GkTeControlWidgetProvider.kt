package com.sametgkte.gktedpi.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class GkTeControlWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        GkTeWidgetUpdater.updateControlWidgets(context, appWidgetManager, appWidgetIds)
    }
}
