package com.denia.jmreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.security.MessageDigest

// Page un-scramble — verified numerically against the reference implementation.
// aid = chapterId, stem = filename without extension:
//   aid<220980 -> 0; aid<268850 -> 10; else x = 10 (aid<421926) or 8; num = (ord(last char of md5("aid+stem")) % x)*2+2
// Bands are stored in reverse order; the remainder rows ride with the stored
// image's bottom band, which lands on top after reversal (iOS/jmcomic layout).
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
        // Mirror of the verified iOS/reference implementation: destination
        // band i (top-down) is copied from the stored image's i-th band
        // counting from the bottom; the first destination band also takes
        // the `rem` remainder rows from the stored image's tall bottom band.
        for (i in 0 until num) {
            var move = band
            val srcY = h - move * (i + 1) - rem
            var dstY = move * i
            if (i == 0) move += rem else dstY += rem
            System.arraycopy(src, srcY * w, dst, dstY * w, move * w)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
