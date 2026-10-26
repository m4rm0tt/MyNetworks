package ch.m4rm0tt.mynetworks.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class BackupCryptoTest {
    private val it = 100_000 // minimum accepté, pour des tests rapides
    private val pw = "correct horse battery staple".toCharArray()

    private fun roundTrip(size: Int) {
        val data = Random(size).nextBytes(size)
        val enc = BackupCrypto.encryptBytes(data, pw, it)
        val dec = BackupCrypto.decrypt(ByteArrayInputStream(enc), pw).readBytes()
        assertArrayEquals(data, dec)
    }

    @Test fun emptyPayload() = roundTrip(0)
    @Test fun smallPayload() = roundTrip(10)
    @Test fun exactSegment() = roundTrip(BackupCrypto.SEGMENT_SIZE)
    @Test fun multiSegment() = roundTrip(BackupCrypto.SEGMENT_SIZE * 3 + 17)

    private fun expect(reason: BackupException.Reason, block: () -> Unit) {
        try {
            block(); fail("exception attendue : $reason")
        } catch (e: BackupException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun wrongPasswordIsDetectedBeforeReading() {
        val enc = BackupCrypto.encryptBytes(ByteArray(1000) { 1 }, pw, it)
        expect(BackupException.Reason.WRONG_PASSWORD) { BackupCrypto.decrypt(ByteArrayInputStream(enc), "nope".toCharArray()) }
    }

    @Test
    fun flippedByteIsDetected() {
        val enc = BackupCrypto.encryptBytes(ByteArray(200_000) { (it % 251).toByte() }, pw, it)
        for (pos in listOf(70, 100, enc.size / 2, enc.size - 1)) {
            val bad = enc.copyOf(); bad[pos] = (bad[pos].toInt() xor 0x40).toByte()
            expect(BackupException.Reason.CORRUPTED) { BackupCrypto.decrypt(ByteArrayInputStream(bad), pw).readBytes() }
        }
    }

    @Test
    fun headerTamperingIsDetected() {
        val enc = BackupCrypto.encryptBytes(ByteArray(100) { 7 }, pw, it)
        val bad = enc.copyOf(); bad[20] = (bad[20].toInt() xor 1).toByte() // sel modifié
        try {
            BackupCrypto.decrypt(ByteArrayInputStream(bad), pw).readBytes(); fail()
        } catch (e: BackupException) {
            // sel modifié → vérificateur faux (WRONG_PASSWORD) ; acceptable : rien n'est lu.
        }
    }

    @Test
    fun truncationIsDetected() {
        val enc = BackupCrypto.encryptBytes(ByteArray(BackupCrypto.SEGMENT_SIZE * 2 + 5) { 3 }, pw, it)
        // Coupure exactement après le premier segment : sans drapeau « dernier », c'est refusé.
        val firstSegEnd = 54 + 4 + BackupCrypto.SEGMENT_SIZE + 16
        expect(BackupException.Reason.CORRUPTED) { BackupCrypto.decrypt(ByteArrayInputStream(enc.copyOf(firstSegEnd)), pw).readBytes() }
        expect(BackupException.Reason.CORRUPTED) { BackupCrypto.decrypt(ByteArrayInputStream(enc.copyOf(enc.size - 3)), pw).readBytes() }
    }

    @Test
    fun notABackup() {
        expect(BackupException.Reason.NOT_A_BACKUP) { BackupCrypto.decrypt(ByteArrayInputStream("PK\u0003\u0004hello".toByteArray()), pw) }
        expect(BackupException.Reason.NOT_A_BACKUP) { BackupCrypto.decrypt(ByteArrayInputStream(ByteArray(3)), pw) }
    }

    @Test
    fun sameInputGivesDifferentCiphertext() {
        val a = BackupCrypto.encryptBytes(ByteArray(10), pw, it)
        val b = BackupCrypto.encryptBytes(ByteArray(10), pw, it)
        assert(!a.contentEquals(b))
    }
}
