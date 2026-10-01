package com.feiyu.notes.ui

import com.feiyu.notes.ai.AiDefaults
import com.feiyu.notes.ai.AiModel
import com.feiyu.notes.data.Entry
import com.feiyu.notes.data.EntryAction
import com.feiyu.notes.data.EntryKind
import com.feiyu.notes.data.EntryState
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadNavigationTest {
    private fun user(id: Long, parent: Long? = null) = Entry(id, 1, EntryKind.USER, EntryAction.ASK, "q$id", parentEntryId = parent)
    private fun answer(id: Long, parent: Long, state: EntryState = EntryState.COMPLETE) =
        Entry(id, 1, EntryKind.ASSISTANT, text = "a$id", parentEntryId = parent, state = state)

    @Test fun questionsAndRepliesShareDisplayNumbers() {
        // Ids are interleaved: 10 asks, 11 failed, 12 retry, 13 follow-up, 14 answers it, 20 is a new thread.
        val entries = listOf(user(10), answer(11, 10, EntryState.FAILED), answer(12, 10), user(13, parent = 12), answer(14, 13), user(20), answer(21, 20))
        val n = qaNumbers(entries)
        assertEquals(listOf(1, 1, 1, 2, 2, 3, 3), entries.map { n[it.id] })
    }

    @Test fun longChainKeepsDepthFirstOrderWithoutRecursion() {
        val chain = buildList {
            add(user(1)); add(answer(2, 1))
            for (id in 3L until 20_000L step 2) { add(user(id, parent = id - 1)); add(answer(id + 1, id)) }
        }
        val rows = threadRows(chain + user(50_000), archived = false)
        assertEquals(chain.map { it.id } + 50_000L, rows.map { it.first.id })
        assertEquals(chain.size - 1, rows[chain.size - 1].second)
    }

    @Test fun effortTiersFollowProviderOrDefaultToOfficialThree() {
        assertEquals(listOf("low", "high", "max"), effortLevels(null))
        assertEquals("low", AiDefaults.EFFORT)
        assertEquals(listOf("high", "max"), effortLevels(AiModel("future", efforts = listOf("high", "max"))))
    }
}
