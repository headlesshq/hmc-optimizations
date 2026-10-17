<h1 align="center" style="font-weight: normal;"><b>HMC-Optimizations</b></h1>
<p align="center">Version specific optimizations for the HeadlessMc runtime.</p>
<p align="center"><img src="docs/logo.png" alt="logo" style="width:250px;"></p>
<p align="center"><a href="https://github.com/headlesshq/mc-runtime-test">Mc-Runtime-Test</a> | <a href="https://github.com/headlesshq/headlessmc">HMC</a> | <a href="https://github.com/headlesshq/hmc-specifics">HMC-Specifics</a> | HMC-Optimizations</p>

<div align="center">

[![GitHub All Releases](https://img.shields.io/github/downloads/headlesshq/hmc-optimizations/total.svg)](https://github.com/headlesshq/hmc-optimizations/releases)
![](https://github.com/headlesshq/hmc-optimizations/actions/workflows/lifecycle.yml/badge.svg)
![GitHub](https://img.shields.io/github/license/headlesshq/hmc-optimizations)
![Github last-commit](https://img.shields.io/github/last-commit/headlesshq/hmc-optimizations)

</div>

> [!WARNING]
> This mod skips large parts of the rendering pipeline of Minecraft.
> It makes changes that together with other mods can be unstable.

Version specific optimizations for [HeadlessMC](https://github.com/headlesshq/headlessmc).
The goal of HeadlessMC is to make the Minecraft client run on the command line, headless. It does so by
hooking into the LWJGL library skipping all the code. This also makes it largely
Minecraft version independent, as I only have to target the LWJGL library.

**However**, all the Minecraft code for rendering will still run;
it is just the calls to LWJGL that do not do anything.
Also, due to the inextensible nature of Javas Buffers, the LWJGL library patched by us still
needs to allocate buffers when Minecrafts code tells it to, currently on the heap instead of off.

The goal of this project is to write mods that target Minecraft code specifically, patching out
anything rendering related with [Mixin](https://github.com/SpongePowered/Mixin).
If you really want to bring your memory footprint down,
we also recommend that you configure HeadlessMc to use
dummy assets via the `hmc.assets.dummy` property.

HMC-Optimizations currently supports the following Minecraft versions and modloaders:

| Version        | Forge | Fabric | NeoForge |
|----------------|-------|--------|----------|
| 26.1 - 26.3    | ✅     | ✅      | ✅        |
| 1.21 - 1.21.11 | ✅     | ✅      | ✅        |
| 1.20.6         | ✅     | ✅      | ✅        |
| 1.19.4         | ✅     | ✅      | —        |
| 1.18.2         | ✅     | ✅      | —        |
| 1.17.1         | ✅     | ✅      | —        |
| 1.16.5         | ✅     | ✅      | —        |
| 1.12.2         | ✅     | ✅      | —        |
| 1.8.9          | ✅     | —      | —        |
| 1.7.10         | ✅     | —      | —        |

Download the jar for your version and modloader from
the [releases](https://github.com/headlesshq/hmc-optimizations/releases).
For every Minecraft version there is a `<mc>-latest` release, e.g. `1.21.4-latest`.

### Turning optimizations on and off

The optimizations are on by default. With [HMC-Specifics](https://github.com/headlesshq/hmc-specifics) 3
installed, the `optimize` command controls them at runtime:

| Command                 | Effect                                                                                |
|-------------------------|---------------------------------------------------------------------------------------|
| `optimize`              | Shows whether the optimizations are on.                                               |
| `optimize on`           | Turns the optimizations on.                                                           |
| `optimize off`          | Turns the optimizations off, the game renders normally.                               |
| `optimize toggle`       | Turns them off if they are on and on if they are off.                                 |
| `optimize --frames <n>` | Renders the next `n` frames without optimizations, then turns them back on by itself. |

HMC-Optimizations does not need HMC-Specifics, it only registers the command for it
(via `META-INF/hmc-specifics-commands`).
To start the game with the optimizations off, launch it with `-Dhmc.optimizations.enabled=false`.
On Minecraft versions that cannot turn the optimizations off at runtime, this property is the only way,
`optimize` tells you if that is the case.

### Screenshots

When the game is not truly headless, e.g. when running HeadlessMC with a real or
a virtual display like [Xvfb](https://www.x.org/releases/X11R7.6/doc/man/man1/Xvfb.1.xhtml)
instead of the LWJGL redirection, you can still take screenshots while the optimizations are on:
every screenshot (F2, the `screenshot` command of
HMC-Specifics, [HMC-Prometheus](https://github.com/headlesshq/hmc-prometheus), ...)
is delayed until a single frame has been rendered without optimizations.
Because chunks are only built on rendered frames, a single frame might not show all distant terrain yet,
use `optimize --frames 20` before taking a screenshot to give the world some frames to build.

Some optimizations would make rendering impossible, e.g. removing the light engine on the client.
Those are only applied when the game runs truly headless with the HeadlessMC LWJGL redirection.
You can override that detection with `-Dhmc.optimizations.headless=true|false`.

### Using the API from your own mod

The `hmc-optimizations-api` is a small, Minecraft version independent Java 8 library that is shaded into every
HMC-Optimizations jar. It is published to GitHub packages as `me.earth.headlessmc:hmc-optimizations-api`.
Because its classes are only present when HMC-Optimizations is installed, check for them before using them:

```java
import me.earth.headlessmc.optimizations.api.HmcOptimizations;

// HmcOptimizations.isActive()            - optimizations are on and the current frame is optimized
// HmcOptimizations.setEnabled(boolean)   - turn them on or off
// HmcOptimizations.requestUnoptimizedFrames(int)    - render the next n frames normally
// HmcOptimizations.runAfterUnoptimizedFrame(Runnable) - run something after a normally rendered frame
```

### Benchmarks

A headless SinglePlayer world on Minecraft 1.21.4 (Fabric, dummy assets), measured with the [benchmark](benchmark):

| Metric               | Without HMC-Optimizations | With HMC-Optimizations |
|----------------------|---------------------------|------------------------|
| Heap used            | 683 MB                    | 458 MB (-33%)          |
| Process memory (RSS) | 1,609 MB                  | 1,158 MB (-28%)        |
| GC pauses per minute | 458                       | 172 (-63%)             |

The [benchmark](benchmark) folder contains scripts that compare the performance and memory usage of the
game with and without HMC-Optimizations, look for memory leaks, and find the rendering code that still
costs time while the optimizations are on.

### Building

Every Minecraft version has its own directory in `versions` with its own Gradle build, e.g. `versions/1_21`.
Build one with `./gradlew build` in that directory, other versions of the same directory
are built by passing `-Pminecraft_version`, `-Plexforge_version` and `-Pneoforge_version`, see `ci-data.json`.
The jars ending in `-release.jar` in `build/libs` are the ones to use.
