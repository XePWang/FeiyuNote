package com.feiyu.notes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P2 checks on a real device/emulator SQLite, using synthetic data in a throwaway DB. */
@RunWith(AndroidJUnit4::class)
class NotebookStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "store-test.db"
    private lateinit var filesDir: File
    private lateinit var database: NotebookDatabase
    private lateinit var photos: PhotoFiles
    private lateinit var store: NotebookStore

    @Before fun setUp() {
        context.deleteDatabase(dbName)
        filesDir = File(context.cacheDir, "store-test").apply { deleteRecursively(); mkdirs() }
        open()
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(dbName)
        filesDir.deleteRecursively()
    }

    private fun open() {
        database = NotebookDatabase(context, dbName)
        photos = PhotoFiles(filesDir)
        store = NotebookStore(database, photos)
    }

    private fun reopen() { database.close(); open() }

    private fun photo(notebookId: Long): String =
        photos.allocatePhoto(notebookId).apply { writeText("jpeg") }.name

    private suspend fun ask(lessonId: Long, text: String, parent: Long? = null, image: String? = null, ref: Long? = null) =
        store.insertQuestion(
            Entry(0, lessonId, EntryKind.USER, EntryAction.ASK, text, parentEntryId = parent, imagePath = image,
                sourceEntryIds = listOfNotNull(ref))
        )!!

    @Test fun notebooksAreIsolatedAndRenameKeepsPhotos() = runBlocking {
        val a = store.createNotebook(NotebookKind.COURSE, "数学")!!
        val b = store.createNotebook(NotebookKind.COURSE, "物理")!!
        val la = store.createLesson(a.id, "2026-09-30")!!
        val lb = store.createLesson(b.id, "2026-09-30")!!
        val img = photo(a.id)
        val (q, _) = ask(la.id, "题目", image = img)
        ask(lb.id, "另一门")

        assertTrue(store.updateNotebook(a.copy(name = "高等数学")))
        reopen()

        assertEquals(listOf(la), store.listLessons(a.id))
        val entries = store.readEntries(la.id)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.lessonId == la.id })
        assertEquals(img, entries.first { it.id == q.id }.imagePath)
        assertTrue(photos.isUsable(a.id, img))
        assertEquals("高等数学", store.getNotebook(a.id)!!.name)
    }

    @Test fun parentAndSourceIdsRoundTrip() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        val (root, reply) = ask(l.id, "q1")
        store.commitReply(reply.id, "a1", EntryState.COMPLETE)
        val note = store.commitSummary(l.id, "note", listOf(root.id, reply.id), null)!!
        val (child, _) = ask(l.id, "q2", parent = reply.id, ref = note.id)
        reopen()
        val byId = store.readEntries(l.id).associateBy { it.id }
        assertEquals(reply.id, byId.getValue(child.id).parentEntryId)
        assertEquals(listOf(note.id), byId.getValue(child.id).sourceEntryIds)
        assertEquals(listOf(root.id, reply.id), byId.getValue(note.id).sourceEntryIds)
    }

    @Test fun failedTransactionLeavesDataReadable() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        ask(l.id, "kept")
        val before = store.readEntries(l.id)
        // A missing parent is rejected inside the transaction; nothing partial is written.
        assertNull(store.insertQuestion(Entry(0, l.id, EntryKind.USER, EntryAction.ASK, "x", parentEntryId = -1)))
        // A constraint violation aborts the whole transaction.
        assertTrue(runCatching { store.commitReply(-1, "x", EntryState.PENDING) }.isFailure)
        assertEquals(before, store.readEntries(l.id))
    }

    @Test fun pendingBecomesInterruptedCompleteUnchanged() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        val (_, pending) = ask(l.id, "p")
        val (_, done) = ask(l.id, "d")
        store.commitReply(done.id, "ok", EntryState.COMPLETE)
        reopen()
        assertEquals(1, store.markPendingInterrupted())
        val byId = store.readEntries(l.id).associateBy { it.id }
        assertEquals(EntryState.INTERRUPTED, byId.getValue(pending.id).state)
        assertEquals(EntryState.COMPLETE, byId.getValue(done.id).state)
    }

    @Test fun commitRechecksTarget() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        val (root, reply) = ask(l.id, "q")
        assertTrue(store.deleteThread(root.id))
        assertFalse(store.commitReply(reply.id, "late", EntryState.COMPLETE))
        assertTrue(store.readEntries(l.id, includeArchived = true).isEmpty())

        assertTrue(store.deleteLesson(l.id))
        assertNull(store.commitSummary(l.id, "late note", emptyList(), null))
    }

    @Test fun archivedThreadsHiddenByDefault() = runBlocking {
        val n = store.createNotebook(NotebookKind.COURSE, "c")!!
        val l = store.createLesson(n.id, "l")!!
        val (root, reply) = ask(l.id, "q")
        ask(l.id, "follow", parent = reply.id)
        val (other, _) = ask(l.id, "other")
        assertFalse(store.setArchived(reply.id, true))
        assertTrue(store.setArchived(root.id, true))
        assertEquals(listOf(other.id), store.readEntries(l.id).filter { it.isRoot }.map { it.id })
        assertEquals(2, store.readEntries(l.id).size)
        // Each question also stores its pending reply: 3 questions -> 6 entries.
        assertEquals(6, store.readEntries(l.id, includeArchived = true).size)
        assertTrue(store.setArchived(root.id, false))
        assertEquals(6, store.readEntries(l.id).size)
    }

    @Test fun masteryOnlyOnPracticeRoots() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "c")!!
        val practice = store.createNotebook(NotebookKind.PRACTICE, "p")!!
        val cl = store.createLesson(course.id, "l")!!
        val pl = store.createLesson(practice.id, "试卷一")!!
        val (courseRoot, _) = ask(cl.id, "q")
        val (problem, reply) = ask(pl.id, "题")
        val (child, _) = ask(pl.id, "我的解答", parent = reply.id)

        assertNull(courseRoot.mastery)
        assertEquals(Mastery.UNMASTERED, problem.mastery)
        assertFalse(store.setMastery(courseRoot.id, Mastery.MASTERED))
        assertFalse(store.setMastery(child.id, Mastery.MASTERED))
        assertNull(store.getEntry(courseRoot.id)!!.mastery)
        assertNull(store.getEntry(child.id)!!.mastery)
        assertTrue(store.setMastery(problem.id, Mastery.MASTERED))
        assertEquals(Mastery.MASTERED, store.getEntry(problem.id)!!.mastery)
    }

    @Test fun referenceNotesFollowCourseLink() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "c")!!
        val other = store.createNotebook(NotebookKind.COURSE, "o")!!
        val practice = store.createNotebook(NotebookKind.PRACTICE, "p")!!
        val courseNote = store.commitSummary(store.createLesson(course.id, "l")!!.id, "cn", emptyList(), null)!!
        store.commitSummary(store.createLesson(other.id, "l")!!.id, "on", emptyList(), null)!!
        val ownNote = store.commitSummary(store.createLesson(practice.id, "l")!!.id, "pn", emptyList(), null)!!

        assertEquals(setOf(ownNote.id), store.readReferenceNotes(practice.id).map { it.id }.toSet())
        assertTrue(store.updateNotebook(practice.copy(linkedCourseId = course.id)))
        assertEquals(setOf(ownNote.id, courseNote.id), store.readReferenceNotes(practice.id).map { it.id }.toSet())
        // Courses cannot link; practice books cannot link to practice books.
        assertFalse(store.updateNotebook(course.copy(linkedCourseId = other.id)))
        assertFalse(store.updateNotebook(practice.copy(linkedCourseId = practice.id)))
    }

    @Test fun deletesCascadeAndCleanPhotos() = runBlocking {
        val course = store.createNotebook(NotebookKind.COURSE, "c")!!
        val practice = store.createNotebook(NotebookKind.PRACTICE, "p", linkedCourseId = course.id)!!
        val l1 = store.createLesson(course.id, "l1")!!
        val l2 = store.createLesson(course.id, "l2")!!
        val rootImg = photo(course.id)
        val childImg = photo(course.id)
        val keepImg = photo(course.id)
        val (root, reply) = ask(l1.id, "q", image = rootImg)
        ask(l1.id, "child", parent = reply.id, image = childImg)
        val (keep, keepReply) = ask(l1.id, "keep", image = keepImg)
        val note = store.commitSummary(l1.id, "n", listOf(root.id, reply.id), null)!!

        assertTrue(store.deleteThread(root.id))
        assertFalse(photos.isUsable(course.id, rootImg))
        assertFalse(photos.isUsable(course.id, childImg))
        assertTrue(photos.isUsable(course.id, keepImg))
        val remaining = store.readEntries(l1.id).map { it.id }.toSet()
        assertEquals(setOf(keep.id, keepReply.id, note.id), remaining)
        // Note text survives; its sources now dangle and are shown as deleted.
        assertEquals("n", store.getEntry(note.id)!!.text)
        assertTrue(store.getEntries(store.getEntry(note.id)!!.sourceEntryIds).isEmpty())

        assertTrue(store.deleteLesson(l1.id))
        assertNull(store.getEntry(note.id))
        assertFalse(photos.isUsable(course.id, keepImg))

        val template = store.saveTemplate(Template(0, "严格", "讲证明要严格", 0))!!
        assertTrue(store.updateNotebook(practice.copy(defaultTemplateId = template.id)))
        assertTrue(store.deleteTemplate(template.id))
        assertNull(store.getNotebook(practice.id)!!.defaultTemplateId)

        photo(course.id)
        assertTrue(store.deleteNotebook(course.id))
        assertTrue(store.listLessons(course.id).isEmpty())
        assertNull(store.getLesson(l2.id))
        assertFalse(photos.notebookDir(course.id).exists())
        assertNull(store.getNotebook(practice.id)!!.linkedCourseId)
    }

    @Test fun changesIncrementAndPhotoNamesAreConfined() = runBlocking {
        val before = store.changes.value
        val n = store.createNotebook(NotebookKind.COURSE, "c")
        assertNotNull(n)
        assertTrue(store.changes.value > before)
        assertTrue(runCatching { photos.resolvePhoto(n!!.id, "../notes.db") }.isFailure)
        assertTrue(runCatching { photos.resolvePhoto(n!!.id, "a/b.jpg") }.isFailure)
        assertFalse(photos.isUsable(n!!.id, "..\\x.jpg"))
    }
}
