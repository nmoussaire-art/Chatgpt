package com.batterycast.quant.core.system

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.batterycast.quant.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A calendar event the user can pick as a battery target. */
data class CalendarTarget(
    val id: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
)

/**
 * Optional read-only access to upcoming calendar events.
 *
 * The permission buys exactly one thing: the ability to say "your flight is at 8 pm" instead of
 * making the user type it in. Only title, start and end are read, only for events in the next
 * couple of days, and nothing is stored — the chosen event's timestamp is kept, the event itself
 * is not copied into the database. Nothing is uploaded, because the app has no network permission
 * with which to upload it.
 *
 * Every forecast in the app works with this permission denied.
 */
@Singleton
class CalendarEventProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Upcoming events between now and [lookaheadMs] from now.
     *
     * Returns an empty list when the permission is absent — the caller shows the permission
     * explanation rather than any events.
     */
    suspend fun upcomingEvents(
        nowMs: Long = System.currentTimeMillis(),
        lookaheadMs: Long = DEFAULT_LOOKAHEAD_MS,
        limit: Int = 20,
    ): List<CalendarTarget> = withContext(ioDispatcher) {
        if (!hasPermission()) return@withContext emptyList()

        runCatching {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, nowMs)
            ContentUris.appendId(builder, nowMs + lookaheadMs)

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            )

            context.contentResolver.query(
                builder.build(),
                projection,
                "${CalendarContract.Instances.BEGIN} >= ?",
                arrayOf(nowMs.toString()),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val allDay = cursor.getInt(4) == 1
                        // An all-day event has no useful target time; it would make the forecast
                        // claim precision the calendar does not contain.
                        if (allDay) continue
                        val start = cursor.getLong(2)
                        if (start <= nowMs) continue
                        add(
                            CalendarTarget(
                                id = cursor.getLong(0),
                                title = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "Untitled event",
                                startMs = start,
                                endMs = cursor.getLong(3),
                                allDay = false,
                            ),
                        )
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    companion object {
        const val DEFAULT_LOOKAHEAD_MS = 48L * 60 * 60 * 1000
    }
}
