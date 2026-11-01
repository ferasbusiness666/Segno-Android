package io.github.ferasbusiness666.segno.download

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Writes the song's name, artist, album and cover into an M4A file (MP4 "ilst" tags, the kind
 * iTunes uses), so the phone's own music apps show them too. No library needed: the tags go in
 * a "udta" box inside "moov", and any file offsets that move because of it are fixed.
 *
 * Works for both plain and fragmented MP4 (what YouTube sends).
 */
object Mp4Tagger {
    class Tags(
        val title: String,
        val artist: String,
        val album: String = "",
        val cover: ByteArray? = null,
        val coverIsPng: Boolean = false,
        val comment: String = "",
    )

    private class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    /** Copies [src] to [out] with [tags] in it. Throws [IOException] if [src] isn't an MP4 file. */
    fun write(src: File, out: OutputStream, tags: Tags) {
        RandomAccessFile(src, "r").use { raf ->
            val boxes = topLevel(raf)
            val moov = boxes.firstOrNull { it.type == "moov" } ?: throw IOException("Not an MP4 file: no moov box")
            if (moov.size > 64L * 1024 * 1024) throw IOException("moov box too big")
            val oldMoov = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(oldMoov)
            val newMoov = rebuildMoov(oldMoov, moov.headerSize, tags)
            val delta = (newMoov.size - oldMoov.size).toLong()
            // Data after the moov box moves by delta; offsets pointing there need the same nudge.
            patchChunkOffsets(newMoov, 8, newMoov.size, moov.offset, delta)

            val buf = ByteArray(64 * 1024)
            for (b in boxes) {
                when {
                    b === moov -> out.write(newMoov)
                    b.type == "mfra" -> Unit // optional index of absolute offsets; players don't need it
                    b.type == "moof" && b.offset > moov.offset && b.size < 16L * 1024 * 1024 -> {
                        val bytes = ByteArray(b.size.toInt())
                        raf.seek(b.offset)
                        raf.readFully(bytes)
                        patchFragment(bytes, b.headerSize, bytes.size, moov.offset, delta)
                        out.write(bytes)
                    }
                    else -> copy(raf, b.offset, b.size, out, buf)
                }
            }
            out.flush()
        }
    }

    private fun copy(raf: RandomAccessFile, offset: Long, size: Long, out: OutputStream, buf: ByteArray) {
        raf.seek(offset)
        var left = size
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("File ended early")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun topLevel(raf: RandomAccessFile): List<Box> {
        val out = mutableListOf<Box>()
        val len = raf.length()
        var pos = 0L
        while (pos + 8 <= len) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = fourcc(raf.readInt())
            var header = 8
            if (size == 1L) {
                size = raf.readLong()
                header = 16
            } else if (size == 0L) {
                size = len - pos
            }
            if (size < header || pos + size > len) {
                // A broken or cut-off last box: keep the rest as it is.
                out += Box(type, pos, len - pos, header)
                break
            }
            out += Box(type, pos, size, header)
            pos += size
        }
        return out
    }

    private fun fourcc(v: Int): String = String(
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()),
        Charsets.ISO_8859_1,
    )

    private fun readU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun writeU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun readU64(b: ByteArray, at: Int): Long = (readU32(b, at) shl 32) or readU32(b, at + 4)

    private fun writeU64(b: ByteArray, at: Int, v: Long) {
        writeU32(b, at, v ushr 32)
        writeU32(b, at + 4, v and 0xFFFFFFFFL)
    }

    private fun typeAt(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)

    /** Calls [f] with (type, start, end, headerSize) for each box between [from] and [to]. */
    private inline fun children(b: ByteArray, from: Int, to: Int, f: (String, Int, Int, Int) -> Unit) {
        var p = from
        while (p + 8 <= to) {
            var size = readU32(b, p)
            var header = 8
            if (size == 1L && p + 16 <= to) {
                size = readU64(b, p + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - p).toLong()
            }
            if (size < header || p + size > to) return
            f(typeAt(b, p + 4), p, (p + size).toInt(), header)
            p += size.toInt()
        }
    }

    /** moov's children without any old udta, plus a new udta with our tags. */
    private fun rebuildMoov(moov: ByteArray, header: Int, tags: Tags): ByteArray {
        val body = ByteArrayOutputStream()
        children(moov, header, moov.size) { type, start, end, _ ->
            if (type != "udta") body.write(moov, start, end - start)
        }
        body.write(udta(tags))
        val inner = body.toByteArray()
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(8 + inner.size)
        d.writeBytes("moov")
        d.write(inner)
        return out.toByteArray()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(8 + payload.size)
        d.write(type.toByteArray(Charsets.ISO_8859_1))
        d.write(payload)
        return out.toByteArray()
    }

    /** An ilst item: the atom name, then a "data" box with the well-known type. */
    private fun item(name: String, dataType: Int, value: ByteArray): ByteArray {
        val data = ByteArrayOutputStream()
        val d = DataOutputStream(data)
        d.writeInt(dataType)
        d.writeInt(0) // locale
        d.write(value)
        return box(name, box("data", data.toByteArray()))
    }

    private fun udta(t: Tags): ByteArray {
        val ilst = ByteArrayOutputStream()
        fun text(name: String, v: String) {
            if (v.isNotBlank()) ilst.write(item(name, 1, v.trim().toByteArray(Charsets.UTF_8)))
        }
        text("©nam", t.title)
        text("©ART", t.artist)
        text("aART", t.artist.split(',').first())
        text("©alb", t.album)
        text("©cmt", t.comment)
        text("©too", "Segno")
        t.cover?.let { ilst.write(item("covr", if (t.coverIsPng) 14 else 13, it)) }

        val hdlr = ByteArrayOutputStream().apply {
            val d = DataOutputStream(this)
            d.writeInt(0) // version and flags
            d.writeInt(0) // pre_defined
            d.writeBytes("mdir")
            d.writeBytes("appl")
            d.writeInt(0)
            d.writeInt(0)
            d.writeByte(0) // empty name
        }.toByteArray()

        val meta = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0)) // meta is a full box
            write(box("hdlr", hdlr))
            write(box("ilst", ilst.toByteArray()))
        }.toByteArray()
        return box("udta", box("meta", meta))
    }

    private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl", "edts", "dinf")

    /** Adds [delta] to chunk offsets (stco/co64) that point past the old moov at [moovAt]. */
    private fun patchChunkOffsets(b: ByteArray, from: Int, to: Int, moovAt: Long, delta: Long) {
        if (delta == 0L) return
        children(b, from, to) { type, start, end, header ->
            when (type) {
                in CONTAINERS -> patchChunkOffsets(b, start + header, end, moovAt, delta)
                "stco" -> {
                    val count = readU32(b, start + header + 4).toInt()
                    var p = start + header + 8
                    repeat(count) {
                        if (p + 4 <= end) {
                            val v = readU32(b, p)
                            if (v > moovAt) writeU32(b, p, v + delta)
                        }
                        p += 4
                    }
                }
                "co64" -> {
                    val count = readU32(b, start + header + 4).toInt()
                    var p = start + header + 8
                    repeat(count) {
                        if (p + 8 <= end) {
                            val v = readU64(b, p)
                            if (v > moovAt) writeU64(b, p, v + delta)
                        }
                        p += 8
                    }
                }
            }
        }
    }

    /** Fixes a fragment's absolute base offset (tfhd with base-data-offset), if it has one. */
    private fun patchFragment(b: ByteArray, from: Int, to: Int, moovAt: Long, delta: Long) {
        if (delta == 0L) return
        children(b, from, to) { type, start, end, header ->
            when (type) {
                "traf" -> patchFragment(b, start + header, end, moovAt, delta)
                "tfhd" -> {
                    val flags = readU32(b, start + header) and 0xFFFFFF
                    if (flags and 0x1L != 0L) {
                        val at = start + header + 8 // after version/flags and track_ID
                        if (at + 8 <= end) {
                            val v = readU64(b, at)
                            if (v > moovAt) writeU64(b, at, v + delta)
                        }
                    }
                }
            }
        }
    }

    /** True if [input] starts like an MP4/M4A file. */
    fun looksLikeMp4(input: InputStream): Boolean {
        val head = ByteArray(8)
        var n = 0
        while (n < 8) {
            val r = input.read(head, n, 8 - n)
            if (r < 0) break
            n += r
        }
        return n == 8 && typeAt(head, 4) == "ftyp"
    }
}
