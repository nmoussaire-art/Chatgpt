package com.ontimequant.data.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val colour: Int?,
)

data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val start: Instant,
    val end: Instant?,
    val zone: ZoneId,
    val location: String?,
    val allDay: Boolean,
    val hasReminder: Boolean,
) {
    val hasUsableDestination: Boolean get() = !location.isNullOrBlank()
}

/**
 * Read-only access to the device calendar.
 *
 * Privacy rules enforced here rather than by convention:
 *  - Only calendars the user explicitly selected are queried; an empty selection reads
 *    nothing at all.
 *  - Only title, time, zone, location and reminder-presence are read. Descriptions,
 *    attendees, organisers and free/busy status are never touched.
 *  - Nothing read here is sent anywhere. The only value that ever leaves the device is
 *    the destination *string* of an event the user chose to forecast, and only if a live
 *    Places/Routes key is configured.
 */
@Singleton
class CalendarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun calendars(): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, null, null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            DeviceCalendar(
                                id = cursor.getLong(0),
                                displayName = cursor.getStringOrEmpty(1),
                                accountName = cursor.getStringOrEmpty(2),
                                colour = if (cursor.isNull(3)) null else cursor.getInt(3),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Events in `[from, to]` from the selected calendars only.
     *
     * All-day events are excluded: they carry no meaningful arrival deadline, and
     * forecasting a departure for one would be noise.
     */
    suspend fun events(
        calendarIds: Set<Long>,
        from: Instant,
        to: Instant,
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission() || calendarIds.isEmpty()) return@withContext emptyList()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.HAS_ALARM,
        )
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection =
            "${CalendarContract.Events.CALENDAR_ID} IN ($placeholders) AND " +
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND " +
                "${CalendarContract.Events.DELETED} = 0"
        val args = calendarIds.map { it.toString() }.toTypedArray() +
            arrayOf(from.toEpochMilli().toString(), to.toEpochMilli().toString())

        runCatching {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection, args,
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val allDay = cursor.getInt(7) == 1
                        if (allDay) continue
                        val startMillis = cursor.getLong(3)
                        if (startMillis <= 0) continue
                        val zoneId = cursor.getStringOrEmpty(5)
                        add(
                            CalendarEvent(
                                eventId = cursor.getLong(0),
                                calendarId = cursor.getLong(1),
                                title = cursor.getStringOrEmpty(2).ifBlank { "Untitled event" },
                                start = Instant.ofEpochMilli(startMillis),
                                end = cursor.getLong(4).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                                // The event's own zone is preserved so a meeting booked in
                                // another country keeps its correct wall-clock time.
                                zone = runCatching { ZoneId.of(zoneId) }
                                    .getOrDefault(ZoneId.systemDefault()),
                                location = cursor.getStringOrEmpty(6).takeIf { it.isNotBlank() },
                                allDay = false,
                                hasReminder = cursor.getInt(8) == 1,
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun Cursor.getStringOrEmpty(index: Int): String =
        if (isNull(index)) "" else getString(index).orEmpty()
}
