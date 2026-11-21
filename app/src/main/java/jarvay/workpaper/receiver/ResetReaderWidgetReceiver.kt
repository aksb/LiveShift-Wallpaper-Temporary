package jarvay.workpaper.receiver

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import jarvay.workpaper.glance.ResetReaderWidget

@AndroidEntryPoint
class ResetReaderWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = ResetReaderWidget()
}
