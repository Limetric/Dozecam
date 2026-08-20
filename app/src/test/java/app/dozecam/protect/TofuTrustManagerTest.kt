package app.dozecam.protect

import java.security.cert.X509Certificate
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TofuTrustManagerTest {

    private val certificate: X509Certificate =
        HeldCertificate.Builder().commonName("console").build().certificate

    @Test
    fun `first contact throws with the certificate fingerprint`() {
        val manager = TofuTrustManager(pinnedFingerprint = null)

        val thrown = assertThrows(UntrustedCertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(certificate), "RSA")
        }

        assertEquals(certificate.sha256Fingerprint(), thrown.fingerprint)
    }

    @Test
    fun `pinned fingerprint is accepted`() {
        val manager = TofuTrustManager(certificate.sha256Fingerprint())

        manager.checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test
    fun `a changed certificate is rejected with both fingerprints`() {
        val manager = TofuTrustManager("AA:BB:CC")

        val thrown = assertThrows(ChangedCertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(certificate), "RSA")
        }

        assertEquals("AA:BB:CC", thrown.pinnedFingerprint)
        assertEquals(certificate.sha256Fingerprint(), thrown.fingerprint)
    }

    @Test
    fun `fingerprint format is colon-separated uppercase hex`() {
        val fingerprint = certificate.sha256Fingerprint()

        assertTrue(fingerprint.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }
}
