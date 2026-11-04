import com.denia.jmreader.DescrambleCore
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Golden-render verification for the page un-scrambler.
 *
 * Reads golden.tsv (file, chapterId, stem, expectedSha256 — one per line,
 * tab-separated), feeds each sample PNG's pixels through the app's REAL
 * DescrambleCore (compiled from the app sources, not a copy of the math),
 * and compares the SHA-256 of the restored RGB pixels with the golden hash
 * produced by an independent Python/PIL render that was visually inspected.
 *
 * Exit code 0 = every sample restored pixel-perfectly.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: VerifyMainKt <golden.tsv> <pngDir>" }
    var failures = 0
    File(args[0]).readLines().filter { it.isNotBlank() }.forEach { line ->
        val p = line.split("\t")
        val img = ImageIO.read(File(args[1], p[0]))
        val w = img.width
        val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        val num = DescrambleCore.splitNum(p[1].toInt(), p[2])
        val out = DescrambleCore.descramblePixels(px, w, h, num)
        val buf = ByteArray(out.size * 3)
        var j = 0
        for (v in out) {
            buf[j++] = (v shr 16).toByte()
            buf[j++] = (v shr 8).toByte()
            buf[j++] = v.toByte()
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(buf)
            .joinToString("") { "%02x".format(it) }
        val ok = sha == p[3]
        if (!ok) failures++
        println("${p[0]}  num=$num  ${if (ok) "PASS" else "FAIL expected=${p[3]} got=$sha"}")
    }
    if (failures > 0) {
        println("RESULT: $failures FAILURE(S)")
        kotlin.system.exitProcess(1)
    }
    println("RESULT: ALL PASS")
}
