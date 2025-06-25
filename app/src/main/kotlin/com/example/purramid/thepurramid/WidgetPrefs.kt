// WidgetPrefs.kt
package com.example.purramid.thepurramid

import android.content.Context
import android.content.Intent

object WidgetPrefs {

    private const val PREFS_NAME = "com.example.purramid.thepurramid.WidgetPrefs"
    private const val PREF_PREFIX_KEY = "widget_"
    private const val PREF_ICON_SUFFIX = "_icon"
    private const val PREF_INTENT_URI_SUFFIX = "_intent_uri"

    // Save the configuration for a specific widget ID
    fun saveWidgetConfig(context: Context, appWidgetId: Int, iconResId: Int, launchIntent: Intent) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        val intentUri = launchIntent.toUri(Intent.URI_INTENT_SCHEME) // Convert Intent to String URI
        prefs.putInt(PREF_PREFIX_KEY + appWidgetId + PREF_ICON_SUFFIX, iconResId)
        prefs.putString(PREF_PREFIX_KEY + appWidgetId + PREF_INTENT_URI_SUFFIX, intentUri)
        prefs.apply()
    }

    // Load the icon resource ID for a specific widget ID
    fun loadIconResId(context: Context, appWidgetId: Int): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Return a default icon or 0 if not found
        return prefs.getInt(PREF_PREFIX_KEY + appWidgetId + PREF_ICON_SUFFIX, R.mipmap.ic_launcher) // Use app's main launcher as default
    }

    // Load the launch Intent for a specific widget ID
    fun loadLaunchIntent(context: Context, appWidgetId: Int): Intent? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intentUri = prefs.getString(PREF_PREFIX_KEY + appWidgetId + PREF_INTENT_URI_SUFFIX, null)
        return intentUri?.let {
            try {
                Intent.parseUri(it, Intent.URI_INTENT_SCHEME)
            } catch (e: Exception) {
                // Handle error: URI parsing failed
                null
            }
        }
    }

    // Delete the configuration for a specific widget ID when it's removed
    fun deleteWidgetConfig(context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.remove(PREF_PREFIX_KEY + appWidgetId + PREF_ICON_SUFFIX)
        prefs.remove(PREF_PREFIX_KEY + appWidgetId + PREF_INTENT_URI_SUFFIX)
        prefs.apply()
    }
}