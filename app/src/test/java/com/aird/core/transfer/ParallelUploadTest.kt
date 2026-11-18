package com.aird.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ParallelUploadTest {
    @Test
    fun oneGibibyteSurvivesFivePercentLoss() {
        val total = 1L shl 30
        val slices = RangeUpload.chunks(total)
        assertEquals(total, slices.sumOf { it.length })
        assertEquals(RangeUpload.CHUNK_BYTES, slices.first().length)
        assertEquals("bytes 0-${RangeUpload.CHUNK_BYTES - 1}/$total", slices.first().contentRange)
        assertTrue(slices.size > 4)

        val simulation = RangeUpload.simulate(total, RangeUpload.CHUNK_BYTES, 0.05, Random(11))
        assertTrue(simulation.covered(total))
        assertTrue(simulation.hashesMatch())
        assertEquals(4, RangeUpload.parallelism(8L * 1024 * 1024))
        assertEquals(1, RangeUpload.parallelism(RangeUpload.CHUNK_BYTES))
    }
}
