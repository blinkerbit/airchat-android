package com.aird.chat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCryptoTest {
    @Test
    fun matchesDesktopWebCrypto() {
        val identity = ChatCrypto.Identity(
            x = "b4TfLG-3PBcnw59Fy4RJQBz6M3SycrI1KtV7tdIdiz8",
            y = "wOA-Wr_jKI8HFRsZXsOzTZXO9pThfUwo3kb7TL7lwtA",
            d = "f9Rq0M_iQnw8vysST1tNavjleAR_vxfW5050p5uSptM",
        )
        val conv = "11111111-1111-1111-1111-111111111111"
        val raw = ChatCrypto.unwrap(
            identity,
            epkX = "Z8J4U7stTEnzl3722epCoZmxyvMEFEVHw33vGHypwvE",
            epkY = "EM7PvvTr0soawjiKYaIDbjy6XWCbeuQc1YxJa5-ptuE",
            convId = conv,
            iv = "AwMDAwMDAwMDAwMD",
            ct = "gZwLNKpc0vrjKHB2CRIAMqxT4xYjet5vy0m2d+ahFnCPbx3CDVmUdxfomP4nC/dg",
        )
        assertEquals(32, raw.size)
        assertTrue(raw.all { it == 7.toByte() })
        assertEquals("hello aird", ChatCrypto.decrypt(raw, conv, "CQkJCQkJCQkJCQkJ", "T+Do+NHQoAjSBkUfDtoCCTH/ZlvVIHSldWM="))
        assertEquals("pXcLE+z23CS9YXa8yXTk4LJctlQyhSH1l90hAmOPoek", identity.kid())
    }
}
