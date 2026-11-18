package com.aird.core.model

/**
 * Hybrid logical clock. Wire form is `physical_ms:counter` so the Tornado
 * parser (wall, logical) accepts it. The actor id is carried on the op.
 */
class HybridClock(
    private var wall: Long = 0,
    private var logical: Int = 0,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun tick(): String {
        val time = now()
        if (time > wall) {
            wall = time
            logical = 0
        } else {
            logical += 1
        }
        return format(wall, logical)
    }

    fun receive(remote: String): String {
        val (remoteWall, remoteLogical) = parse(remote)
        val time = now()
        if (time > wall && time > remoteWall) {
            wall = time
            logical = 0
        } else if (remoteWall > wall) {
            wall = remoteWall
            logical = remoteLogical + 1
        } else if (remoteWall == wall) {
            logical = maxOf(logical, remoteLogical) + 1
        } else {
            logical += 1
        }
        return format(wall, logical)
    }

    companion object {
        fun format(wall: Long, logical: Int): String = "%013d:%06d".format(wall, logical)

        fun parse(value: String): Pair<Long, Int> {
            val raw = value.trim()
            val cut = raw.indexOf(':')
            if (cut <= 0) return 0L to 0
            val wall = raw.substring(0, cut).toLongOrNull() ?: return 0L to 0
            val logical = raw.substring(cut + 1).substringBefore(':').toIntOrNull() ?: return 0L to 0
            return wall to logical
        }

        fun compare(a: String, b: String): Int {
            val (aw, al) = parse(a)
            val (bw, bl) = parse(b)
            if (aw != bw) return if (aw < bw) -1 else 1
            if (al != bl) return if (al < bl) -1 else 1
            return 0
        }
    }
}
