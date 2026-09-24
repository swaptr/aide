package com.sabreware.aide.core.domain.label

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelsTest {

    private val gpt = LabelSubject.model("openai-test01:gpt-5")
    private val mini = LabelSubject.model("openai-test01:gpt-5-mini")
    private val conn = LabelSubject.connection("openai-test01")
    private val otherConn = LabelSubject.connection("openai-test012")
    private val otherModel = LabelSubject.model("openai-test012:gpt-5")
    private val local = LabelSubject.model("gemma-3n")

    @Test
    fun `a subject key splits on its first separator only`() {
        assertEquals(LabelSubject.MODEL, gpt.kind)
        assertEquals("openai-test01:gpt-5", gpt.id)
    }

    @Test
    fun `renaming back to the original name clears the alias`() {
        val renamed = Labels().rename(gpt, "Work GPT", originalName = "GPT-5")
        assertEquals("Work GPT", renamed.nameOf(gpt, "GPT-5"))

        val restored = renamed.rename(gpt, " GPT-5 ", originalName = "GPT-5")
        assertNull(restored[gpt].alias)
        assertTrue(gpt.key !in restored.bySubject, "an empty label is not stored")

        assertNull(renamed.rename(gpt, "   ")[gpt].alias, "a blank alias clears")
    }

    @Test
    fun `setTags creates missing vocabulary and reuses existing casing`() {
        val labels = Labels().createTag("Work").setTags(gpt, listOf("work", " fast ", "", "FAST"))

        assertEquals(listOf("Work", "fast"), labels.tags)
        assertEquals(listOf("Work", "fast"), labels[gpt].tags)
        assertEquals(mapOf("Work" to 1, "fast" to 1), labels.tagUsage)
    }

    @Test
    fun `renaming a tag onto an existing one merges them`() {
        val labels = Labels()
            .setTags(gpt, listOf("work", "fast"))
            .setTags(mini, listOf("job"))
            .renameTag("job", "Work")

        assertEquals(listOf("work", "fast"), labels.tags)
        assertEquals(listOf("work"), labels[mini].tags)
        assertEquals(setOf(gpt, mini), labels.tagged("WORK"))

        val plain = labels.renameTag("fast", "quick")
        assertEquals(listOf("work", "quick"), plain.tags)
        assertEquals(listOf("work", "quick"), plain[gpt].tags)
    }

    @Test
    fun `deleting a tag strips it from everything and drops labels left empty`() {
        val labels = Labels()
            .setTags(gpt, listOf("work"))
            .setTags(mini, listOf("work", "fast"))
            .deleteTag("WORK")

        assertEquals(listOf("fast"), labels.tags)
        assertTrue(gpt.key !in labels.bySubject)
        assertEquals(listOf("fast"), labels[mini].tags)
    }

    @Test
    fun `without ownedBy drops the connection and its models only`() {
        val labels = listOf(gpt, mini, conn, otherConn, otherModel, local)
            .fold(Labels()) { acc, s -> acc.rename(s, "name of ${s.key}") }
            .without(LabelSubject.ownedBy("openai-test01"))

        assertEquals(setOf(otherConn.key, otherModel.key, local.key), labels.bySubject.keys)
    }

    @Test
    fun `pinned lists oldest pin first and unpinning removes it`() {
        val labels = Labels()
            .setPinned(mini, true, now = 20)
            .setPinned(gpt, true, now = 10)
            .setPinned(conn, true, now = 30)

        assertEquals(listOf(gpt, mini, conn), labels.pinned)

        val repinned = labels.setPinned(gpt, true, now = 99)
        assertEquals(10, repinned[gpt].pinnedAt, "pinning twice keeps the original order")

        assertEquals(listOf(mini, conn), labels.setPinned(gpt, false, now = 40).pinned)
    }
}
