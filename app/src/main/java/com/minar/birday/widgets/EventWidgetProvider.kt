package com.minar.birday.widgets

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.preference.PreferenceManager
import com.minar.birday.R
import com.minar.birday.activities.MainActivity
import com.minar.birday.model.EventCode
import com.minar.birday.model.EventResult
import com.minar.birday.persistence.EventDao
import com.minar.birday.persistence.EventDatabase
import com.minar.birday.utilities.byteArrayToBitmap
import com.minar.birday.utilities.formatEventList
import com.minar.birday.utilities.getNextYears
import com.minar.birday.utilities.nextDateFormatted
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle


@ExperimentalStdlibApi
class EventWidgetProvider : AppWidgetProvider() {

    override fun onDeleted(context: Context?, appWidgetIds: IntArray?) {
        super.onDeleted(context, appWidgetIds)
    }

    override fun onDisabled(context: Context?) {
        super.onDisabled(context)
    }

    override fun onEnabled(context: Context?) {
        super.onEnabled(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action.equals(AppWidgetManager.ACTION_APPWIDGET_UPDATE)) {
            val mgr = AppWidgetManager.getInstance(context)
            val cn = ComponentName(context, EventWidgetProvider::class.java)
            mgr.getAppWidgetIds(cn).forEach { appWidgetId ->
                updateAppWidget(context, mgr, appWidgetId)
            }
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Update each of the widgets with the remote adapter
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

@ExperimentalStdlibApi
internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
) {
    try {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val hideImages = sp.getBoolean("hide_images", false)
        val formatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
        val views = RemoteViews(context.packageName, R.layout.widget_upcoming)
        val intent = Intent(context, MainActivity::class.java)

        views.setTextViewText(R.id.eventWidgetDate, formatter.format(LocalDate.now()))
        Thread {
            // Get the next events and the proper formatter
            val eventDao: EventDao = EventDatabase.getBirdayDatabase(context).eventDao()
            val nextEvents: List<EventResult> = eventDao.getOrderedNextEventsStatic()

            // Launch the app on click
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pendingIntent =
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            views.setOnClickPendingIntent(R.id.background, pendingIntent)

            // If there are zero events, hide the list
            if (nextEvents.isEmpty()) {
                views.setViewVisibility(R.id.eventWidgetList, View.GONE)
                // Restore the widget to its default state
                views.setTextViewText(
                    R.id.eventWidgetText,
                    context.getString(R.string.no_next_event)
                )
                views.setImageViewResource(
                    R.id.eventWidgetImage,
                    R.drawable.placeholder_other_image
                )
            }
            // If there's one event or more, update the list and the main widget
            else {
                views.setViewVisibility(R.id.eventWidgetList, View.VISIBLE)

                // Remove events in the future today (eg: now is december 1st 2023, an event has original date = december 1st 2050)
                var filteredNextEvents = nextEvents.toMutableList()
                filteredNextEvents.removeIf { getNextYears(it) == 0 }
                // If the events are all in the future, display them but avoid confetti
                if (filteredNextEvents.isEmpty()) {
                    filteredNextEvents = nextEvents.toMutableList()
                }

                // Make sure to show if there's more than one event
                var widgetUpcoming = formatEventList(filteredNextEvents, true, context, false)
                if (filteredNextEvents.isNotEmpty()) widgetUpcoming += "\n${
                    nextDateFormatted(
                        filteredNextEvents[0],
                        formatter,
                        context
                    )
                }"
                views.setTextViewText(R.id.eventWidgetText, widgetUpcoming)
                views.setTextViewText(R.id.eventWidgetTitle, context.getString(R.string.appwidget_upcoming))

                // Else proceed to fill the data for the next event
                if (filteredNextEvents[0].image != null && filteredNextEvents[0].image!!.isNotEmpty() && !hideImages) {
                    views.setImageViewBitmap(
                        R.id.eventWidgetImage,
                        byteArrayToBitmap(filteredNextEvents[0].image!!)
                    )
                } else views.setImageViewResource(
                    R.id.eventWidgetImage,
                    // Set the image depending on the event type, the drawable are a b&w version
                    when (filteredNextEvents[0].type) {
                        EventCode.BIRTHDAY.name -> R.drawable.placeholder_birthday_image
                        EventCode.ANNIVERSARY.name -> R.drawable.placeholder_anniversary_image
                        EventCode.DEATH.name -> R.drawable.placeholder_death_image
                        EventCode.NAME_DAY.name -> R.drawable.placeholder_name_day_image
                        else -> R.drawable.placeholder_other_image
                    }
                )

                // Set up the intent that starts the EventViewService, which will provide the views
                val widgetServiceIntent = Intent(context, EventWidgetService::class.java)

                // Set up the RemoteViews object to use a RemoteViews adapter and populate the data
                views.apply {
                    setRemoteAdapter(R.id.eventWidgetList, widgetServiceIntent)
                    // setEmptyView can be used to choose the view displayed when the collection has no items
                }

                // Template to handle the click listener for each item
                val clickIntentTemplate = Intent(context, MainActivity::class.java)
                val clickPendingIntentTemplate: PendingIntent = TaskStackBuilder.create(context)
                    .addNextIntentWithParentStack(clickIntentTemplate)
                    .getPendingIntent(3, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setPendingIntentTemplate(R.id.eventWidgetList, clickPendingIntentTemplate)

                // Fill the list with the next events
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.eventWidgetList)
            }
            // Instruct the widget manager to update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }.start()
    } catch (e: Exception) {
        Log.d("widget", "${e.message}")
    }
}