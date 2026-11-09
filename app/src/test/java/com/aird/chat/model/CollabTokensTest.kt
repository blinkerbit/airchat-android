package com.aird.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollabTokensTest {
    @Test
    fun mentionsUseTheWebSigils() {
        val poll = collabToken("poll", "grocery-nov1")
        val found = collabMentions("bring $poll and #list:packing-nov2 //meet:standup-nov8")
        assertEquals(listOf("poll", "list", "meet"), found.map { it.kind })
        assertEquals(listOf("grocery-nov1", "packing-nov2", "standup-nov8"), found.map { it.slug })
    }

    @Test
    fun shortHashesAndLinksStayText() {
        assertEquals(emptyList<CollabMention>(), collabMentions("see #1 and https://team-sync later"))
        assertEquals("see #1 and https://team-sync later", textWithoutCollab("see #1 and https://team-sync later"))
    }

    @Test
    fun explicitShortNamesStillCount() {
        val dollar = '$'
        val found = collabMentions("${dollar}${dollar}poll:dinner")
        assertEquals("poll", found.single().kind)
        assertEquals("dinner", found.single().slug)
        assertEquals("", textWithoutCollab("${dollar}${dollar}poll:dinner"))
    }

    @Test
    fun tokensBecomePreviewLabels() {
        val poll = collabToken("poll", "Grocery-Nov1")
        assertEquals(2, poll.takeWhile { it == '$' }.length)
        assertTrue(poll.endsWith("grocery-nov1"))
        assertEquals("Poll · grocery-nov1", previewText(poll))
        assertEquals("please vote", previewText("please vote $poll"))
    }
}
