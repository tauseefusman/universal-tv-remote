package com.example.tvremote.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Minimal protobuf wire-format helpers (enough for the Android TV Remote v2 messages). */
object Pb {
    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    fun int(field: Int, v: Int): ByteArray = tag(field, 0) + varint(v.toLong())
    fun bytes(field: Int, b: ByteArray): ByteArray = tag(field, 2) + varint(b.size.toLong()) + b
    fun str(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
    fun msg(field: Int, vararg parts: ByteArray) = bytes(field, concat(*parts))
    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); parts.forEach { out.write(it) }; return out.toByteArray()
    }

    class Field(val number: Int, val wire: Int, val long: Long, val data: ByteArray?)

    fun parse(buf: ByteArray): List<Field> {
        val res = mutableListOf<Field>()
        var i = 0
        fun readVar(): Long {
            var shift = 0; var r = 0L
            while (true) {
                val b = buf[i++].toInt() and 0xFF
                r = r or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return r
                shift += 7
            }
        }
        while (i < buf.size) {
            val key = readVar().toInt()
            val num = key ushr 3
            when (val wire = key and 7) {
                0 -> res += Field(num, wire, readVar(), null)
                2 -> { val n = readVar().toInt(); res += Field(num, wire, 0, buf.copyOfRange(i, i + n)); i += n }
                1 -> { res += Field(num, wire, 0, buf.copyOfRange(i, i + 8)); i += 8 }
                5 -> { res += Field(num, wire, 0, buf.copyOfRange(i, i + 4)); i += 4 }
                else -> error("unsupported wire type $wire")
            }
        }
        return res
    }

    fun List<Field>.find(n: Int) = firstOrNull { it.number == n }

    /** Length-delimited framing used by Android TV Remote v2 on both ports. */
    fun writeFrame(out: OutputStream, payload: ByteArray) {
        out.write(varint(payload.size.toLong()) + payload); out.flush()
    }

    fun readFrame(input: InputStream): ByteArray {
        var shift = 0; var len = 0L
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("connection closed by TV")
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        val buf = ByteArray(len.toInt()); var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException("connection closed by TV")
            off += n
        }
        return buf
    }
}
