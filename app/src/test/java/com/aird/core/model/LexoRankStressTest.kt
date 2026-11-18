package com.aird.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LexoRankStressTest {
    @Test
    fun tenThousandInsertsAtTheStartStaySorted() {
        val keys = ArrayList<String>(10_000)
        repeat(10_000) {
            keys.add(0, FractionalIndex.between(null, keys.firstOrNull()))
        }
        assertEquals(10_000, keys.toSet().size)
        for (index in 1 until keys.size) {
            assertTrue(keys[index - 1] < keys[index])
        }
        assertEquals(keys.sorted(), keys)
    }
}
