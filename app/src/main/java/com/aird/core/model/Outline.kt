package com.aird.core.model

data class BlockNode(
    val id: String,
    val depth: Int,
    val orderKey: String,
    val content: String = "",
)

object Outline {
    const val MAX_DEPTH = 5

    fun parentId(blocks: List<BlockNode>, id: String): String? {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val depth = blocks[index].depth
        if (depth <= 0) return null
        for (i in index - 1 downTo 0) {
            val row = blocks[i]
            if (row.depth == depth - 1) return row.id
            if (row.depth < depth - 1) return null
        }
        return null
    }

    fun createsCycle(blocks: List<BlockNode>, targetId: String, newParentId: String?): Boolean {
        if (newParentId == null) return false
        if (targetId == newParentId) return true
        var current: String? = newParentId
        val seen = HashSet<String>()
        while (current != null) {
            if (current == targetId) return true
            if (!seen.add(current)) return true
            current = parentId(blocks, current)
        }
        return false
    }

    fun depthInvariant(blocks: List<BlockNode>): Boolean {
        if (blocks.isEmpty()) return true
        if (blocks[0].depth != 0) return false
        for (i in blocks.indices) {
            val depth = blocks[i].depth
            if (depth !in 0..MAX_DEPTH) return false
            if (i > 0 && depth > blocks[i - 1].depth + 1) return false
        }
        return true
    }

    fun indentDepth(depths: List<Int>, index: Int): Int? {
        if (index <= 0 || index >= depths.size) return null
        val next = depths[index] + 1
        if (next > MAX_DEPTH) return null
        if (next > depths[index - 1] + 1) return null
        return next
    }

    fun outdentDepth(depths: List<Int>, index: Int): Int? {
        if (index !in depths.indices) return null
        if (depths[index] <= 0) return null
        return depths[index] - 1
    }

    /** Structural move. A cycle aborts to root depth and reclamps the outline. */
    fun move(blocks: List<BlockNode>, targetId: String, newParentId: String?): List<BlockNode> {
        if (blocks.none { it.id == targetId }) return blocks
        if (createsCycle(blocks, targetId, newParentId)) {
            return clamp(blocks.map { if (it.id == targetId) it.copy(depth = 0) else it })
        }
        val parentDepth = if (newParentId == null) -1 else blocks.first { it.id == newParentId }.depth
        val depth = (parentDepth + 1).coerceIn(0, MAX_DEPTH)
        val placed = blocks.map { if (it.id == targetId) it.copy(depth = depth) else it }
        return reposition(placed, targetId, newParentId)
    }

    fun applyRandom(blocks: List<BlockNode>, roll: Int, pick: Int, parentPick: Int): List<BlockNode> {
        if (blocks.isEmpty()) return blocks
        val index = Math.floorMod(pick, blocks.size)
        val depths = blocks.map { it.depth }
        return when (Math.floorMod(roll, 3)) {
            0 -> indentDepth(depths, index)?.let { depth ->
                clamp(blocks.mapIndexed { i, row -> if (i == index) row.copy(depth = depth) else row })
            } ?: blocks
            1 -> outdentDepth(depths, index)?.let { depth ->
                clamp(blocks.mapIndexed { i, row -> if (i == index) row.copy(depth = depth) else row })
            } ?: blocks
            else -> {
                val others = blocks.filter { it.id != blocks[index].id }
                val parent = if (others.isEmpty() || Math.floorMod(parentPick, others.size + 1) == 0) {
                    null
                } else {
                    others[Math.floorMod(parentPick, others.size)].id
                }
                move(blocks, blocks[index].id, parent)
            }
        }
    }

    private fun reposition(blocks: List<BlockNode>, targetId: String, newParentId: String?): List<BlockNode> {
        val target = blocks.first { it.id == targetId }
        val rest = blocks.filter { it.id != targetId }
        val insertAt = if (newParentId == null) {
            rest.size
        } else {
            val parentIndex = rest.indexOfFirst { it.id == newParentId }
            if (parentIndex < 0) return clamp(blocks)
            var at = parentIndex + 1
            val parentDepth = rest[parentIndex].depth
            while (at < rest.size && rest[at].depth > parentDepth) at++
            at
        }
        val prev = rest.getOrNull(insertAt - 1)?.orderKey
        val next = rest.getOrNull(insertAt)?.orderKey
        val order = if ((prev == null || FractionalIndex.isValid(prev)) && (next == null || FractionalIndex.isValid(next))) {
            FractionalIndex.between(prev, next)
        } else {
            target.orderKey
        }
        val nextBlocks = rest.toMutableList()
        nextBlocks.add(insertAt, target.copy(orderKey = order))
        return clamp(nextBlocks)
    }

    fun clamp(blocks: List<BlockNode>): List<BlockNode> {
        if (blocks.isEmpty()) return blocks
        val out = ArrayList<BlockNode>(blocks.size)
        blocks.forEachIndexed { index, row ->
            val cap = if (index == 0) 0 else out[index - 1].depth + 1
            out += row.copy(depth = row.depth.coerceIn(0, minOf(MAX_DEPTH, cap)))
        }
        return out
    }
}
