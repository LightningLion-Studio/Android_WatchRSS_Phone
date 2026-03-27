package com.lightningstudio.watchrss.phone.acoustic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class AcousticPacket(
    val waveform: ShortArray,
    val durationMs: Int,
    val compressedBytes: Int,
    val encodedChars: Int
)

object AcousticCodec {
    const val SAMPLE_RATE = 22_050
    const val SYMBOL_DURATION_MS = 90

    private const val LENGTH_CHAR_COUNT = 6
    private const val CRC_CHAR_COUNT = 7
    private const val PREAMBLE_SYMBOL_COUNT = 8
    private const val END_SYMBOL_COUNT = 4
    private const val START_SYMBOL = 32
    private const val END_SYMBOL = 33
    private const val PHASE_BUCKETS = 4
    private const val MIN_PREAMBLE_MATCH = 6
    private const val MIN_SIGNAL_RMS = 320.0
    private const val MIN_POWER_RATIO = 1.18
    private const val FADE_RATIO = 0.12
    private const val AMPLITUDE = 0.62

    private const val START_FREQUENCY = 720.0
    private const val END_FREQUENCY = 960.0
    private const val DATA_FREQUENCY_START = 1_240.0
    private const val DATA_FREQUENCY_STEP = 105.0

    private val base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val base32Lookup = IntArray(128) { -1 }.apply {
        base32Alphabet.forEachIndexed { index, char ->
            this[char.code] = index
        }
    }

    private val symbolSamples = SAMPLE_RATE * SYMBOL_DURATION_MS / 1_000
    private val stepSamples = symbolSamples / PHASE_BUCKETS
    private val fadeSamples = (symbolSamples * FADE_RATIO).roundToInt().coerceAtLeast(1)
    private val dataFrequencies = DoubleArray(32) { DATA_FREQUENCY_START + (it * DATA_FREQUENCY_STEP) }
    private val frequencies = DoubleArray(34).apply {
        this[0] = START_FREQUENCY
        dataFrequencies.forEachIndexed { index, value -> this[index + 1] = value }
        this[lastIndex] = END_FREQUENCY
    }

    fun encode(payload: ByteArray): AcousticPacket {
        val compressed = gzip(payload)
        val encodedBody = encodeBase32(compressed)
        val header = buildString {
            append(encodeFixedBase32(compressed.size.toLong(), LENGTH_CHAR_COUNT))
            append(encodeFixedBase32(crc32(compressed), CRC_CHAR_COUNT))
        }
        val symbolCount = PREAMBLE_SYMBOL_COUNT + header.length + encodedBody.length + END_SYMBOL_COUNT
        val symbols = IntArray(symbolCount)
        var offset = 0
        repeat(PREAMBLE_SYMBOL_COUNT) {
            symbols[offset++] = START_SYMBOL
        }
        header.forEach { symbols[offset++] = decodeBase32Char(it) }
        encodedBody.forEach { symbols[offset++] = decodeBase32Char(it) }
        repeat(END_SYMBOL_COUNT) {
            symbols[offset++] = END_SYMBOL
        }
        return AcousticPacket(
            waveform = synthesize(symbols),
            durationMs = symbolCount * SYMBOL_DURATION_MS,
            compressedBytes = compressed.size,
            encodedChars = encodedBody.length
        )
    }

    fun estimateDurationMs(payload: ByteArray): Int = encode(payload).durationMs

    fun decode(samples: ShortArray): ByteArray? {
        if (samples.size < symbolSamples * (PREAMBLE_SYMBOL_COUNT + LENGTH_CHAR_COUNT + CRC_CHAR_COUNT + END_SYMBOL_COUNT)) {
            return null
        }

        repeat(PHASE_BUCKETS) { phase ->
            val classified = classifyByPhase(samples, phase)
            val decoded = decodeSymbols(classified)
            if (decoded != null) {
                return decoded
            }
        }
        return null
    }

    private fun classifyByPhase(samples: ShortArray, phase: Int): IntArray {
        val symbolValues = ArrayList<Int>()
        var startIndex = phase * stepSamples
        while (startIndex + symbolSamples <= samples.size) {
            symbolValues += classifyWindow(samples, startIndex)
            startIndex += symbolSamples
        }
        return symbolValues.toIntArray()
    }

    private fun decodeSymbols(symbols: IntArray): ByteArray? {
        var index = 0
        while (index <= symbols.size - MIN_PREAMBLE_MATCH) {
            if (symbols[index] != START_SYMBOL) {
                index++
                continue
            }

            var preambleEnd = index
            while (preambleEnd < symbols.size && symbols[preambleEnd] == START_SYMBOL) {
                preambleEnd++
            }
            if (preambleEnd - index < MIN_PREAMBLE_MATCH) {
                index = preambleEnd
                continue
            }

            val headerEnd = preambleEnd + LENGTH_CHAR_COUNT + CRC_CHAR_COUNT
            if (headerEnd > symbols.size) {
                return null
            }

            val lengthChars = symbols.sliceArray(preambleEnd until preambleEnd + LENGTH_CHAR_COUNT)
            val crcChars = symbols.sliceArray(preambleEnd + LENGTH_CHAR_COUNT until headerEnd)
            if (lengthChars.any { it !in 0..31 } || crcChars.any { it !in 0..31 }) {
                index = preambleEnd
                continue
            }

            val compressedLength = decodeFixedBase32(lengthChars)
            val expectedCrc = decodeFixedBase32(crcChars)
            val bodyCharCount = encodedCharCount(compressedLength)
            val bodyEnd = headerEnd + bodyCharCount
            if (compressedLength <= 0 || bodyEnd > symbols.size) {
                index = preambleEnd
                continue
            }

            val bodySymbols = symbols.sliceArray(headerEnd until bodyEnd)
            if (bodySymbols.any { it !in 0..31 }) {
                index = preambleEnd
                continue
            }

            val endEnd = bodyEnd + END_SYMBOL_COUNT
            if (endEnd > symbols.size || symbols.sliceArray(bodyEnd until endEnd).any { it != END_SYMBOL }) {
                index = preambleEnd
                continue
            }

            val compressed = decodeBase32(bodySymbols.map { base32Alphabet[it] }.joinToString(""), compressedLength)
            if (crc32(compressed) != expectedCrc.toLong()) {
                index = preambleEnd
                continue
            }
            return gunzip(compressed)
        }

        return null
    }

    private fun classifyWindow(samples: ShortArray, startIndex: Int): Int {
        var maxPower = Double.NEGATIVE_INFINITY
        var secondPower = Double.NEGATIVE_INFINITY
        var bestIndex = -1
        var rmsAccumulator = 0.0

        for (offset in 0 until symbolSamples) {
            val value = samples[startIndex + offset].toDouble()
            rmsAccumulator += value * value
        }
        val rms = kotlin.math.sqrt(rmsAccumulator / symbolSamples)
        if (rms < MIN_SIGNAL_RMS) {
            return -1
        }

        frequencies.forEachIndexed { index, frequency ->
            val power = goertzelPower(samples, startIndex, symbolSamples, frequency)
            if (power > maxPower) {
                secondPower = maxPower
                maxPower = power
                bestIndex = index
            } else if (power > secondPower) {
                secondPower = power
            }
        }

        if (bestIndex < 0) {
            return -1
        }
        if (secondPower.isFinite() && maxPower < secondPower * MIN_POWER_RATIO) {
            return -1
        }

        return when (bestIndex) {
            0 -> START_SYMBOL
            frequencies.lastIndex -> END_SYMBOL
            else -> bestIndex - 1
        }
    }

    private fun goertzelPower(
        samples: ShortArray,
        startIndex: Int,
        sampleCount: Int,
        frequency: Double
    ): Double {
        val normalizedFrequency = frequency / SAMPLE_RATE
        val coefficient = 2.0 * cos(2.0 * PI * normalizedFrequency)
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0

        for (offset in 0 until sampleCount) {
            q0 = coefficient * q1 - q2 + samples[startIndex + offset]
            q2 = q1
            q1 = q0
        }

        return q1 * q1 + q2 * q2 - coefficient * q1 * q2
    }

    private fun synthesize(symbols: IntArray): ShortArray {
        val waveform = ShortArray(symbols.size * symbolSamples)
        val fullScale = Short.MAX_VALUE * AMPLITUDE

        symbols.forEachIndexed { symbolIndex, symbol ->
            val frequency = when (symbol) {
                START_SYMBOL -> START_FREQUENCY
                END_SYMBOL -> END_FREQUENCY
                in 0..31 -> dataFrequencies[symbol]
                else -> return@forEachIndexed
            }
            val phaseOffset = symbolIndex * symbolSamples
            for (sampleIndex in 0 until symbolSamples) {
                val ramp = when {
                    sampleIndex < fadeSamples -> sampleIndex.toDouble() / fadeSamples
                    sampleIndex > symbolSamples - fadeSamples -> (symbolSamples - sampleIndex).toDouble() / fadeSamples
                    else -> 1.0
                }.coerceIn(0.0, 1.0)
                val value = sin((2.0 * PI * frequency * sampleIndex) / SAMPLE_RATE) * fullScale * ramp
                waveform[phaseOffset + sampleIndex] = value.roundToInt().toShort()
            }
        }
        return waveform
    }

    private fun encodeBase32(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val output = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                output.append(base32Alphabet[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            output.append(base32Alphabet[index])
        }
        return output.toString()
    }

    private fun decodeBase32(text: String, expectedBytes: Int): ByteArray {
        val output = ByteArray(expectedBytes)
        var outputIndex = 0
        var buffer = 0
        var bitsLeft = 0

        text.forEach { char ->
            val value = decodeBase32Char(char)
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            while (bitsLeft >= 8 && outputIndex < expectedBytes) {
                output[outputIndex++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }

        if (outputIndex != expectedBytes) {
            throw IllegalArgumentException("Base32 decoded length mismatch")
        }
        return output
    }

    private fun encodeFixedBase32(value: Long, width: Int): String {
        var remaining = value
        val chars = CharArray(width)
        for (index in width - 1 downTo 0) {
            chars[index] = base32Alphabet[(remaining and 0x1F).toInt()]
            remaining = remaining shr 5
        }
        require(remaining == 0L) { "Value $value is too large for width $width" }
        return String(chars)
    }

    private fun decodeFixedBase32(symbols: IntArray): Int {
        var result = 0L
        symbols.forEach { symbol ->
            result = (result shl 5) or symbol.toLong()
        }
        return result.toInt()
    }

    private fun decodeBase32Char(char: Char): Int {
        if (char.code >= base32Lookup.size || base32Lookup[char.code] < 0) {
            throw IllegalArgumentException("Invalid base32 char: $char")
        }
        return base32Lookup[char.code]
    }

    private fun encodedCharCount(byteCount: Int): Int = ceil(byteCount * 8.0 / 5.0).toInt()

    private fun crc32(bytes: ByteArray): Long {
        return CRC32().apply { update(bytes) }.value
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { stream ->
            stream.write(bytes)
        }
        return output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }
}
