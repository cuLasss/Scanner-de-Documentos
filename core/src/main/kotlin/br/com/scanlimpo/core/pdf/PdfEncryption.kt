package br.com.scanlimpo.core.pdf

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Standard Security Handler revisão 6 (AES-256, ISO 32000-2).
 *
 * A senha de proprietário é aleatória: quem sabe a senha de abertura lê, imprime e copia normalmente.
 */
internal class PdfEncryption(password: String) {
    private val random = SecureRandom()
    private val fileKey = randomBytes(32)

    /** Dicionário /Encrypt. Os valores dele nunca são cifrados. */
    val dictionary: String

    init {
        val user = normalize(password)
        val owner = randomBytes(32)

        val uValidation = randomBytes(8)
        val uKey = randomBytes(8)
        val u = hash(user, uValidation, EMPTY) + uValidation + uKey
        val ue = aesCbcNoPadding(hash(user, uKey, EMPTY), fileKey)

        val oValidation = randomBytes(8)
        val oKey = randomBytes(8)
        val o = hash(owner, oValidation, u) + oValidation + oKey
        val oe = aesCbcNoPadding(hash(owner, oKey, u), fileKey)

        val perms = ByteArray(16)
        for (i in 0 until 4) perms[i] = (PERMISSIONS shr (8 * i)).toByte()
        for (i in 4 until 8) perms[i] = 0xFF.toByte()
        perms[8] = 'T'.code.toByte()
        perms[9] = 'a'.code.toByte()
        perms[10] = 'd'.code.toByte()
        perms[11] = 'b'.code.toByte()
        randomBytes(4).copyInto(perms, 12)
        val permsEnc = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"))
            doFinal(perms)
        }

        dictionary = "<< /Filter /Standard /V 5 /R 6 /Length 256 /P $PERMISSIONS /EncryptMetadata true " +
            "/CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> /StmF /StdCF /StrF /StdCF " +
            "/O <${o.toHex()}> /U <${u.toHex()}> /OE <${oe.toHex()}> /UE <${ue.toHex()}> /Perms <${permsEnc.toHex()}> >>"
    }

    /** Cifra um stream ou string: IV aleatório de 16 bytes seguido do texto cifrado em AES-CBC. */
    fun encrypt(data: ByteArray): ByteArray {
        val iv = randomBytes(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(data)
    }

    private fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    private fun normalize(password: String): ByteArray {
        val bytes = Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        return if (bytes.size > 127) bytes.copyOf(127) else bytes
    }

    private fun aesCbcNoPadding(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return cipher.doFinal(data)
    }

    /** Algoritmo 2.B da ISO 32000-2. */
    private fun hash(password: ByteArray, salt: ByteArray, userData: ByteArray): ByteArray {
        var k = MessageDigest.getInstance("SHA-256").digest(password + salt + userData)
        var round = 0
        while (true) {
            val unit = password + k + userData
            val k1 = ByteArray(unit.size * 64)
            for (i in 0 until 64) unit.copyInto(k1, i * unit.size)

            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(k.copyOfRange(0, 16), "AES"),
                IvParameterSpec(k.copyOfRange(16, 32)),
            )
            val e = cipher.doFinal(k1)

            var sum = 0
            for (i in 0 until 16) sum += e[i].toInt() and 0xFF
            val algorithm = when (sum % 3) {
                0 -> "SHA-256"
                1 -> "SHA-384"
                else -> "SHA-512"
            }
            k = MessageDigest.getInstance(algorithm).digest(e)

            round++
            if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
        }
        return k.copyOf(32)
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val PERMISSIONS = -4 // Tudo liberado.
    }
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789ABCDEF"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return sb.toString()
}
