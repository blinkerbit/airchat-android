package com.aird.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPolicyTest {
    @Test
    fun httpsPublicIsAllowed() {
        assertTrue(HostPolicy.isSafeRemoteUrl("https://dx6si.aiccloud.online"))
        assertTrue(HostPolicy.isSafeRemoteUrl("https://dx6si.aiccloud.online/files"))
    }

    @Test
    fun publicHttpIsBlocked() {
        assertFalse(HostPolicy.isSafeRemoteUrl("http://example.com"))
        assertFalse(HostPolicy.isSafeRemoteUrl("http://dx6si.aiccloud.online/api"))
        assertFalse(HostPolicy.allowsCleartext("example.com"))
    }

    @Test
    fun lanHttpIsAllowed() {
        assertTrue(HostPolicy.isSafeRemoteUrl("http://192.168.1.20:8000"))
        assertTrue(HostPolicy.isSafeRemoteUrl("http://10.0.0.5"))
        assertTrue(HostPolicy.isSafeRemoteUrl("http://172.16.0.2"))
        assertTrue(HostPolicy.isSafeRemoteUrl("http://127.0.0.1:8000"))
        assertTrue(HostPolicy.isSafeRemoteUrl("http://localhost:8000"))
        assertTrue(HostPolicy.isSafeRemoteUrl("http://files.local"))
        assertTrue(HostPolicy.isSafeRemoteUrl("http://[fe80::1]/"))
        assertTrue(HostPolicy.isSafeRemoteUrl("http://[fd00::10]/"))
    }

    @Test
    fun credentialsAndOtherSchemesAreBlocked() {
        assertFalse(HostPolicy.isSafeRemoteUrl("https://user:secret@example.com"))
        assertFalse(HostPolicy.isSafeRemoteUrl("file:///data/data/com.aird/files/secret"))
        assertFalse(HostPolicy.isSafeRemoteUrl("javascript:alert(1)"))
        assertFalse(HostPolicy.isSafeRemoteUrl("http://1.2.3.4"))
    }

    @Test
    fun sameHostIgnoresSchemeAndPath() {
        assertTrue(HostPolicy.sameHost("https://box.local", "http://box.local/api"))
        assertFalse(HostPolicy.sameHost("https://box.local", "https://evil.example"))
    }
}
