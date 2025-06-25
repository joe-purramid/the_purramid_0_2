// PurramidWidgetProvider.kt
package com.example.purramid.thepurramid

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

class PurramidWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Update each widget instance
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // When the user deletes widgets, clean up their settings
        for (appWidgetId in appWidgetIds) {
            WidgetPrefs.deleteWidgetConfig(context, appWidgetId)
        }
    }

    companion object {
        // Helper function to update a single widget
        internal fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            // Load config
            val iconResId = WidgetPrefs.loadIconResId(context, appWidgetId)
            val launchIntent = WidgetPrefs.loadLaunchIntent(context, appWidgetId)

            // Construct the RemoteViews object
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Set the icon
            views.setImageViewResource(R.id.widget_image, iconResId)

            // Set the click listener to launch the configured intent
            if (launchIntent != null) {
                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                 // Ensure the launch intent clears any previous task stack and brings the target to front
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId, // Use unique request code per widget ID
                    launchIntent,
                    pendingIntentFlags
                )
                views.setOnClickPendingIntent(R.id.widget_image, pendingIntent)
            } else {
                // Optionally: Set a pending intent to re-launch configuration if intent is missing
                 val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
                     putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                     // Add flag to differentiate from initial config launch if needed
                     flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                 }
                 val configPendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                 val configPendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId, // Use same unique request code
                    configIntent,
                    configPendingIntentFlags
                 )
                 views.setOnClickPendingIntent(R.id.widget_image, configPendingIntent)
                 // You could also set a default "Tap to configure" icon/text here
                 views.setImageViewResource(R.id.widget_image, R.mipmap.ic_launcher) // Default icon
            }


            // Instruct the widget manager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}