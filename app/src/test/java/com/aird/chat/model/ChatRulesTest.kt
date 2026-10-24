package com.aird.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChatRulesTest {
    private val me = "ada"
    private val seenAt = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun editStaysOpenUntilTheMessageIsSeen() {
        assertTrue(editAllowed(text(), emptyList(), me, seenAt))
        assertTrue(editAllowed(text(), listOf(receipt(me, seenAt.minusSeconds(120))), me, seenAt))
        assertTrue(editAllowed(text(e2e = true), listOf(receipt("bea", seenAt, decrypted = false)), me, seenAt.plusSeconds(60)))
    }

    @Test
    fun editClosesThirtySecondsAfterSeen() {
        val receipt = receipt("bea", seenAt)
        assertTrue(editAllowed(text(), listOf(receipt), me, seenAt.plusMillis(29_999)))
        assertFalse(editAllowed(text(), listOf(receipt), me, seenAt.plusMillis(30_000)))
        val earlier = receipt("bea", seenAt.minusSeconds(40))
        val later = receipt("cy", seenAt.minusSeconds(5))
        assertFalse(editAllowed(text(), listOf(later, earlier), me, seenAt))
    }

    @Test
    fun editIsOnlyForOwnPlainText() {
        val now = seenAt.plusSeconds(1)
        assertFalse(editAllowed(text(mine = false), emptyList(), me, now))
        assertFalse(editAllowed(text(pending = true), emptyList(), me, now))
        assertFalse(editAllowed(text(failed = true), emptyList(), me, now))
        assertFalse(editAllowed(text(body = "  "), emptyList(), me, now))
        assertFalse(editAllowed(text(decryptOk = false), emptyList(), me, now))
        assertFalse(editAllowed(text(attachments = listOf(file("notes.txt", "file"))), emptyList(), me, now))
        assertFalse(editAllowed(text(attachments = listOf(file("pics", "folder"))), emptyList(), me, now))
        assertFalse(editAllowed(text(attachments = listOf(file("wave.gif", "gif"))), emptyList(), me, now))
    }

    @Test
    fun plainTextSeenByTimeStillUsesThatReadTime() {
        val sent = seenAt.minusSeconds(20)
        val message = text(createdAt = sent.toString(), e2e = false)
        val receipt = ReadReceipt("bea", "other-id", seenAt.toString(), true)
        assertTrue(editAllowed(message, listOf(receipt), me, seenAt.plusSeconds(10)))
        assertFalse(editAllowed(message, listOf(receipt), me, seenAt.plusSeconds(31)))
    }

    @Test
    fun conversationFilterUsesNamePeerTitleAndPreview() {
        val items = listOf(
            conv("1", title = "Team", peer = "Ada", preview = "hello"),
            conv("2", title = "Notes", peer = "", preview = "budget"),
            conv("3", title = "Ops", peer = "Bea", preview = "ship it"),
        )
        assertEquals(listOf("1", "2", "3"), filterConversations(items, "  ").map { it.id })
        assertEquals(listOf("1"), filterConversations(items, "ada").map { it.id })
        assertEquals(listOf("1"), filterConversations(items, "TEAM").map { it.id })
        assertEquals(listOf("2"), filterConversations(items, "budg").map { it.id })
        assertEquals(listOf("3"), filterConversations(items, "ship").map { it.id })
        assertTrue(filterConversations(items, "missing").isEmpty())
    }

    private fun text(
        mine: Boolean = true,
        pending: Boolean = false,
        failed: Boolean = false,
        body: String = "hello",
        decryptOk: Boolean = true,
        e2e: Boolean = true,
        createdAt: String = seenAt.minusSeconds(60).toString(),
        attachments: List<ChatAttachment> = emptyList(),
    ) = ChatMessage(
        id = "m1",
        conversationId = "c1",
        sender = if (mine) me else "bea",
        body = body,
        createdAt = createdAt,
        mine = mine,
        pending = pending,
        failed = failed,
        e2e = e2e,
        decryptOk = decryptOk,
        attachments = attachments,
    )

    private fun receipt(username: String, at: Instant, decrypted: Boolean = true) =
        ReadReceipt(username, "m1", at.toString(), decrypted)

    private fun file(name: String, kind: String) = ChatAttachment(name, "/f", kind, 0)

    private fun conv(id: String, title: String, peer: String, preview: String) =
        Conversation(id, title, peer, preview, 0, "")
}
