package io.github.ferasbusiness666.segno

import io.github.ferasbusiness666.segno.download.Mp4Tagger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

class Mp4TaggerTest {
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + body.size)
            writeBytes(type)
            write(body)
        }
        return out.toByteArray()
    }

    private fun ints(vararg v: Int): ByteArray {
        val b = ByteBuffer.allocate(4 * v.size)
        v.forEach { b.putInt(it) }
        return b.array()
    }

    /** A tiny MP4: ftyp, moov (one track with an stco pointing into mdat), mdat. */
    private fun sample(oldUdta: Boolean): Pair<ByteArray, Int> {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ints(0), "isomM4A ".toByteArray())
        val mdatPayload = "AUDIODATA-1234567890".toByteArray()
        fun moov(offset: Int): ByteArray {
            val stco = box("stco", ints(0, 1, offset))
            val trak = box("trak", box("mdia", box("minf", box("stbl", stco))))
            return if (oldUdta) box("moov", box("mvhd", ints(0, 0, 0)), trak, box("udta", "old".toByteArray()))
            else box("moov", box("mvhd", ints(0, 0, 0)), trak)
        }
        // The chunk offset is where the mdat payload starts.
        val provisional = moov(0)
        val dataAt = ftyp.size + provisional.size + 8
        val file = ftyp + moov(dataAt) + box("mdat", mdatPayload)
        return file to dataAt
    }

    private fun find(b: ByteArray, s: String): Int {
        val needle = s.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..b.size - needle.size) {
            for (j in needle.indices) if (b[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun writesTagsAndKeepsAudioWhereTheOffsetsSay() {
        for (old in listOf(false, true)) {
            val (input, _) = sample(old)
            val f = File.createTempFile("segno", ".m4a")
            f.writeBytes(input)
            val out = ByteArrayOutputStream()
            Mp4Tagger.write(f, out, Mp4Tagger.Tags("Blinding Lights", "The Weeknd", "After Hours", cover = byteArrayOf(1, 2, 3)))
            f.delete()
            val result = out.toByteArray()
            assertTrue(find(result, "ilst") > 0)
            assertTrue(find(result, "Blinding Lights") > 0)
            assertTrue(find(result, "The Weeknd") > 0)
            assertTrue(find(result, "covr") > 0)
            assertEquals(-1, find(result, "old"))
            // The stco entry must point at the audio in the new file.
            val stco = find(result, "stco")
            val offset = ByteBuffer.wrap(result, stco + 4 + 8, 4).int
            val audio = "AUDIODATA-1234567890".toByteArray()
            assertArrayEquals(audio, result.copyOfRange(offset, offset + audio.size))
            // The moov box size matches its contents.
            val moov = find(result, "moov") - 4
            val moovSize = ByteBuffer.wrap(result, moov, 4).int
            assertEquals("mdat", String(result, moov + moovSize + 4, 4, Charsets.ISO_8859_1))
        }
    }

    @Test
    fun recognisesMp4() {
        val (input, _) = sample(false)
        assertTrue(Mp4Tagger.looksLikeMp4(input.inputStream()))
        assertTrue(!Mp4Tagger.looksLikeMp4("not an mp4 file".byteInputStream()))
    }
}
