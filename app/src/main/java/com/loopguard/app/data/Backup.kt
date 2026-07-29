package com.loopguard.app.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

class BackupFormatException(message: String) : Exception(message)

/** A loop and its timeline, before it has been given database ids. */
data class ImportedLoop(
    val loop: Loop,
    val events: List<LoopEvent>,
)

data class BackupPayload(
    val schema: Int,
    val exportedAt: String?,
    val loops: List<ImportedLoop>,
) {
    val isLegacy: Boolean get() = schema < 2
    val openCount: Int get() = loops.count { it.loop.statusEnum == LoopStatus.OPEN }
    val doneCount: Int get() = loops.count { it.loop.statusEnum == LoopStatus.DONE }
}

/**
 * Backup format for LoopGuard.
 *
 * The export is deliberately a superset of the v1.0 WebView format: the
 * `loops` / `done` split and the `history` pairs are still there with the same
 * key names, so a v2 export can be read by v1 as well as v2. New fields are
 * added alongside, and v1 simply ignores them.
 */
object BackupSerializer {

    const val SCHEMA = 2

    fun export(
        loops: List<Loop>,
        eventsByLoop: Map<Long, List<LoopEvent>>,
        appVersion: String,
    ): String {
        val open = JSONArray()
        val done = JSONArray()

        loops.forEach { loop ->
            val obj = loopToJson(loop, eventsByLoop[loop.id].orEmpty())
            if (loop.statusEnum == LoopStatus.DONE) done.put(obj) else open.put(obj)
        }

        return JSONObject().apply {
            put("app", "LoopGuard")
            put("schema", SCHEMA)
            put("appVersion", appVersion)
            put("exportedAt", Instant.now().toString())
            put("loops", open)
            put("done", done)
        }.toString(2)
    }

    private fun loopToJson(loop: Loop, events: List<LoopEvent>): JSONObject = JSONObject().apply {
        // --- v1 compatible keys -------------------------------------------
        put("id", loop.legacyId ?: loop.id.toString())
        put("title", loop.title)
        put("person", loop.whoLabelForExport())
        put("side", loop.side)
        put("category", loop.category)
        put("due", loop.due?.toString() ?: JSONObject.NULL)
        put("impact", loop.impact)
        put("last", loop.lastActivityDate.toString())
        put("notes", loop.notes)
        loop.closedAt?.let {
            put("closed", Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())
        }
        put("history", JSONArray().apply {
            events.sortedBy { it.at }.forEach { e ->
                put(JSONArray().apply { put(e.text); put(LocalDate.ofEpochDay(e.date).toString()) })
            }
        })

        // --- v2 additions --------------------------------------------------
        put("counterparty", loop.counterparty)
        put("organisation", loop.organisation)
        put("status", loop.status)
        put("reference", loop.reference)
        put("tags", JSONArray(loop.tagList))
        put("followUpCount", loop.followUpCount)
        put("pinned", loop.pinned)
        put("createdAt", loop.createdAt)
        loop.snoozedUntil?.let { put("snoozedUntil", LocalDate.ofEpochDay(it).toString()) }
        put("timeline", JSONArray().apply {
            events.sortedBy { it.at }.forEach { e ->
                put(JSONObject().apply {
                    put("text", e.text)
                    put("date", LocalDate.ofEpochDay(e.date).toString())
                    put("at", e.at)
                    put("kind", e.kind)
                })
            }
        })
    }

    private fun Loop.whoLabelForExport(): String =
        listOf(counterparty, organisation).filter { it.isNotBlank() }.joinToString(" / ")

    fun parse(raw: String): BackupPayload {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw BackupFormatException("The file or text is empty.")

        val root = try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            throw BackupFormatException("This does not look like a LoopGuard backup (invalid JSON).")
        }

        if (!root.has("loops") && !root.has("done")) {
            throw BackupFormatException(
                "No LoopGuard data found. A backup should contain a \"loops\" list."
            )
        }

        val schema = root.optInt("schema", 1)
        val result = mutableListOf<ImportedLoop>()

        root.optJSONArray("loops")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { result += parseLoop(it, LoopStatus.OPEN) }
            }
        }
        root.optJSONArray("done")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { result += parseLoop(it, LoopStatus.DONE) }
            }
        }

        if (result.isEmpty()) {
            throw BackupFormatException("The backup was readable but contained no loops.")
        }

        return BackupPayload(
            schema = schema,
            exportedAt = root.optString("exportedAt").takeIf { it.isNotBlank() },
            loops = result,
        )
    }

    private fun parseLoop(obj: JSONObject, fallbackStatus: LoopStatus): ImportedLoop {
        val title = obj.optString("title").trim().ifBlank { "Untitled loop" }

        // v1 crammed person and organisation into one "person" string, often
        // separated by "/". Split it back apart where we safely can.
        val personRaw = obj.optString("person").trim()
        val counterparty = obj.optString("counterparty").trim().ifBlank {
            personRaw.substringBefore('/').trim()
        }
        val organisation = obj.optString("organisation").trim().ifBlank {
            if ('/' in personRaw) personRaw.substringAfter('/').trim() else ""
        }

        val status = LoopStatus.from(obj.optString("status").ifBlank { fallbackStatus.storageKey })
        val due = parseDate(obj.opt("due"))
        val last = parseDate(obj.opt("last")) ?: LocalDate.now()
        val snoozed = parseDate(obj.opt("snoozedUntil"))
        val closedDay = parseDate(obj.opt("closed"))

        val legacyId = obj.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }

        val loop = Loop(
            id = 0,
            title = title,
            counterparty = counterparty.take(120),
            organisation = organisation.take(120),
            side = Side.from(obj.optString("side")).storageKey,
            category = Categories.normalise(obj.optString("category")),
            dueDate = due?.toEpochDay(),
            impact = obj.optInt("impact", 3).coerceIn(1, 5),
            lastActivity = last.toEpochDay(),
            notes = obj.optString("notes").trim(),
            status = status.storageKey,
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            closedAt = when {
                status != LoopStatus.DONE -> null
                closedDay != null -> closedDay.atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
                else -> System.currentTimeMillis()
            },
            reference = obj.optString("reference").trim(),
            tags = parseTags(obj.opt("tags")),
            snoozedUntil = snoozed?.toEpochDay(),
            followUpCount = obj.optInt("followUpCount", 0).coerceAtLeast(0),
            pinned = obj.optBoolean("pinned", false),
            legacyId = legacyId,
        )

        return ImportedLoop(loop, parseEvents(obj, last))
    }

    private fun parseEvents(obj: JSONObject, fallbackDate: LocalDate): List<LoopEvent> {
        // v2 timeline wins when present; otherwise fall back to v1 history pairs.
        obj.optJSONArray("timeline")?.let { arr ->
            if (arr.length() > 0) {
                return (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    val date = parseDate(e.opt("date")) ?: fallbackDate
                    LoopEvent(
                        loopId = 0,
                        text = e.optString("text").ifBlank { "Update" },
                        date = date.toEpochDay(),
                        at = e.optLong("at", date.toEpochDay() * 86_400_000L),
                        kind = e.optString("kind").ifBlank { EventKind.NOTE },
                    )
                }
            }
        }

        val history = obj.optJSONArray("history") ?: return emptyList()
        return (0 until history.length()).mapNotNull { i ->
            when (val entry = history.opt(i)) {
                is JSONArray -> {
                    val text = entry.optString(0).ifBlank { "Update" }
                    val date = parseDate(entry.opt(1)) ?: fallbackDate
                    LoopEvent(
                        loopId = 0,
                        text = text,
                        date = date.toEpochDay(),
                        at = date.toEpochDay() * 86_400_000L + i,
                        kind = EventKind.IMPORTED,
                    )
                }
                is JSONObject -> {
                    val date = parseDate(entry.opt("date")) ?: fallbackDate
                    LoopEvent(
                        loopId = 0,
                        text = entry.optString("text").ifBlank { "Update" },
                        date = date.toEpochDay(),
                        at = date.toEpochDay() * 86_400_000L + i,
                        kind = EventKind.IMPORTED,
                    )
                }
                is String -> LoopEvent(
                    loopId = 0,
                    text = entry,
                    date = fallbackDate.toEpochDay(),
                    at = fallbackDate.toEpochDay() * 86_400_000L + i,
                    kind = EventKind.IMPORTED,
                )
                else -> null
            }
        }
    }

    private fun parseTags(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until value.length())
            .mapNotNull { value.optString(it).trim().takeIf(String::isNotEmpty) }
            .joinToString(",")
        is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
        else -> ""
    }

    private fun parseDate(value: Any?): LocalDate? {
        if (value == null || value == JSONObject.NULL) return null
        val text = value.toString().trim()
        if (text.isEmpty() || text == "null") return null
        // Plain ISO date first, then a full timestamp, then an epoch value.
        runCatching { return LocalDate.parse(text.take(10)) }
        runCatching { return Instant.parse(text).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
        text.toLongOrNull()?.let { epoch ->
            return if (epoch > 100_000_000L) {
                Instant.ofEpochMilli(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            } else {
                LocalDate.ofEpochDay(epoch)
            }
        }
        return null
    }
}
