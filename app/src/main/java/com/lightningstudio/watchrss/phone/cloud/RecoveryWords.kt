package com.lightningstudio.watchrss.phone.cloud

import java.security.MessageDigest
import java.security.SecureRandom

object RecoveryWords {
    private const val ENTROPY_BYTES = 32
    private const val WORD_COUNT = 24
    private const val BITS_PER_WORD = 11

    private val prefixes =
        "ba be bi bo bu ca ce ci co cu da de di do du fa fe fi fo fu ga ge gi go gu ha he hi ho hu ja je"
            .split(' ')
    private val suffixes =
        "la le li lo lu ma me mi mo mu na ne ni no nu pa pe pi po pu ra re ri ro ru sa se si so su ta te ti to tu va ve vi vo vu wa we wi wo wu ya ye yi yo yu za ze zi zo zu ka ke ki ko ku qa qe qi qo"
            .split(' ')
    private val words = prefixes.flatMap { prefix -> suffixes.map { suffix -> prefix + suffix } }
    private val indices = words.withIndex().associate { it.value to it.index }

    init {
        check(prefixes.size == 32)
        check(suffixes.size == 64)
        check(words.size == 2048 && words.distinct().size == words.size)
    }

    data class Generated(
        val words: List<String>,
        val entropy: ByteArray
    )

    fun generate(random: SecureRandom = SecureRandom()): Generated {
        val entropy = ByteArray(ENTROPY_BYTES).also(random::nextBytes)
        return Generated(encode(entropy), entropy)
    }

    fun encode(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "恢复密钥熵必须为256位" }
        val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)[0]
        val payload = entropy + checksum
        return List(WORD_COUNT) { wordIndex ->
            var value = 0
            repeat(BITS_PER_WORD) { bitWithinWord ->
                val bitIndex = wordIndex * BITS_PER_WORD + bitWithinWord
                val byte = payload[bitIndex / 8].toInt() and 0xff
                val bit = (byte shr (7 - bitIndex % 8)) and 1
                value = (value shl 1) or bit
            }
            words[value]
        }
    }

    fun decode(input: List<String>): ByteArray {
        require(input.size == WORD_COUNT) { "恢复密钥必须包含24个词" }
        val payload = ByteArray(ENTROPY_BYTES + 1)
        input.forEachIndexed { wordIndex, rawWord ->
            val word = rawWord.trim().lowercase()
            val value = indices[word] ?: error("恢复词无效：$rawWord")
            repeat(BITS_PER_WORD) { bitWithinWord ->
                val bit = (value shr (BITS_PER_WORD - 1 - bitWithinWord)) and 1
                val bitIndex = wordIndex * BITS_PER_WORD + bitWithinWord
                payload[bitIndex / 8] =
                    (payload[bitIndex / 8].toInt() or (bit shl (7 - bitIndex % 8))).toByte()
            }
        }
        val entropy = payload.copyOfRange(0, ENTROPY_BYTES)
        val expectedChecksum = MessageDigest.getInstance("SHA-256").digest(entropy)[0]
        require(payload.last() == expectedChecksum) { "恢复词校验失败" }
        return entropy
    }

    fun parse(text: String): List<String> =
        text.trim().split(Regex("""\s+""")).filter(String::isNotBlank)
}
