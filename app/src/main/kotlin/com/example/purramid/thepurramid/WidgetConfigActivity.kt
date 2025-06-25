// WidgetConfigActivity.kt
package com.example.purramid.thepurramid

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.view.setPadding

class WidgetConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Set the result to CANCELED initially. If the user backs out, it stays canceled.
        setResult(RESULT_CANCELED)

        setContentView(R.layout.activity_widget_config) // Use your config layout

        // Find the widget id from the intent.
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If they launched this activity without specifying an appWidgetId, finish.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            Toast.makeText(this, "Invalid Widget ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupConfigOptions()
    }

    private fun setupConfigOptions() {
        val configContainer: LinearLayout = findViewById(R.id.config_options_container)
        configContainer.removeAllViews() // Clear any previous views if needed

        val options = listOf(
            WidgetConfigData("Clock", R.mipmap.tp_clock_launcher, Intent(this, ClockActivity::class.java)),
            WidgetConfigData("Randomizers", R.mipmap.tp_randomizers_launcher, Intent(this, RandomizersActivity::class.java)),
            WidgetConfigData("Screen Mask", R.mipmap.tp_screen_mask_launcher, Intent(this, ScreenMaskActivity::class.java)),
            WidgetConfigData("Spotlight", R.mipmap.tp_spotlight_launcher, Intent(this, SpotlightActivity::class.java)),
            WidgetConfigData("Timers", R.mipmap.tp_timers_launcher, Intent(this, TimersActivity::class.java)),
            WidgetConfigData("Traffic Light", R.mipmap.tp_traffic_light_launcher, Intent(this, TrafficLightActivity::class.java))
        )

        // Dynamically create buttons for each option
        options.forEach { configData ->
            val button = ImageButton(this).apply {
                setImageResource(configData.iconResId)
                contentDescription = configData.label
                setBackgroundResource(android.R.drawable.btn_default_small) // Basic background
                setPadding(16) // Add some padding
                setOnClickListener {
                    configureWidget(configData)
                }
            }
            // Add button to layout (adjust layout params as needed)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 8, 8, 8) // Add some margins
            }
            configContainer.addView(button, params)
        }
    }


    private fun configureWidget(configData: WidgetConfigData) {
        val context = this@WidgetConfigActivity
        val appWidgetManager = AppWidgetManager.getInstance(context)

        // Save the configuration using SharedPreferences
        WidgetPrefs.saveWidgetConfig(context, appWidgetId, configData.iconResId, configData.intent)

        // Construct the RemoteViews object for the final widget appearance
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        views.setImageViewResource(R.id.widget_image, configData.iconResId)

        // Create the PendingIntent to launch when clicked
         val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // Ensure the launch intent clears any previous task stack and brings the target to front
        configData.intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

        val pendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId, // Use unique request code per widget
            configData.intent,
            pendingIntentFlags
        )
        views.setOnClickPendingIntent(R.id.widget_image, pendingIntent)


        // Tell the AppWidgetManager to perform an update on the current app widget
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // Make sure we pass back the original appWidgetId
        val resultValue = Intent()
        resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish() // Configuration complete
    }
}