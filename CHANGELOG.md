# Changelog

## [1.0.0](https://github.com/headlesshq/hmc-optimizations/compare/0.4.0...1.0.0) (2026-10-17)


### ⚠ BREAKING CHANGES

* 1.12.2 no longer replaces the display, framebuffer, font renderer, RenderGlobal and resource loading at startup. Like the newer versions it skips the per-frame work in Minecraft#runGameLoop (world and GUI rendering, framebuffer blit, display updates, GL error checks) while HmcOptimizations.isActive(), so the optimize command can turn optimizations off at runtime and the game can run with Xvfb. Screenshots are read synchronously on 1.12.2 (F2, HMC-Specifics), so right before ScreenShotHelper#createScreenshot reads the framebuffer a single frame is rendered without optimizations, the way runGameLoop would render it. The release jars bundle the hmc-optimizations-api, the HeadlessMc 2 API, the fps command and the old HeadlessForge code are gone. 1.12.2 now also runs the Xvfb screenshot test in CI.

### Features

* add Minecraft 26.2 support ([#8](https://github.com/headlesshq/hmc-optimizations/issues/8)) ([817a359](https://github.com/headlesshq/hmc-optimizations/commit/817a359917d81fc2b2e9a6634f8af8a4c8c2a951))
* moved to headlesshq ([#7](https://github.com/headlesshq/hmc-optimizations/issues/7)) ([a147436](https://github.com/headlesshq/hmc-optimizations/commit/a147436e04503298cf7c77d7167a266f3a3fc9bd))
* Release 1.0.0, screenshot support, hmc-specifics support ([#9](https://github.com/headlesshq/hmc-optimizations/issues/9)) ([7ad52bc](https://github.com/headlesshq/hmc-optimizations/commit/7ad52bca53ee6705045fb41eec1e83bb2a73c6d9))
