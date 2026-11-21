package com.giftap.overlay

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object GifEncoder {
    private val globalPalette: ByteArray = buildPalette()

    private val bayer4x4 = intArrayOf(
        -16, 0, -12, 4,
        8, -8, 12, -4,
        -10, 6, -14, 2,
        14, -2, 10, -6
    )

    private fun buildPalette(): ByteArray {
        val palette = ByteArray(256 * 3)
        var idx = 0
        for (r in 0..5) {
            for (g in 0..6) {
                for (b in 0..5) {
                    palette[idx++] = ((r * 255) / 5).toByte()
                    palette[idx++] = ((g * 255) / 6).toByte()
                    palette[idx++] = ((b * 255) / 5).toByte()
                }
            }
        }
        val greys = intArrayOf(42, 106, 170, 212)
        for (v in greys) {
            palette[idx++] = v.toByte()
            palette[idx++] = v.toByte()
            palette[idx++] = v.toByte()
        }
        return palette
    }

    fun encode(frames: List<Bitmap>, delayCs: Int = 10): ByteArray {
        if (frames.isEmpty()) return ByteArray(0)
        val width = frames[0].width
        val height = frames[0].height
        val out = ByteArrayOutputStream(frames.size * width * height / 3)

        // GIF89a Header + Logical Screen Descriptor
        out.write(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
        out.write(width and 0xff)
        out.write((width ushr 8) and 0xff)
        out.write(height and 0xff)
        out.write((height ushr 8) and 0xff)
        out.write(0xf7)
        out.write(0)
        out.write(0)
        out.write(globalPalette)

        // NETSCAPE2.0 Infinite Loop Application Extension
        out.write(
            byteArrayOf(
                0x21, 0xff.toByte(), 0x0b,
                0x4e, 0x45, 0x54, 0x53, 0x43, 0x41, 0x50, 0x45, 0x32, 0x2e, 0x30,
                0x03, 0x01, 0x00, 0x00, 0x00
            )
        )

        val safeDelay = delayCs.coerceIn(4, 300)
        val pixels = IntArray(width * height)

        for (bmp in frames) {
            val frame = if (bmp.width == width && bmp.height == height) {
                bmp
            } else {
                Bitmap.createScaledBitmap(bmp, width, height, true)
            }
            frame.getPixels(pixels, 0, width, 0, 0, width, height)

            // Graphic Control Extension
            out.write(
                byteArrayOf(
                    0x21, 0xf9.toByte(), 0x04, 0x04,
                    (safeDelay and 0xff).toByte(),
                    ((safeDelay ushr 8) and 0xff).toByte(),
                    0x00, 0x00
                )
            )

            // Image Descriptor
            out.write(
                byteArrayOf(
                    0x2c,
                    0x00, 0x00, 0x00, 0x00,
                    (width and 0xff).toByte(), ((width ushr 8) and 0xff).toByte(),
                    (height and 0xff).toByte(), ((height ushr 8) and 0xff).toByte(),
                    0x00
                )
            )

            val indices = quantize(pixels, width, height)
            writeLzwImageData(out, indices)

            if (frame !== bmp) {
                frame.recycle()
            }
        }

        out.write(0x3b)
        return out.toByteArray()
    }

    private fun quantize(pixels: IntArray, width: Int, height: Int): ByteArray {
        val indices = ByteArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            val bayerRow = (y and 3) shl 2
            for (x in 0 until width) {
                val c = pixels[rowOffset + x]
                val d = bayer4x4[bayerRow + (x and 3)]
                val r = (((c ushr 16) and 0xff) + d).coerceIn(0, 255)
                val g = (((c ushr 8) and 0xff) + d).coerceIn(0, 255)
                val b = ((c and 0xff) + d).coerceIn(0, 255)
                val ri = (r * 5 + 127) / 255
                val gi = (g * 6 + 127) / 255
                val bi = (b * 5 + 127) / 255
                indices[rowOffset + x] = (ri * 42 + gi * 6 + bi).toByte()
            }
        }
        return indices
    }

    private fun writeLzwImageData(out: ByteArrayOutputStream, indices: ByteArray) {
        val minCodeSize = 8
        val clearCode = 256
        val eoiCode = 257

        val chunks = ByteArrayOutputStream(indices.size)
        var bitBuffer = 0
        var bitCount = 0
        var codeSize = minCodeSize + 1
        var nextCode = 258

        val tableSize = 5003
        val keys = IntArray(tableSize) { -1 }
        val vals = ShortArray(tableSize)

        fun resetTable() {
            keys.fill(-1)
            codeSize = minCodeSize + 1
            nextCode = 258
        }

        fun writeCode(code: Int) {
            bitBuffer = bitBuffer or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                chunks.write(bitBuffer and 0xff)
                bitBuffer = bitBuffer ushr 8
                bitCount -= 8
            }
        }

        writeCode(clearCode)
        var prefix = indices[0].toInt() and 0xff

        for (i in 1 until indices.size) {
            val suffix = indices[i].toInt() and 0xff
            val key = (prefix shl 8) or suffix
            var hash = ((key ushr 4) xor key) % tableSize
            if (hash < 0) hash += tableSize

            var found = -1
            while (keys[hash] != -1) {
                if (keys[hash] == key) {
                    found = vals[hash].toInt()
                    break
                }
                hash = (hash + 1) % tableSize
            }

            if (found != -1) {
                prefix = found
            } else {
                writeCode(prefix)
                if (nextCode < 4096) {
                    keys[hash] = key
                    vals[hash] = nextCode.toShort()
                    if (nextCode == (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                    nextCode++
                } else {
                    writeCode(clearCode)
                    resetTable()
                }
                prefix = suffix
            }
        }

        writeCode(prefix)
        writeCode(eoiCode)
        if (bitCount > 0) {
            chunks.write(bitBuffer and 0xff)
        }

        val raw = chunks.toByteArray()
        out.write(minCodeSize)
        var offset = 0
        while (offset < raw.size) {
            val len = minOf(255, raw.size - offset)
            out.write(len)
            out.write(raw, offset, len)
            offset += len
        }
        out.write(0)
    }
}
