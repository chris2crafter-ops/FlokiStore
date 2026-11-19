package io.github.chris2crafter.thorstore.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Inflater

interface RangeSource {
    /** Total size; valid after the first [tail] call. */
    val length: Long

    /** Last [n] bytes of the file (fewer if the file is smaller). Sets [length]. */
    fun tail(n: Int): ByteArray

    fun read(offset: Long, size: Int): ByteArray
}

class ByteArrayRangeSource(private val bytes: ByteArray) : RangeSource {
    override val length: Long get() = bytes.size.toLong()
    override fun tail(n: Int): ByteArray = bytes.copyOfRange(maxOf(0, bytes.size - n), bytes.size)
    override fun read(offset: Long, size: Int): ByteArray =
        bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + size))
}

/**
 * Reads single entries out of a zip (an APK) without fetching the whole file:
 * one request for the end of central directory, one for the directory, one per entry.
 */
class RemoteZip(private val src: RangeSource) {

    class Entry(val name: String, val method: Int, val compressedSize: Long, val size: Long, val localOffset: Long)

    val entries: Map<String, Entry>

    /** Start of the central directory; the APK signing block sits right before it. */
    val centralDirOffset: Long

    init {
        val tail = src.tail(TAIL)
        val tailStart = src.length - tail.size
        var eocd = -1
        for (i in tail.size - 22 downTo 0) {
            if (Bytes.i32(tail, i) == 0x06054b50) { eocd = i; break }
        }
        if (eocd < 0) throw IOException("Keine ZIP-Datei")
        var cdSize = Bytes.u32(tail, eocd + 12)
        var cdOffset = Bytes.u32(tail, eocd + 16)
        if (cdOffset == 0xFFFFFFFFL && eocd >= 20 && Bytes.i32(tail, eocd - 20) == 0x07064b50) {
            val z64 = Bytes.i64(tail, eocd - 20 + 8)
            val rec = if (z64 >= tailStart) tail.copyOfRange((z64 - tailStart).toInt(), (z64 - tailStart).toInt() + 56) else src.read(z64, 56)
            cdSize = Bytes.i64(rec, 40)
            cdOffset = Bytes.i64(rec, 48)
        }
        centralDirOffset = cdOffset
        val cd = if (cdOffset >= tailStart) {
            tail.copyOfRange((cdOffset - tailStart).toInt(), (cdOffset - tailStart + cdSize).toInt())
        } else {
            src.read(cdOffset, cdSize.toInt())
        }
        val map = LinkedHashMap<String, Entry>()
        var p = 0
        while (p + 46 <= cd.size && Bytes.i32(cd, p) == 0x02014b50) {
            val method = Bytes.u16(cd, p + 10)
            var comp = Bytes.u32(cd, p + 20)
            var size = Bytes.u32(cd, p + 24)
            val nameLen = Bytes.u16(cd, p + 28)
            val extraLen = Bytes.u16(cd, p + 30)
            val commentLen = Bytes.u16(cd, p + 32)
            var local = Bytes.u32(cd, p + 42)
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            // zip64 extra field carries the real values for 0xFFFFFFFF placeholders
            var e = p + 46 + nameLen
            val extraEnd = e + extraLen
            while (e + 4 <= extraEnd) {
                val id = Bytes.u16(cd, e)
                val len = Bytes.u16(cd, e + 2)
                if (id == 1) {
                    var q = e + 4
                    if (size == 0xFFFFFFFFL) { size = Bytes.i64(cd, q); q += 8 }
                    if (comp == 0xFFFFFFFFL) { comp = Bytes.i64(cd, q); q += 8 }
                    if (local == 0xFFFFFFFFL) { local = Bytes.i64(cd, q) }
                }
                e += 4 + len
            }
            map[name] = Entry(name, method, comp, size, local)
            p = extraEnd + commentLen
        }
        entries = map
    }

    /**
     * SHA-256 of the signing certificate from the APK Signature Scheme v3.1/v3/v2 block, the same
     * value Android compares on updates. Null for v1-only APKs.
     */
    fun signerCertSha256(): String? {
        if (centralDirOffset < 32) return null
        val footer = src.read(centralDirOffset - 24, 24)
        if (footer.size < 24 || String(footer, 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return null
        val size = Bytes.i64(footer, 0)
        if (size <= 24 || size > (16L shl 20)) return null
        val block = src.read(centralDirOffset - size - 8, (size + 8).toInt())
        val values = HashMap<Int, ByteArray>()
        var p = 8
        val end = block.size - 24
        while (p + 12 <= end) {
            val len = Bytes.i64(block, p)
            if (len < 4 || p + 8 + len > end) break
            values[Bytes.i32(block, p + 8)] = block.copyOfRange(p + 12, (p + 8 + len).toInt())
            p = (p + 8 + len).toInt()
        }
        // Android 13 uses v3.1 when present, then v3, then v2
        val v = values[0x1b93ad61] ?: values[0xf05368c0.toInt()] ?: values[0x7109871a] ?: return null
        // signers[0].signedData: digests (skipped), then certificates[0]
        var q = 4 + 4 + 4
        q += 4 + Bytes.i32(v, q)
        q += 4
        val certLen = Bytes.i32(v, q)
        q += 4
        if (certLen <= 0 || q + certLen > v.size) return null
        val der = v.copyOfRange(q, q + certLen)
        return java.security.MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
    }

    fun read(name: String, maxSize: Long = 64L shl 20): ByteArray? {
        val e = entries[name] ?: return null
        if (e.size > maxSize) throw IOException("$name ist zu groß (${e.size} Bytes)")
        // local header + some slack for its extra field, all in one request
        val slack = 30 + name.toByteArray().size + 1024
        val chunk = src.read(e.localOffset, (slack + e.compressedSize).toInt())
        if (Bytes.i32(chunk, 0) != 0x04034b50) throw IOException("Kaputter lokaler Header für $name")
        val start = 30 + Bytes.u16(chunk, 26) + Bytes.u16(chunk, 28)
        val data = if (start + e.compressedSize <= chunk.size) {
            chunk.copyOfRange(start, start + e.compressedSize.toInt())
        } else {
            src.read(e.localOffset + start, e.compressedSize.toInt())
        }
        return when (e.method) {
            0 -> data
            8 -> inflate(data, e.size)
            else -> throw IOException("Unbekannte Kompression ${e.method} für $name")
        }
    }

    private fun inflate(data: ByteArray, size: Long): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(size.toInt().coerceAtLeast(64))
            val buf = ByteArray(65536)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            inf.end()
        }
    }

    companion object {
        private const val TAIL = 65557 + 1024
    }
}

internal object Bytes {
    fun u8(b: ByteArray, p: Int) = b[p].toInt() and 0xff
    fun u16(b: ByteArray, p: Int) = u8(b, p) or (u8(b, p + 1) shl 8)
    fun i32(b: ByteArray, p: Int) = u16(b, p) or (u16(b, p + 2) shl 16)
    fun u32(b: ByteArray, p: Int) = i32(b, p).toLong() and 0xFFFFFFFFL
    fun i64(b: ByteArray, p: Int) = u32(b, p) or (u32(b, p + 4) shl 32)
}
