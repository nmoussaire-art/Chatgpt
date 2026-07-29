package com.loopguard.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class BackupSerializerTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    /**
     * Exactly the shape produced by LoopGuard 1.0's "Backup" button
     * (localStorage key `loopguard_v1`, shared as text).
     */
    private val legacyBackup = """
        {
          "loops": [
            {
              "id": 1,
              "title": "Confirm Arabic stream with school",
              "person": "Rebecca / Admissions",
              "side": "them",
              "category": "Family",
              "due": "2026-03-12",
              "impact": 4,
              "last": "2026-03-06",
              "notes": "Need answer before completing FS1 admission form.",
              "history": [["Request sent", "2026-03-06"]]
            },
            {
              "id": 3,
              "title": "Submit insurance pre-approval documents",
              "person": "SKMC Pharmacy",
              "side": "me",
              "category": "Health",
              "due": "2026-03-11",
              "impact": 5,
              "last": "2026-03-09",
              "notes": "Avoid delay to next injection.",
              "history": [["Documents requested", "2026-03-09"]]
            }
          ],
          "done": [
            {
              "id": 9,
              "title": "Old finished thing",
              "person": "Someone",
              "side": "me",
              "category": "Admin",
              "due": "2026-02-01",
              "impact": 2,
              "last": "2026-02-02",
              "notes": "",
              "history": [["Closed", "2026-02-02"]],
              "closed": "2026-02-02"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `imports a LoopGuard 1_0 backup without losing anything`() {
        val payload = BackupSerializer.parse(legacyBackup)

        assertTrue("should be detected as legacy", payload.isLegacy)
        assertEquals(3, payload.loops.size)
        assertEquals(2, payload.openCount)
        assertEquals(1, payload.doneCount)

        val school = payload.loops.first { it.loop.title.contains("Arabic") }.loop
        assertEquals("Rebecca", school.counterparty)
        assertEquals("Admissions", school.organisation)
        assertEquals(Side.THEM, school.sideEnum)
        assertEquals(LocalDate.of(2026, 3, 12), school.due)
        assertEquals(4, school.impact)
        assertEquals(LocalDate.of(2026, 3, 6), school.lastActivityDate)
        assertEquals(LoopStatus.OPEN, school.statusEnum)
        assertEquals("1", school.legacyId)
        assertTrue(school.notes.startsWith("Need answer"))

        val events = payload.loops.first { it.loop.title.contains("Arabic") }.events
        assertEquals(1, events.size)
        assertEquals("Request sent", events.first().text)
        assertEquals(LocalDate.of(2026, 3, 6).toEpochDay(), events.first().date)

        val closed = payload.loops.first { it.loop.statusEnum == LoopStatus.DONE }.loop
        assertNotNull(closed.closedAt)
    }

    @Test
    fun `export then import is lossless`() {
        val original = Loop(
            id = 7,
            title = "Repainting estimate",
            counterparty = "Mégane",
            organisation = "Rentalys",
            side = Side.THEM.storageKey,
            category = Categories.PROPERTY,
            dueDate = today.plusDays(5).toEpochDay(),
            impact = 3,
            lastActivity = today.minusDays(7).toEpochDay(),
            notes = "Needed before the listing refresh.",
            reference = "Q-4471",
            tags = "condo,listing",
            followUpCount = 2,
            pinned = true,
            createdAt = 1_700_000_000_000L,
        )
        val events = listOf(
            LoopEvent(id = 1, loopId = 7, text = "Asked for estimate", date = today.minusDays(7).toEpochDay(), at = 1L, kind = EventKind.CREATED),
            LoopEvent(id = 2, loopId = 7, text = "Follow-up sent (Firm)", date = today.minusDays(2).toEpochDay(), at = 2L, kind = EventKind.FOLLOW_UP),
        )

        val json = BackupSerializer.export(listOf(original), mapOf(7L to events), "2.0.0")
        val restored = BackupSerializer.parse(json).loops.single()

        assertTrue(!BackupSerializer.parse(json).isLegacy)
        assertEquals(original.title, restored.loop.title)
        assertEquals(original.counterparty, restored.loop.counterparty)
        assertEquals(original.organisation, restored.loop.organisation)
        assertEquals(original.side, restored.loop.side)
        assertEquals(original.category, restored.loop.category)
        assertEquals(original.dueDate, restored.loop.dueDate)
        assertEquals(original.impact, restored.loop.impact)
        assertEquals(original.lastActivity, restored.loop.lastActivity)
        assertEquals(original.notes, restored.loop.notes)
        assertEquals(original.reference, restored.loop.reference)
        assertEquals(original.tags, restored.loop.tags)
        assertEquals(original.followUpCount, restored.loop.followUpCount)
        assertEquals(original.pinned, restored.loop.pinned)
        assertEquals(original.createdAt, restored.loop.createdAt)

        assertEquals(2, restored.events.size)
        assertEquals("Asked for estimate", restored.events.first().text)
    }

    @Test
    fun `export stays readable by LoopGuard 1_0`() {
        val loop = Loop(
            id = 5,
            title = "Lease renewal",
            counterparty = "Landlord",
            side = Side.THEM.storageKey,
            dueDate = today.toEpochDay(),
            lastActivity = today.toEpochDay(),
        )
        val json = JSONObject(BackupSerializer.export(listOf(loop), emptyMap(), "2.0.0"))

        // v1 read exactly these keys; they must all still be present.
        val entry = json.getJSONArray("loops").getJSONObject(0)
        listOf("id", "title", "person", "side", "category", "due", "impact", "last", "notes", "history")
            .forEach { key -> assertTrue("v1 key '$key' missing", entry.has(key)) }
        assertTrue(json.has("done"))
    }

    @Test
    fun `rejects nonsense with a readable message`() {
        listOf("", "not json at all", "{\"something\":1}").forEach { input ->
            try {
                BackupSerializer.parse(input)
                fail("should have rejected: $input")
            } catch (e: BackupFormatException) {
                assertTrue("message should be human readable", (e.message?.length ?: 0) > 10)
            }
        }
    }

    @Test
    fun `tolerates missing and malformed fields`() {
        val messy = """
            {"loops":[
              {"title":"Only a title"},
              {"title":"Bad date","due":"not-a-date","impact":99,"last":""},
              {"title":"String history","history":["did a thing"]},
              {"title":"Tags as string","tags":"a, b ,c"}
            ]}
        """.trimIndent()

        val payload = BackupSerializer.parse(messy)
        assertEquals(4, payload.loops.size)

        val bare = payload.loops[0].loop
        assertEquals("Only a title", bare.title)
        assertEquals(3, bare.impact)

        val bad = payload.loops[1].loop
        assertEquals(null, bad.dueDate)
        assertTrue("impact must be clamped", bad.impact in 1..5)

        assertEquals("did a thing", payload.loops[2].events.single().text)
        assertEquals("a,b,c", payload.loops[3].loop.tags)
    }

    @Test
    fun `parses timestamps and epoch values as dates`() {
        val mixed = """
            {"loops":[
              {"title":"ISO instant","due":"2026-04-01T10:15:30Z"},
              {"title":"Epoch millis","due":"1774000000000"}
            ]}
        """.trimIndent()
        val payload = BackupSerializer.parse(mixed)
        assertEquals(LocalDate.of(2026, 4, 1), payload.loops[0].loop.due)
        assertNotNull(payload.loops[1].loop.due)
    }
}
