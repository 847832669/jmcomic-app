package com.denia.jmreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory

// Page un-scramble. All layout math lives in DescrambleCore (pure Kotlin,
// covered by the golden-render verification harness — see
// jm-reader/verify-descramble/). This wrapper only handles Bitmap IO.
object Descramble {
    fun splitNum(aid: Int, stem: String): Int = DescrambleCore.splitNum(aid, stem)

    fun decode(bytes: ByteArray, chapterId: String, fileName: String): Bitmap {
        // inScaled = false: the decoder must return the stored pixels 1:1.
        // Density scaling would hand the un-scrambler a resized bitmap whose
        // band math no longer matches the stored layout on some devices.
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalStateException("图片解码失败")
        val stem = fileName.substringBeforeLast('.')
        val aid = chapterId.toIntOrNull() ?: return bmp
        val num = DescrambleCore.splitNum(aid, stem)
        if (num <= 1) return bmp
        val w = bmp.width
        val h = bmp.height
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val dst = DescrambleCore.descramblePixels(src, w, h, num)
        if (dst === src) return bmp
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
