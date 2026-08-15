package com.phonelink.app

import com.phonelink.app.transfer.TransferErrorClassifier
import com.phonelink.app.transfer.TransferFailureKind
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferErrorClassifierTest {

    @Test
    fun revoked_returnsRevokedKind() {
        val failure = TransferErrorClassifier.fromHttp(403, "DEVICE_REVOKED")
        assertEquals(TransferFailureKind.DEVICE_REVOKED, failure.kind)
        assertFalse(failure.canRetry)
    }

    @Test
    fun authInvalid_returnsAuthKind() {
        val failure = TransferErrorClassifier.fromHttp(401, "AUTH_INVALID")
        assertEquals(TransferFailureKind.AUTH_INVALID, failure.kind)
    }

    @Test
    fun paused_returnsPausedKindWithRetry() {
        val failure = TransferErrorClassifier.fromHttp(503, "SERVICE_PAUSED")
        assertEquals(TransferFailureKind.SERVICE_PAUSED, failure.kind)
        assertTrue(failure.canRetry)
    }

    @Test
    fun fileTooLarge_returnsSizeKind() {
        val failure = TransferErrorClassifier.fromHttp(413, "FILE_TOO_LARGE")
        assertEquals(TransferFailureKind.FILE_TOO_LARGE, failure.kind)
    }

    @Test
    fun unsupportedMedia_returnsMediaKind() {
        val failure = TransferErrorClassifier.fromHttp(415, "UNSUPPORTED_MEDIA_TYPE")
        assertEquals(TransferFailureKind.UNSUPPORTED_MEDIA_TYPE, failure.kind)
    }

    @Test
    fun hashMismatch_returnsHashKindWithRetry() {
        val failure = TransferErrorClassifier.fromHttp(422, "TRANSFER_HASH_MISMATCH")
        assertEquals(TransferFailureKind.TRANSFER_HASH_MISMATCH, failure.kind)
        assertTrue(failure.canRetry)
    }

    @Test
    fun http500_returnsServerKindWithRetry() {
        val failure = TransferErrorClassifier.fromHttp(500, "DISK_WRITE_FAILED")
        assertEquals(TransferFailureKind.HTTP_500, failure.kind)
        assertTrue(failure.canRetry)
    }

    @Test
    fun tlsHandshake_returnsFingerprintKind() {
        val failure = TransferErrorClassifier.fromIo(SSLHandshakeException("Certificate fingerprint mismatch"))
        assertEquals(TransferFailureKind.TLS_FINGERPRINT, failure.kind)
        assertFalse(failure.canRetry)
    }

    @Test
    fun connectionRefused_returnsOfflineKindWithRetry() {
        val failure = TransferErrorClassifier.fromIo(java.net.ConnectException("Connection refused"))
        assertEquals(TransferFailureKind.NETWORK_TIMEOUT, failure.kind)
        assertTrue(failure.canRetry)
    }

    @Test
    fun timeout_returnsOfflineKindWithRetry() {
        val failure = TransferErrorClassifier.fromIo(java.net.SocketTimeoutException("timeout"))
        assertEquals(TransferFailureKind.NETWORK_TIMEOUT, failure.kind)
        assertTrue(failure.canRetry)
    }

    @Test
    fun genericIo_returnsNetworkKind() {
        val failure = TransferErrorClassifier.fromIo(java.io.IOException("boom"))
        assertEquals(TransferFailureKind.NETWORK_TIMEOUT, failure.kind)
        assertTrue(failure.canRetry)
    }
}