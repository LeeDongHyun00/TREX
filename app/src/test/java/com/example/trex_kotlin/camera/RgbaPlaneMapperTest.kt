package com.example.trex_kotlin.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RgbaPlaneMapperTest {
    @Test
    fun visiblePixelsAreCopiedWithoutRowPaddingOrChangingTheSourcePosition() {
        val bytes = byteArrayOf(
            99,
            1, 2, 3, 4, 5, 6, 7, 8, 90, 91, 92, 93,
            9, 10, 11, 12, 13, 14, 15, 16, 94, 95, 96, 97,
        )
        val source = ByteBuffer.wrap(bytes).apply { position(1) }

        val compact = copyVisibleRgbaRows(
            sourceBuffer = source,
            width = 2,
            height = 2,
            rowStride = 12,
            pixelStride = 4,
        )

        assertArrayEquals((1..16).map(Int::toByte).toByteArray(), compact)
        assertEquals(1, source.position())
    }

    @Test
    fun unsupportedStrideOrTruncatedPlaneFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            copyVisibleRgbaRows(ByteBuffer.allocate(16), 2, 2, rowStride = 8, pixelStride = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyVisibleRgbaRows(ByteBuffer.allocate(16), 3, 1, rowStride = 8, pixelStride = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyVisibleRgbaRows(ByteBuffer.allocate(15), 2, 2, rowStride = 8, pixelStride = 4)
        }
    }
}
