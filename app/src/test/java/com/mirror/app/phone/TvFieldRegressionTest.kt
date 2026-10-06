package com.mirror.app.phone

import io.github.muntashirakon.crypto.ed25519.Ed25519
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.Random

/** Independent integer arithmetic checks the vendored signed-limb multiplication fix. */
class TvFieldRegressionTest {
    @Test fun signedPointCoordinatesMultiplyModulo25519() {
        val field = Ed25519.getSpec().curve.field
        val prime = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        val random = Random(190)
        fun encoded() = ByteArray(32).also { random.nextBytes(it); it[31] = (it[31].toInt() and 127).toByte() }
        fun integer(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())
        repeat(200) {
            val a = encoded(); val b = encoded(); val c = encoded()
            val value = field.fromByteArray(a).add(field.fromByteArray(b)).multiply(field.fromByteArray(c).subtract(field.fromByteArray(b)))
            val expected = integer(a).add(integer(b)).multiply(integer(c).subtract(integer(b))).mod(prime)
            assertEquals(expected, integer(value.toByteArray()))
        }
    }
    @Test fun compressedPointSignBitUsesUnsignedJavaByte() {
        val curve = Ed25519.getSpec().curve
        for (sign in listOf(0, 128)) {
            val bytes = Ed25519.getSpec().b.toByteArray(); bytes[31] = ((bytes[31].toInt() and 127) or sign).toByte()
            assertArrayEquals(bytes, curve.fromBytesNegateVarTime(bytes).toByteArray())
        }
    }
}
