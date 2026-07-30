package com.loopguard.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Walks the flows a user actually performs, against a real Room database.
 *
 * This is the closest thing to a device test available in this build
 * environment, and it covers every flow the brief asked to be verified:
 * create, edit, change responsibility, follow up, complete, restore, reopen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class UserFlowTest {

    private lateinit var db: LoopDatabase
    private lateinit var repository: LoopRepository

    private val today: LocalDate = LocalDate.of(2026, 3, 10)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LoopDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = LoopRepository(db.loopDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newLoop(
        title: String = "Confirm the Arabic stream place",
        side: Side = Side.THEM,
    ) = Loop(
        title = title,
        counterparty = "Rebecca",
        organisation = "Admissions office",
        side = side.storageKey,
        category = Categories.SCHOOL,
        dueDate = today.plusDays(2).toEpochDay(),
        impact = 4,
        lastActivity = today.toEpochDay(),
        notes = "Needed before the enrolment form.",
        reference = "ADM-2291",
    )

    @Test
    fun `create a loop and it appears with its first timeline entry`() = runTest {
        val id = repository.create(newLoop(), today)

        val stored = repository.find(id)
        assertNotNull(stored)
        assertEquals("Confirm the Arabic stream place", stored!!.title)
        assertEquals(LoopStatus.OPEN, stored.statusEnum)

        val events = repository.eventsFor(id)
        assertEquals(1, events.size)
        assertEquals(EventKind.CREATED, events.single().kind)
    }

    @Test
    fun `edit a loop and the changes persist`() = runTest {
        val id = repository.create(newLoop(), today)
        val stored = repository.find(id)!!

        repository.update(
            stored.copy(
                title = "Confirm the Arabic stream place for September",
                impact = 5,
                dueDate = today.plusDays(9).toEpochDay(),
                tags = "school,september",
            )
        )

        val updated = repository.find(id)!!
        assertEquals("Confirm the Arabic stream place for September", updated.title)
        assertEquals(5, updated.impact)
        assertEquals(today.plusDays(9), updated.due)
        assertEquals(listOf("school", "september"), updated.tagList)
    }

    @Test
    fun `change who is responsible and it is recorded on the timeline`() = runTest {
        val id = repository.create(newLoop(side = Side.THEM), today)

        repository.setSide(id, Side.ME, today)
        assertEquals(Side.ME, repository.find(id)!!.sideEnum)

        repository.setSide(id, Side.THEM, today)
        assertEquals(Side.THEM, repository.find(id)!!.sideEnum)

        val handovers = repository.eventsFor(id).filter { it.kind == EventKind.HANDOVER }
        assertEquals(2, handovers.size)
    }

    @Test
    fun `logging an action resets the silence clock and can hand the loop over`() = runTest {
        val stale = newLoop(side = Side.ME).copy(lastActivity = today.minusDays(20).toEpochDay())
        val id = repository.create(stale, today)

        repository.logAction(id, "Uploaded the documents", handOver = true, today = today)

        val updated = repository.find(id)!!
        assertEquals(today.toEpochDay(), updated.lastActivity)
        assertEquals(Side.THEM, updated.sideEnum)
        assertTrue(repository.eventsFor(id).any { it.text == "Uploaded the documents" })
    }

    @Test
    fun `following up increments the counter and resets silence`() = runTest {
        val quiet = newLoop().copy(lastActivity = today.minusDays(14).toEpochDay())
        val id = repository.create(quiet, today)

        repository.recordFollowUp(id, "Firm", today)
        repository.recordFollowUp(id, "Final notice", today)

        val updated = repository.find(id)!!
        assertEquals(2, updated.followUpCount)
        assertEquals(today.toEpochDay(), updated.lastActivity)
        assertEquals(2, repository.eventsFor(id).count { it.kind == EventKind.FOLLOW_UP })
    }

    @Test
    fun `snooze hides the loop then wakes it`() = runTest {
        val id = repository.create(newLoop(), today)

        repository.snooze(id, today.plusDays(3), today)
        val snoozed = repository.find(id)!!
        assertTrue(snoozed.isSnoozed(today))
        assertTrue(!snoozed.isSnoozed(today.plusDays(4)))

        repository.unsnooze(id, today)
        assertNull(repository.find(id)!!.snoozedUntil)
    }

    @Test
    fun `complete then reopen a loop`() = runTest {
        val id = repository.create(newLoop(), today)

        repository.complete(id, today = today)
        val done = repository.find(id)!!
        assertEquals(LoopStatus.DONE, done.statusEnum)
        assertNotNull(done.closedAt)
        assertEquals(0, repository.openLoops().size)

        repository.reopen(id, today)
        val reopened = repository.find(id)!!
        assertEquals(LoopStatus.OPEN, reopened.statusEnum)
        assertNull(reopened.closedAt)
        assertEquals(1, repository.openLoops().size)
    }

    @Test
    fun `deleting a loop removes its timeline, and restore brings both back`() = runTest {
        val id = repository.create(newLoop(), today)
        repository.addEvent(id, "Called the office", EventKind.ACTION, today)

        val loop = repository.find(id)!!
        val events = repository.eventsFor(id)
        assertEquals(2, events.size)

        repository.delete(loop)
        assertNull(repository.find(id))
        assertEquals(0, db.loopDao().allEvents().size)

        repository.restore(loop, events)
        val all = db.loopDao().allLoops()
        assertEquals(1, all.size)
        assertEquals(2, repository.eventsFor(all.single().id).size)
    }

    @Test
    fun `import a LoopGuard 1_0 backup end to end`() = runTest {
        val legacy = """
            {"loops":[
              {"id":1,"title":"Confirm Arabic stream with school","person":"Rebecca / Admissions",
               "side":"them","category":"Family","due":"2026-03-12","impact":4,"last":"2026-03-06",
               "notes":"Need answer.","history":[["Request sent","2026-03-06"]]}
            ],"done":[
              {"id":9,"title":"Finished thing","person":"Someone","side":"me","category":"Admin",
               "due":"2026-02-01","impact":2,"last":"2026-02-02","notes":"","history":[],"closed":"2026-02-02"}
            ]}
        """.trimIndent()

        val result = repository.import(BackupSerializer.parse(legacy), ImportMode.MERGE)

        assertEquals(2, result.added)
        assertTrue(result.legacy)
        assertEquals(1, repository.openLoops().size)

        val imported = db.loopDao().allLoops().first { it.title.contains("Arabic") }
        assertEquals("Rebecca", imported.counterparty)
        assertEquals(1, repository.eventsFor(imported.id).size)
    }

    @Test
    fun `merge import does not duplicate loops that were already imported`() = runTest {
        val legacy = """
            {"loops":[{"id":1,"title":"A loop","person":"X","side":"them","due":"2026-03-12",
             "impact":3,"last":"2026-03-06","notes":"","history":[]}]}
        """.trimIndent()

        repository.import(BackupSerializer.parse(legacy), ImportMode.MERGE)
        val second = repository.import(BackupSerializer.parse(legacy), ImportMode.MERGE)

        assertEquals(0, second.added)
        assertEquals(1, second.skipped)
        assertEquals(1, db.loopDao().allLoops().size)
    }

    @Test
    fun `replace import wipes first`() = runTest {
        repository.create(newLoop("Something already here"), today)

        val backup = """
            {"schema":2,"loops":[{"id":"99","title":"Only this should survive","person":"Y",
             "side":"me","due":"2026-03-12","impact":3,"last":"2026-03-06","notes":"","history":[]}]}
        """.trimIndent()

        val result = repository.import(BackupSerializer.parse(backup), ImportMode.REPLACE)

        assertTrue(result.replaced)
        val all = db.loopDao().allLoops()
        assertEquals(1, all.size)
        assertEquals("Only this should survive", all.single().title)
    }

    @Test
    fun `data survives closing and reopening the database`() = runTest {
        // A file-backed database, so this is a genuine reopen rather than a
        // handle that never left memory.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("flow-test.db")

        var disk = Room.databaseBuilder(context, LoopDatabase::class.java, "flow-test.db")
            .allowMainThreadQueries().build()
        var repo = LoopRepository(disk.loopDao())

        val id = repo.create(newLoop("Survives a restart"), today)
        repo.recordFollowUp(id, "Professional", today)
        disk.close()

        disk = Room.databaseBuilder(context, LoopDatabase::class.java, "flow-test.db")
            .allowMainThreadQueries().build()
        repo = LoopRepository(disk.loopDao())

        val reloaded = disk.loopDao().allLoops()
        assertEquals(1, reloaded.size)
        assertEquals("Survives a restart", reloaded.single().title)
        assertEquals(1, reloaded.single().followUpCount)
        assertEquals(2, repo.eventsFor(reloaded.single().id).size)

        disk.close()
        context.deleteDatabase("flow-test.db")
    }

    @Test
    fun `observing loops emits the current state`() = runTest {
        repository.create(newLoop("First"), today)
        repository.create(newLoop("Second"), today)

        val observed = repository.observeAll().first()
        assertEquals(2, observed.size)
    }

    @Test
    fun `starter data seeds only once`() = runTest {
        repository.seedStarterLoops(today)
        val afterFirst = db.loopDao().count()
        assertTrue(afterFirst > 0)

        repository.seedStarterLoops(today)
        assertEquals(afterFirst, db.loopDao().count())
    }
}
