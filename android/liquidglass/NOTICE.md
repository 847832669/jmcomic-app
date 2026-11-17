# AndroidLiquidGlassView

Source: https://github.com/QmDeve/AndroidLiquidGlassView

Vendored core revision: `d4238c557e46271f29ec0e3d3ee211bcf58dc2e0` (1.1.0 API). The original Java components and `liquidglass_effect.agsl` are built as the `:liquidglass` library module. MIT license; see `LICENSE` and the copyright headers in each source file.

Local adaptations:

- Build with SDK 35 / Java 17 and AndroidX versions compatible with the app, without the upstream publication plugins. Replace Java 21 `Math.clamp` with equivalent min/max operations; remove the optional editor-only AGSL annotation.
- Use screen coordinates for sampling across activity and dialog windows.
- Cancel deferred component creation and parameter updates when detached; coalesce pending updates and guard null sources.
- Dispose replaced RenderNodes and guard removal of dead ViewTreeObserver listeners.
- Log shader initialization for device verification.
- Reuse the existing renderer on component size changes; the child updates its RenderNode bounds and parameters, avoiding shader recreation during capsule size animation. Optical parameters and AGSL remain unchanged.

Only the floating bottom bar and its moving selection lens opt into the native library component. All other surfaces and controls use Material theme styling and allocate no glass renderer. The bar uses 20dp refraction height, 70dp offset and 0.5 dispersion; the selection lens uses the parameters below. The bar uses 12dp blur (capped by the library at 50px) and 0.5 theme tint; the lens retains 0.01px blur. Optical parameters use the public library API, with no separate navigation shader.

`QmGlassHost` hosts the real `LiquidGlassView` behind Compose content. A sibling ViewGroup exposes a crop of the recorded Compose backdrop via the library's `bind()` API. Foreground input and accessibility stay with Compose. Each library renderer records the bounds of its own surface rather than the entire screen.

The navigation lens uses zero tint and a recorded branch containing only the page and the library-rendered frosted bar. A single foreground row draws all four icons and labels at fixed tab positions, excluding them from optical sampling in every gesture state. The capsule moves behind this row. The complete bar uses a layout offset shared by the native View, recorded source and foreground; no draw-only translation or separately scaled/crossfaded icon copies are used. The 64dp bar contains a 56dp resting lens with a 12dp refraction height, 20dp offset and 0.15 dispersion, respecting the library minimums. Pressing grows the lens to 72dp and 18% wider, with 45dp offset and 1.0 dispersion. The entire native material stays at full opacity with zero tint, sampling the frosted bar instead of revealing a sharp patch of page content. The source extends 28dp around the bar. All optical parameters use the public library API; the AGSL remains unchanged.

The library provides refraction, dispersion, tint and GPU blur on API 33+. On older systems the app supplies a legible translucent surface; the removed Kyant0 shader / CPU renderer is no longer used. The app's existing background recording and navigation layout retain their earlier attribution in `third-party/Kyant0-AndroidLiquidGlass`.
