package com.denia.jmreader

import java.security.MessageDigest

/**
 * Pure pixel math for page un-scrambling — no Android dependencies, so the
 * exact code that ships in the app can be exercised by the JVM verification
 * harness in jm-reader/verify-descramble/ against golden renders.
 *
 * Layout (verified against the reference implementation and by rendering
 * real pages): pages are stored cut into `num` horizontal bands in reverse
 * order; the `h % num` remainder rows ride with the stored image's BOTTOM
 * band, which lands on top after reversal. Getting the remainder band wrong
 * offsets every seam by `rem` rows — the "white lines" bug. Do not change
 * this math without re-running the harness.
 */
object DescrambleCore {
    /** aid = chapterId, stem = filename without extension. */
    fun splitNum(aid: Int, stem: String): Int {
        if (aid < 220980) return 0
        if (aid < 268850) return 10
        val x = if (aid < 421926) 10 else 8
        val md5 = MessageDigest.getInstance("MD5").digest("$aid$stem".toByteArray(Charsets.UTF_8))
        val hex = md5.joinToString("") { "%02x".format(it) }
        val ord = hex.last().code
        return (ord % x) * 2 + 2
    }

    /**
     * Rearrange row-major ARGB pixels of a scrambled page into the restored
     * image. Returns [src] unchanged when there is nothing to do.
     */
    fun descramblePixels(src: IntArray, w: Int, h: Int, num: Int): IntArray {
        if (num <= 1) return src
        val band = h / num
        val rem = h % num
        if (band == 0) return src
        val dst = IntArray(w * h)
        for (i in 0 until num) {
            var move = band
            val srcY = h - move * (i + 1) - rem
            var dstY = move * i
            if (i == 0) move += rem else dstY += rem
            System.arraycopy(src, srcY * w, dst, dstY * w, move * w)
        }
        return dst
    }
}
