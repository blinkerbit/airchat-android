package com.aird.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyIdTest {
    @Test
    fun credentialIdsMatchAcrossBase64Alphabets() {
        assertTrue(sameCredentialId("abc-_", "abc+/"))
        assertTrue(sameCredentialId("abc=", "abc"))
        assertFalse(sameCredentialId("abc", "abd"))
        assertFalse(sameCredentialId("", "abc"))
    }
}
