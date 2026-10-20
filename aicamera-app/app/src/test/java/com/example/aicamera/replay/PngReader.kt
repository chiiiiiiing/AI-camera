package com.example.aicamera.replay

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * 最小 PNG 解码器（桌面回放台用）。
 *
 * Android 单测的编译 classpath 以 android.jar 为 bootclasspath，java.desktop
 * （javax.imageio / java.awt）不可用；这里用 java.base 的 Inflater 手写解码，
 * 只支持回放所需的常见格式：8-bit、RGB 或 RGBA、非隔行、标准 zlib 压缩。
 *
 * 输出与 Android Bitmap.getPixels 相同布局的 ARGB IntArray。
 */
object PngReader {

    data class Image(val width: Int, val height: Int, val rgba: IntArray)

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun read(file: File): Image {
        val bytes = file.readBytes()
        require(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "不是 PNG 文件: ${file.name}" }

        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        val idat = ByteArrayOutputStream()
        while (pos + 8 <= bytes.size) {
            val len = readInt(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val start = pos + 8
            when (type) {
                "IHDR" -> {
                    width = readInt(bytes, start)
                    height = readInt(bytes, start + 4)
                    bitDepth = bytes[start + 8].toInt() and 0xFF
                    colorType = bytes[start + 9].toInt() and 0xFF
                    interlace = bytes[start + 12].toInt() and 0xFF
                }
                "IDAT" -> idat.write(bytes, start, len)
                "IEND" -> break
            }
            pos = start + len + 4
        }
        require(bitDepth == 8 && (colorType == 2 || colorType == 6) && interlace == 0) {
            "仅支持 8-bit RGB/RGBA 非隔行 PNG: ${file.name}"
        }
        val channels = if (colorType == 2) 3 else 4
        val raw = inflate(idat.toByteArray())
        require(raw.size >= (width * channels + 1) * height) { "PNG 数据不完整: ${file.name}" }

        val stride = width * channels
        val output = IntArray(width * height)
        var previous = ByteArray(stride)
        val row = ByteArray(stride)
        var src = 0
        for (y in 0 until height) {
            val filter = raw[src].toInt() and 0xFF
            src++
            when (filter) {
                0 -> for (i in 0 until stride) row[i] = raw[src + i]
                1 -> for (i in 0 until stride) {
                    val a = if (i >= channels) row[i - channels].toInt() and 0xFF else 0
                    row[i] = ((raw[src + i].toInt() and 0xFF) + a).toByte()
                }
                2 -> for (i in 0 until stride) {
                    row[i] = ((raw[src + i].toInt() and 0xFF) + (previous[i].toInt() and 0xFF)).toByte()
                }
                3 -> for (i in 0 until stride) {
                    val a = if (i >= channels) row[i - channels].toInt() and 0xFF else 0
                    val b = previous[i].toInt() and 0xFF
                    row[i] = ((raw[src + i].toInt() and 0xFF) + ((a + b) ushr 1)).toByte()
                }
                4 -> for (i in 0 until stride) {
                    val a = if (i >= channels) row[i - channels].toInt() and 0xFF else 0
                    val b = previous[i].toInt() and 0xFF
                    val c = if (i >= channels) previous[i - channels].toInt() and 0xFF else 0
                    row[i] = ((raw[src + i].toInt() and 0xFF) + paeth(a, b, c)).toByte()
                }
                else -> throw IllegalArgumentException("未知 PNG 滤波类型 $filter: ${file.name}")
            }
            src += stride
            for (x in 0 until width) {
                val r = row[x * channels].toInt() and 0xFF
                val g = row[x * channels + 1].toInt() and 0xFF
                val b = row[x * channels + 2].toInt() and 0xFF
                val a = if (channels == 4) row[x * channels + 3].toInt() and 0xFF else 0xFF
                output[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            previous = row.copyOf()
        }
        return Image(width, height, output)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

    private fun inflate(compressed: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream(compressed.size * 4)
        val buffer = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            require(count > 0 || inflater.finished()) { "PNG zlib 解压失败" }
            out.write(buffer, 0, count)
        }
        inflater.end()
        return out.toByteArray()
    }
}
