package com.aird.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlockTreeInvariantTest {
    @Test
    fun depthHoldsAcrossRandomMovesAndCyclesAbort() {
        var previous: String? = null
        var blocks = List(24) { index ->
            val key = FractionalIndex.between(previous, null)
            previous = key
            BlockNode(id = "b$index", depth = 0, orderKey = key, content = "block $index")
        }
        val random = Random(2026)
        repeat(1_000) {
            val next = Outline.applyRandom(blocks, random.nextInt(), random.nextInt(), random.nextInt())
            assertEquals(blocks.map { it.id to it.content }.toSet(), next.map { it.id to it.content }.toSet())
            assertTrue(Outline.depthInvariant(next))
            blocks = next
        }

        val parent = BlockNode("parent", 0, "a0", "parent")
        val child = BlockNode("child", 1, "a1", "child")
        val tree = listOf(parent, child)
        assertTrue(Outline.createsCycle(tree, "parent", "child"))
        val aborted = Outline.move(tree, "parent", "child")
        assertEquals(0, aborted.first { it.id == "parent" }.depth)
        assertEquals("parent", aborted.first { it.id == "parent" }.content)
        assertEquals("child", aborted.first { it.id == "child" }.content)
        assertTrue(Outline.depthInvariant(aborted))
        assertFalse(Outline.createsCycle(aborted, "parent", null))
    }

    @Test
    fun clockMatchesServerOrder() {
        var time = 1_774_000_008_500L
        val clock = HybridClock(now = { time })
        val first = clock.tick()
        assertEquals("1774000008500:000000", first)
        val second = clock.tick()
        assertEquals("1774000008500:000001", second)
        time = 1_774_000_008_400L
        val received = clock.receive("1774000008600:000004")
        assertTrue(HybridClock.compare(received, second) > 0)
        assertTrue(HybridClock.compare(second, first) > 0)
    }
}
