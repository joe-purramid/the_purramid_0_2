// WidgetConfigData.kt
package com.example.purramid.thepurramid

import android.content.Intent
import androidx.annotation.DrawableRes

data class WidgetConfigData(
    val label: String,
    @DrawableRes val iconResId: Int,
    val intent: Intent // The intent to launch when the widget is clicked
)