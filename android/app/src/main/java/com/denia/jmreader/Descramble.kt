package com.denia.jmreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.security.MessageDigest

// Page un-scramble — verified numerically against the reference implementation.
// aid = chapterId, stem = filename without extension:
//   aid<220980 -> 0; aid<268850 -> 10; else x = 10 (aid<421926) or 8; num = (ord(last char of md5("aid+stem")) % x)*2+2
// Bands are stored in reverse order; remainder rows join the bottom band.
object Descramble {
    fun splitNum(aid: Int, stem: String): Int {
        if (aid < 220980) return 0
        if (aid < 268850) return 10
        val x = if (aid < 421926) 10 else 8
        val md5 = MessageDigest.getInstance("MD5").digest("$aid$stem".toByteArray(Charsets.UTF_8))
        val hex = md5.joinToString("") { "%02x".format(it) }
        val ord = hex.last().code
        return (ord % x) * 2 + 2
    }

    fun decode(bytes: ByteArray, chapterId: String, fileName: String): Bitmap {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("图片解码失败")
        val stem = fileName.substringBeforeLast('.')
        val aid = chapterId.toIntOrNull() ?: return bmp
        val num = splitNum(aid, stem)
        if (num <= 1) return bmp
        val w = bmp.width
        val h = bmp.height
        val band = h / num
        val rem = h % num
        if (band == 0) return bmp
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        // source bands in stored order: bands of heights [band+rem (top, = original bottom), band, band, ...]
        // original layout: num bands each `band` tall, bottom one takes +rem.
        val heightsSrc = IntArray(num) { band }
        heightsSrc[0] = band + rem
        val heightsDst = IntArray(num) { band }
        heightsDst[num - 1] = band + rem
        // mapping: stored band k corresponds to original band (num-1-k)
        var srcY = 0
        for (k in 0 until num) {
            val dstBand = num - 1 - k
            var dstY = 0
            for (b in 0 until dstBand) dstY += heightsDst[b]
            val hh = heightsSrc[k]
            System.arraycopy(src, srcY * w, dst, dstY * w, hh * w)
            srcY += hh
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
