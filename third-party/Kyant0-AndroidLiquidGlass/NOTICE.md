# Kyant0 / AndroidLiquidGlass (Backdrop)

Historical attribution: the current app renders glass with QmDeve/AndroidLiquidGlassView (`android/liquidglass`). The earlier Kyant0 AGSL and CPU renderers have been removed. This notice remains because the app's Compose backdrop recording and navigation layout were adapted during that earlier implementation. The following describes that original adaptation.

Source: https://github.com/Kyant0/AndroidLiquidGlass

Adapted revision: `eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5`.

Copyright 2025 Kyant. Licensed under Apache-2.0; the full license is included in `LICENSE`.

`GlassShaders.kt` retains the rounded rectangle SDF, gradient, circle-map refraction and seven-sample dispersion shaders from `backdrop/.../internal/Shaders.kt`. Its package and annotations have been changed for this application.

`LiquidGlass.kt` adapts the layer recording / backdrop coordinate approach and 64dp / 56dp capsule layout from `LayerBackdrop`, `LayerBackdropModifier` and `LiquidBottomTabs`. It implements an application-specific renderer compatible with the existing Compose 1.8 stack instead of depending on the upstream Kotlin 2.4 / Compose 1.12 artifact. The software renderer translates the same SDF equations to Kotlin with separable blur and a cheaper three-channel dispersion approximation for Android 8–11.

API 33+: upstream AGSL lens and dispersion, chained with GPU blur.
API 31–32: GPU blur and reflective material; AGSL lens is unavailable.
API 26–30: bounded backdrop snapshots, CPU blur and SDF refraction.

Page artwork is never included in the foreground effect. Glass panels sample the wallpaper branch; the floating navigation samples the content branch, preventing recursive sampling of itself.
