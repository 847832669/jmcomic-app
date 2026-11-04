#!/bin/bash
# Golden-render verification for the JM Reader Android page un-scrambler.
#
# Compiles the app's REAL DescrambleCore.kt (from the repo sources — not a
# copy of the math) together with VerifyMain.kt, runs it against the sample
# pages in png/, and compares the restored pixels with golden.tsv (hashes
# produced by an independent Python/PIL render that was visually inspected).
#
# Run this BEFORE shipping any Android build that touches Descramble,
# DescrambleCore, the reader, or the image pipeline. Exit 0 = safe.
set -e
cd "$(dirname "$0")"
JAVA=~/workspace/tools/jdk17/bin/java
CORE=~/workspace/jm-reader/repo/android/app/src/main/java/com/denia/jmreader/DescrambleCore.kt
STDLIB=$(find /home/hatch/.gradle/caches -name 'kotlin-stdlib-2.0.20.jar' | head -1)
GLIB="$HOME/workspace/tools/gradle/lib/*"
"$JAVA" -cp "$GLIB" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -cp "$STDLIB" -d verify.jar "$CORE" VerifyMain.kt 2>/dev/null
"$JAVA" -cp "verify.jar:$STDLIB" VerifyMainKt golden.tsv png
