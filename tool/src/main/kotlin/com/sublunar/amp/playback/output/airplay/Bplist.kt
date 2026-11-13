package com.sublunar.amp.playback.output.airplay

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Apple's binary property lists, which AirPlay 2 sets a session up in:
 * dictionaries, arrays, strings, whole numbers, true/false, bytes and
 * decimals. Written as [Map]/[List]/[String]/[Long]/[Boolean]/[ByteArray]/
 * [Double]; read back the same way (whole numbers as Long).
 */
internal object Bplist {

    fun write(root: Any): ByteArray {
        val objects = ArrayList<Any>()
        fun flatten(value: Any): Int {
            val index = objects.size
            objects += value
            when (value) {
                is Map<*, *> -> {
                    val keys = value.keys.map { flatten(it as String) }
                    val values = value.values.map { flatten(requireNotNull(it)) }
                    objects[index] = Flat(0xD, keys + values, value.size)
                }
                is List<*> -> objects[index] = Flat(0xA, value.map { flatten(requireNotNull(it)) }, value.size)
            }
            return index
        }
        flatten(root)
        val refSize = if (objects.size < 256) 1 else 2
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(objects.size)
        for ((i, value) in objects.withIndex()) {
            offsets[i] = out.size()
            when (value) {
                is Flat -> {
                    marker(out, value.type, value.count)
                    for (ref in value.refs) uint(out, ref.toLong(), refSize)
                }
                is String -> {
                    val ascii = value.all { it.code < 0x80 }
                    if (ascii) {
                        marker(out, 0x5, value.length)
                        out.write(value.toByteArray(Charsets.US_ASCII))
                    } else {
                        marker(out, 0x6, value.length)
                        out.write(value.toByteArray(Charsets.UTF_16BE))
                    }
                }
                is Boolean -> out.write(if (value) 0x09 else 0x08)
                is Int, is Long -> {
                    val n = (value as Number).toLong()
                    if (n in 0..0xff) { out.write(0x10); uint(out, n, 1) }
                    else if (n in 0..0xffff) { out.write(0x11); uint(out, n, 2) }
                    else if (n in 0..0xffffffffL) { out.write(0x12); uint(out, n, 4) }
                    else { out.write(0x13); uint(out, n, 8) }
                }
                is Double -> { out.write(0x23); uint(out, java.lang.Double.doubleToRawLongBits(value), 8) }
                is ByteArray -> { marker(out, 0x4, value.size); out.write(value) }
                else -> throw IllegalArgumentException("no plist form for ${value::class.simpleName}")
            }
        }
        val tableAt = out.size()
        val offsetSize = if (tableAt < 256) 1 else if (tableAt < 65536) 2 else 4
        for (o in offsets) uint(out, o.toLong(), offsetSize)
        out.write(ByteArray(6))
        out.write(offsetSize)
        out.write(refSize)
        uint(out, objects.size.toLong(), 8)
        uint(out, 0, 8)
        uint(out, tableAt.toLong(), 8)
        return out.toByteArray()
    }

    fun read(bytes: ByteArray): Any? {
        if (bytes.size < 40 || String(bytes, 0, 8, Charsets.US_ASCII) != "bplist00") throw IOException("not a binary plist")
        val t = bytes.size - 32
        val offsetSize = bytes[t + 6].toInt() and 0xff
        val refSize = bytes[t + 7].toInt() and 0xff
        val count = uint(bytes, t + 8, 8).toInt()
        val top = uint(bytes, t + 16, 8).toInt()
        val tableAt = uint(bytes, t + 24, 8).toInt()
        if (count <= 0 || count > MAX_OBJECTS || tableAt + count * offsetSize > t) throw IOException("a broken plist")
        fun offset(i: Int) = uint(bytes, tableAt + i * offsetSize, offsetSize).toInt()

        fun parse(i: Int, depth: Int): Any? {
            if (i !in 0 until count || depth > MAX_DEPTH) throw IOException("a broken plist")
            var at = offset(i)
            val marker = bytes[at].toInt() and 0xff
            val type = marker shr 4
            var length = marker and 0xf
            at++
            if (length == 0xf && type in setOf(0x4, 0x5, 0x6, 0xA, 0xD)) {
                val size = 1 shl (bytes[at].toInt() and 0xf)
                length = uint(bytes, at + 1, size).toInt()
                at += 1 + size
            }
            return when (type) {
                0x0 -> when (marker) { 0x08 -> false; 0x09 -> true; else -> null }
                0x1 -> uint(bytes, at, 1 shl length)
                0x2 -> if (length == 3) java.lang.Double.longBitsToDouble(uint(bytes, at, 8))
                    else java.lang.Float.intBitsToFloat(uint(bytes, at, 4).toInt()).toDouble()
                0x4 -> bytes.copyOfRange(at, at + length)
                0x5 -> String(bytes, at, length, Charsets.US_ASCII)
                0x6 -> String(bytes, at, length * 2, Charsets.UTF_16BE)
                0x8 -> uint(bytes, at, length + 1)
                0xA -> List(length) { parse(uint(bytes, at + it * refSize, refSize).toInt(), depth + 1) }
                0xD -> LinkedHashMap<String, Any?>().apply {
                    for (k in 0 until length) {
                        val key = parse(uint(bytes, at + k * refSize, refSize).toInt(), depth + 1) as? String ?: continue
                        put(key, parse(uint(bytes, at + (length + k) * refSize, refSize).toInt(), depth + 1))
                    }
                }
                else -> null
            }
        }
        return parse(top, 0)
    }

    private class Flat(val type: Int, val refs: List<Int>, val count: Int)

    private fun marker(out: ByteArrayOutputStream, type: Int, count: Int) {
        if (count < 15) {
            out.write((type shl 4) or count)
        } else {
            out.write((type shl 4) or 0xf)
            if (count < 256) { out.write(0x10); uint(out, count.toLong(), 1) }
            else if (count < 65536) { out.write(0x11); uint(out, count.toLong(), 2) }
            else { out.write(0x12); uint(out, count.toLong(), 4) }
        }
    }

    private fun uint(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write((value ushr (8 * i)).toInt() and 0xff)
    }

    private fun uint(bytes: ByteArray, at: Int, size: Int): Long {
        if (at < 0 || at + size > bytes.size) throw IOException("a broken plist")
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (bytes[at + i].toLong() and 0xff)
        return v
    }

    private const val MAX_OBJECTS = 100_000
    private const val MAX_DEPTH = 32
}
