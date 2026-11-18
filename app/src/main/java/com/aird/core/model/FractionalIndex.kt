package com.aird.core.model

/**
 * Lexicographic fractional keys (CC0 algorithm from fractional-indexing).
 * Keys sort with ordinary ASCII string comparison and never collide.
 */
object FractionalIndex {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val INT_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val digitLookup = table(DIGITS)
    private val intLookup = table(INT_DIGITS)
    private val smallest = INT_DIGITS[0] + DIGITS[0].toString().repeat(INT_DIGITS.length / 2)

    fun between(prev: String?, next: String?): String = generate(prev, next)

    fun isValid(key: String): Boolean = try {
        validateOrderKey(key)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun generate(a: String?, b: String?): String {
        var lower = a
        var upper = b
        if (lower != null) validateOrderKey(lower)
        if (upper != null) validateOrderKey(upper)
        if (lower != null && upper != null && lower > upper) {
            val swap = lower
            lower = upper
            upper = swap
        }
        if (lower == null) {
            if (upper == null) return INT_DIGITS[INT_DIGITS.length / 2].toString() + DIGITS[0]
            val head = integerPart(upper)
            val fraction = upper.substring(head.length)
            if (head == smallest) return head + midpoint("", fraction)
            if (head < upper) return head
            return decrementInteger(head) ?: error("cannot decrement any more")
        }
        if (upper == null) {
            val head = integerPart(lower)
            val fraction = lower.substring(head.length)
            return incrementInteger(head) ?: (head + midpoint(fraction, null))
        }
        val left = integerPart(lower)
        val leftFraction = lower.substring(left.length)
        val right = integerPart(upper)
        if (left == right) return left + midpoint(leftFraction, upper.substring(right.length))
        val bumped = incrementInteger(left) ?: error("cannot increment any more")
        if (bumped < upper) return bumped
        return left + midpoint(leftFraction, null)
    }

    private fun midpoint(a: String, b: String?): String {
        val upper = b?.takeIf { it.isNotEmpty() }
        val zero = DIGITS[0]
        if (upper != null && a >= upper) error("$a >= $upper")
        if (a.endsWith(zero) || (upper != null && upper.endsWith(zero))) error("trailing zero")
        if (upper != null) {
            var n = 0
            while (n < upper.length && (a.getOrNull(n) ?: zero) == upper[n]) n++
            if (n > 0) return upper.substring(0, n) + midpoint(a.drop(n), upper.drop(n))
        }
        val digitA = if (a.isEmpty()) 0 else digitLookup[a[0].code]
        val digitB = if (upper == null) DIGITS.length else digitLookup[upper[0].code]
        if (digitB - digitA > 1) {
            val mid = kotlin.math.round(0.5 * (digitA + digitB)).toInt()
            return DIGITS[mid].toString()
        }
        if (upper != null && upper.length > 1) return upper.substring(0, 1)
        return DIGITS[digitA].toString() + midpoint(a.drop(1), null)
    }

    private fun integerPart(key: String): String {
        val length = integerLength(key[0])
        if (length > key.length) error("invalid order key: $key")
        return key.substring(0, length)
    }

    private fun integerLength(head: Char): Int {
        val i = intLookup[head.code]
        if (INT_DIGITS.getOrNull(i) != head) error("invalid order key head: $head")
        val half = INT_DIGITS.length / 2
        return if (i < half) half - i + 1 else i - half + 2
    }

    private fun validateOrderKey(key: String) {
        if (key == smallest) error("invalid order key: $key")
        val head = integerPart(key)
        val fraction = key.substring(head.length)
        if (fraction.endsWith(DIGITS[0])) error("invalid order key: $key")
    }

    private fun validateInteger(value: String) {
        if (value.length != integerLength(value[0])) error("invalid integer part of order key: $value")
    }

    private fun incrementInteger(value: String): String? {
        validateInteger(value)
        val zero = DIGITS[0]
        val trailing = StringBuilder()
        for (i in value.length - 1 downTo 1) {
            val next = digitLookup[value[i].code] + 1
            if (next == DIGITS.length) trailing.insert(0, zero)
            else return "${value[0]}${value.substring(1, i)}${DIGITS[next]}$trailing"
        }
        val headIndex = intLookup[value[0].code]
        if (headIndex == INT_DIGITS.length - 1) return null
        val head = INT_DIGITS[headIndex + 1]
        val delta = integerLength(head) - integerLength(value[0])
        val tail = when {
            delta > 0 -> trailing.toString() + zero
            delta < 0 -> trailing.drop(1)
            else -> trailing.toString()
        }
        return head.toString() + tail
    }

    private fun decrementInteger(value: String): String? {
        validateInteger(value)
        val last = DIGITS[DIGITS.length - 1]
        val trailing = StringBuilder()
        for (i in value.length - 1 downTo 1) {
            val next = digitLookup[value[i].code] - 1
            if (next == -1) trailing.insert(0, last)
            else return "${value[0]}${value.substring(1, i)}${DIGITS[next]}$trailing"
        }
        val headIndex = intLookup[value[0].code]
        if (headIndex == 0) return null
        val head = INT_DIGITS[headIndex - 1]
        val delta = integerLength(head) - integerLength(value[0])
        val tail = when {
            delta > 0 -> trailing.toString() + last
            delta < 0 -> trailing.drop(1)
            else -> trailing.toString()
        }
        return head.toString() + tail
    }

    private fun table(alphabet: String): IntArray {
        val map = IntArray(256)
        alphabet.forEachIndexed { index, ch -> map[ch.code] = index }
        return map
    }

    private fun error(message: String): Nothing = throw IllegalArgumentException(message)
}
