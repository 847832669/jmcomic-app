# AndroidLiquidGlassView

Source: https://github.com/QmDeve/AndroidLiquidGlassView

Vendored core revision: `d4238c557e46271f29ec0e3d3ee211bcf58dc2e0` (1.1.0 API). The original Java components and `liquidglass_effect.agsl` are built as the `:liquidglass` library module. MIT license; see `LICENSE` and the copyright headers in each source file.

Local adaptations:

- Build with SDK 35 / Java 17 and AndroidX versions compatible with the app, without the upstream publication plugins. Replace Java 21 `Math.clamp` with equivalent min/max operations; remove the optional editor-only AGSL annotation.
- Use screen coordinates for sampling across activity and dialog windows.
- Cancel deferred component creation and parameter updates when detached; coalesce pending updates and guard null sources.
- Dispose replaced RenderNodes and guard removal of dead ViewTreeObserver listeners.
- Log shader initialization for device verification.

`QmGlassHost` hosts the real `LiquidGlassView` behind Compose content. A sibling ViewGroup exposes a crop of the recorded Compose backdrop via the library's `bind()` API. Foreground input and accessibility stay with Compose. Each library renderer records the bounds of its own surface rather than the entire screen.

The library provides refraction, dispersion, tint and GPU blur on API 33+. On older systems the app supplies a legible translucent surface; the removed Kyant0 shader / CPU renderer is no longer used. The app's existing background recording and navigation layout retain their earlier attribution in `third-party/Kyant0-AndroidLiquidGlass`.
