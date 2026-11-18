package com.aird.core.crypto

/**
 * NIST Ascon-AEAD128 (Ascon-128a parameters: 16-byte rate, 12/8 rounds).
 * State words are little-endian, matching SP 800-232.
 */
object AsconAead {
    fun encrypt(key: ByteArray, nonce: ByteArray, associated: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == 16 && nonce.size == 16)
        val state = initialize(key, nonce)
        absorb(state, associated)
        val cipher = crypt(state, plain, encrypt = true)
        return cipher + tag(state, key)
    }

    fun decrypt(key: ByteArray, nonceAndBody: ByteArray, associated: ByteArray): ByteArray? {
        if (key.size != 16 || nonceAndBody.size < 32) return null
        val nonce = nonceAndBody.copyOfRange(0, 16)
        val body = nonceAndBody.copyOfRange(16, nonceAndBody.size)
        if (body.size < 16) return null
        val cipher = body.copyOfRange(0, body.size - 16)
        val expected = body.copyOfRange(body.size - 16, body.size)
        val state = initialize(key, nonce)
        absorb(state, associated)
        val plain = crypt(state, cipher, encrypt = false)
        val actual = tag(state, key)
        if (!constantEquals(actual, expected)) return null
        return plain
    }

    private fun initialize(key: ByteArray, nonce: ByteArray): LongArray {
        val state = LongArray(5)
        state[0] = le(byteArrayOf(1, 0, 0x8c.toByte(), 128.toByte(), 0, 16, 0, 0), 0)
        state[1] = le(key, 0)
        state[2] = le(key, 8)
        state[3] = le(nonce, 0)
        state[4] = le(nonce, 8)
        permute(state, 12)
        state[3] = state[3] xor le(key, 0)
        state[4] = state[4] xor le(key, 8)
        return state
    }

    private fun absorb(state: LongArray, associated: ByteArray) {
        if (associated.isNotEmpty()) {
            val padded = pad(associated)
            var offset = 0
            while (offset < padded.size) {
                state[0] = state[0] xor le(padded, offset)
                state[1] = state[1] xor le(padded, offset + 8)
                permute(state, 8)
                offset += 16
            }
        }
        state[4] = state[4] xor (1L shl 63)
    }

    private fun crypt(state: LongArray, input: ByteArray, encrypt: Boolean): ByteArray {
        val padded = if (encrypt) {
            pad(input)
        } else {
            val fill = if (input.size % 16 == 0) 16 else 16 - input.size % 16
            input + ByteArray(fill)
        }
        val out = ByteArray(input.size)
        var offset = 0
        while (offset + 16 < padded.size) {
            val c0 = le(padded, offset)
            val c1 = le(padded, offset + 8)
            if (encrypt) {
                state[0] = state[0] xor c0
                state[1] = state[1] xor c1
                write(out, offset, state[0])
                write(out, offset + 8, state[1])
            } else {
                val p0 = state[0] xor c0
                val p1 = state[1] xor c1
                write(out, offset, p0)
                write(out, offset + 8, p1)
                state[0] = c0
                state[1] = c1
            }
            permute(state, 8)
            offset += 16
        }
        val c0 = le(padded, offset)
        val c1 = le(padded, offset + 8)
        if (encrypt) {
            state[0] = state[0] xor c0
            state[1] = state[1] xor c1
            val take = input.size - offset
            val block = ByteArray(16)
            write(block, 0, state[0])
            write(block, 8, state[1])
            if (take > 0) block.copyInto(out, offset, 0, take)
        } else {
            val p0 = state[0] xor c0
            val p1 = state[1] xor c1
            val block = ByteArray(16)
            write(block, 0, p0)
            write(block, 8, p1)
            val take = input.size - offset
            if (take > 0) block.copyInto(out, offset, 0, take)
            val pad = ByteArray(16)
            pad[take] = 1
            val mask = ByteArray(16)
            for (i in take until 16) mask[i] = 0xff.toByte()
            state[0] = (state[0] and le(mask, 0)) xor c0 xor le(pad, 0)
            state[1] = (state[1] and le(mask, 8)) xor c1 xor le(pad, 8)
        }
        return out
    }

    private fun tag(state: LongArray, key: ByteArray): ByteArray {
        state[2] = state[2] xor le(key, 0)
        state[3] = state[3] xor le(key, 8)
        permute(state, 12)
        state[3] = state[3] xor le(key, 0)
        state[4] = state[4] xor le(key, 8)
        val out = ByteArray(16)
        write(out, 0, state[3])
        write(out, 8, state[4])
        return out
    }

    private fun permute(state: LongArray, rounds: Int) {
        for (round in 12 - rounds until 12) {
            state[2] = state[2] xor (0xf0 - round * 0x10 + round).toLong()
            state[0] = state[0] xor state[4]
            state[4] = state[4] xor state[3]
            state[2] = state[2] xor state[1]
            val t = LongArray(5) { i -> (state[i] xor -1L) and state[(i + 1) % 5] }
            for (i in 0 until 5) state[i] = state[i] xor t[(i + 1) % 5]
            state[1] = state[1] xor state[0]
            state[0] = state[0] xor state[4]
            state[3] = state[3] xor state[2]
            state[2] = state[2] xor -1L
            state[0] = state[0] xor rotr(state[0], 19) xor rotr(state[0], 28)
            state[1] = state[1] xor rotr(state[1], 61) xor rotr(state[1], 39)
            state[2] = state[2] xor rotr(state[2], 1) xor rotr(state[2], 6)
            state[3] = state[3] xor rotr(state[3], 10) xor rotr(state[3], 17)
            state[4] = state[4] xor rotr(state[4], 7) xor rotr(state[4], 41)
        }
    }

    private fun pad(bytes: ByteArray): ByteArray {
        val extra = 16 - (bytes.size % 16)
        val out = ByteArray(bytes.size + extra)
        bytes.copyInto(out)
        out[bytes.size] = 1
        return out
    }

    private fun le(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[offset + i].toLong() and 0xff) shl (8 * i))
        return value
    }

    private fun write(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            if (offset + i >= out.size) return
            out[offset + i] = (value ushr (8 * i)).toByte()
        }
    }

    private fun rotr(value: Long, bits: Int): Long = (value ushr bits) or (value shl (64 - bits))

    private fun constantEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
