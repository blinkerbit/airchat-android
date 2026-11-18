package com.aird.core.transfer

import kotlin.random.Random

data class ByteSlice(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
) {
    val length: Long get() = endInclusive - start + 1
    val contentRange: String get() = "bytes $start-$endInclusive/$total"
}

object RangeUpload {
    const val SMALL_FILE_BYTES = 100L * 1024 * 1024
    const val CHUNK_BYTES = 90L * 1024 * 1024
    const val MAX_PARALLEL = 4

    fun chunks(total: Long, chunkBytes: Long = CHUNK_BYTES): List<ByteSlice> {
        if (total <= 0) return emptyList()
        val size = chunkBytes.coerceAtLeast(1)
        val out = ArrayList<ByteSlice>()
        var start = 0L
        while (start < total) {
            val end = minOf(start + size, total) - 1
            out += ByteSlice(start, end, total)
            start = end + 1
        }
        return out
    }

    /** Keep a few slices in flight without holding several 90 MB buffers. */
    fun parallelism(chunkBytes: Long): Int {
        if (chunkBytes <= 0) return 1
        val budget = 48L * 1024 * 1024
        return (budget / chunkBytes).toInt().coerceIn(1, MAX_PARALLEL)
    }

    fun simulatedDigest(start: Long, endInclusive: Long): Long {
        var hash = start xor (endInclusive * 0x9E3779B97F4A7C15uL.toLong())
        val length = endInclusive - start + 1
        var step = 0
        while (step < 32) {
            val pos = start + (length - 1) * step / 31
            val byte = (pos * 1315423911L).ushr(56)
            hash = hash xor (byte + step * 0x100000001b3L)
            hash = (hash shl 7) or hash.ushr(57)
            step++
        }
        return hash xor length
    }

    /**
     * Simulated ranged PUT with independent packet loss. Each slice is retried
     * until the server stores the same digest the client computed.
     */
    fun simulate(
        total: Long,
        chunkBytes: Long = CHUNK_BYTES,
        lossRate: Double = 0.0,
        random: Random = Random(1),
        maxAttempts: Int = 32,
    ): Simulation {
        val planned = chunks(total, chunkBytes)
        val stored = LinkedHashMap<Long, Long>()
        var attempts = 0
        planned.forEach { slice ->
            val digest = simulatedDigest(slice.start, slice.endInclusive)
            var placed = false
            var tryNo = 0
            while (!placed) {
                tryNo++
                attempts++
                if (tryNo > maxAttempts) error("slice ${slice.start} did not land")
                val lost = lossRate > 0 && random.nextDouble() < lossRate
                if (!lost) {
                    stored[slice.start] = digest
                    placed = true
                }
            }
        }
        return Simulation(planned, stored, attempts)
    }

    data class Simulation(
        val slices: List<ByteSlice>,
        val digests: Map<Long, Long>,
        val attempts: Int,
    ) {
        fun covered(total: Long): Boolean {
            var cursor = 0L
            slices.sortedBy { it.start }.forEach { slice ->
                if (slice.start != cursor) return false
                cursor = slice.endInclusive + 1
            }
            return cursor == total && digests.size == slices.size
        }

        fun hashesMatch(): Boolean = slices.all { slice ->
            digests[slice.start] == simulatedDigest(slice.start, slice.endInclusive)
        }
    }
}
