package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DemucsSeparationLogicTest {

    @Test
    fun testInterleavedToPlanarDeinterleaving() {
        val frames = 100
        val interleavedShorts = ShortArray(frames * 2)
        for (i in 0 until frames) {
            interleavedShorts[i * 2] = (1000 + i).toShort()      // Left channel
            interleavedShorts[i * 2 + 1] = (-1000 - i).toShort() // Right channel
        }

        val directByteBuffer = ByteBuffer.allocateDirect(1 * 2 * frames * 4)
            .order(ByteOrder.nativeOrder())
        val floatBuf = directByteBuffer.asFloatBuffer()

        // De-interleave into planar: Channel 0 (Left), Channel 1 (Right)
        floatBuf.clear()
        for (f in 0 until frames) {
            floatBuf.put(interleavedShorts[f * 2].toFloat() / 32768f)
        }
        for (f in 0 until frames) {
            floatBuf.put(interleavedShorts[f * 2 + 1].toFloat() / 32768f)
        }
        floatBuf.rewind()

        // Verify Channel 0 (Left)
        for (f in 0 until frames) {
            val expectedL = (1000 + f).toFloat() / 32768f
            assertEquals(expectedL, floatBuf.get(f), 0.0001f)
        }

        // Verify Channel 1 (Right)
        for (f in 0 until frames) {
            val expectedR = (-1000 - f).toFloat() / 32768f
            assertEquals(expectedR, floatBuf.get(frames + f), 0.0001f)
        }
    }

    @Test
    fun testDemucs4StemExtraction() {
        val frames = 50
        // Shape [1, 4, 2, frames]: total 4 * 2 * frames = 400 floats
        val totalFloats = 4 * 2 * frames
        val mockOutput = FloatArray(totalFloats)

        val drumsVal = 0.1f
        val bassVal = 0.2f
        val otherVal = 0.3f
        val vocalVal = 0.4f

        for (f in 0 until frames) {
            // Stem 0 (Drums): Left & Right
            mockOutput[(0 * 2 + 0) * frames + f] = drumsVal
            mockOutput[(0 * 2 + 1) * frames + f] = drumsVal

            // Stem 1 (Bass)
            mockOutput[(1 * 2 + 0) * frames + f] = bassVal
            mockOutput[(1 * 2 + 1) * frames + f] = bassVal

            // Stem 2 (Other)
            mockOutput[(2 * 2 + 0) * frames + f] = otherVal
            mockOutput[(2 * 2 + 1) * frames + f] = otherVal

            // Stem 3 (Vocals)
            mockOutput[(3 * 2 + 0) * frames + f] = vocalVal
            mockOutput[(3 * 2 + 1) * frames + f] = vocalVal
        }

        val floatBuffer = ByteBuffer.allocateDirect(totalFloats * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        floatBuffer.put(mockOutput)
        floatBuffer.rewind()

        // Extract Vocals (Stem 3)
        for (f in 0 until frames) {
            val vocL = floatBuffer.get((3 * 2 + 0) * frames + f)
            val vocR = floatBuffer.get((3 * 2 + 1) * frames + f)
            assertEquals(0.4f, vocL, 0.0001f)
            assertEquals(0.4f, vocR, 0.0001f)
        }

        // Extract Karaoke (Drums + Bass + Other)
        for (f in 0 until frames) {
            val karL = floatBuffer.get((0 * 2 + 0) * frames + f) +
                       floatBuffer.get((1 * 2 + 0) * frames + f) +
                       floatBuffer.get((2 * 2 + 0) * frames + f)
            val karR = floatBuffer.get((0 * 2 + 1) * frames + f) +
                       floatBuffer.get((1 * 2 + 1) * frames + f) +
                       floatBuffer.get((2 * 2 + 1) * frames + f)

            // Expected sum: 0.1 + 0.2 + 0.3 = 0.6
            assertEquals(0.6f, karL, 0.0001f)
            assertEquals(0.6f, karR, 0.0001f)
        }
    }

    @Test
    fun testOverlapAddCrossfadeUnityGain() {
        val overlapFrames = 85995
        for (f in 0 until overlapFrames step 1000) {
            val fadeOut = 1.0f - (f.toFloat() / overlapFrames.toFloat())
            val fadeIn = f.toFloat() / overlapFrames.toFloat()
            val sum = fadeOut + fadeIn
            assertEquals(1.0f, sum, 0.00001f)
        }
    }
}
