# Descramble verification harness (JM Reader Android)

Why this exists: JM stores reader pages cut into horizontal bands in reverse
order, with the `height % num` remainder rows riding on the stored image's
bottom band. An implementation that puts the remainder on the wrong band
still produces a readable page, but every seam is offset by `rem` rows —
the recurring "white lines" bug (hit the old Android build and the web
version). Eyeballing one page is not a reliable gate, so the fix is pinned
down by this harness instead.

How it works:
- `samples/` holds real page files fetched from JM's image CDN
  (`https://cdn-msp.jmapiproxy1.cc/media/photos/<chapterId>/<file>`),
  covering every band-count branch of `DescrambleCore.splitNum`:
  - 96580_00002.webp — aid < 220980, num = 0 (unscrambled)
    sha256 f9d236364e1c2bf720e7fdb0e193f8bb727bd99a76fbe9a26430f4e6fce3b23e
  - 246530_00002.webp — 220980..268850, num = 10 flat
    sha256 46e898d565ebb3fbcffb15730b1295cd2d000ac29b1a42cec95896f05492db35
  - 362009_00002.webp — 268850..421926, formula with x = 10
    sha256 392e993ed204c128ed106696f379abd285f3a2820f6af522d14c82d60caf7476
  - 1191299_00001.webp / 1191299_00002.webp — aid >= 421926, formula x = 8
    (1191299_00002 is the exact page from the 2026-11-04 white-line report)
    sha256 524bfc2b1babbd49a0f5a939e7b8171b758bb60ddd8cda3eb2dae1f568d7cb34
    sha256 31ffb25728aa1059a8943c519186e499d76b2957c2c44e7aeeeb6c27d939efc2
- `png/` is each sample losslessly converted to PNG (for the JVM harness).
- `golden/` + `golden.tsv` are reference renders produced by an independent
  Python/PIL implementation of the layout, visually inspected seam-by-seam;
  golden.tsv stores the SHA-256 of each restored image's RGB pixels. A seam
  metric backs this up: correct renders score ~9–23 mean abs row diff at
  seams (interior median ~6–19), the wrong layout scores 61–84.
- `verify.sh` compiles the app's actual `DescrambleCore.kt` with
  `VerifyMain.kt` and checks its output against golden.tsv.

Rule: run `./verify.sh` before shipping any Android build that touches the
descramble code, reader, or image loading. ALL PASS or it doesn't ship.
If JM ever changes the server-side layout, re-fetch the samples, re-render
the goldens with PIL, eyeball them, and regenerate golden.tsv.
