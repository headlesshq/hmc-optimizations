# hmc-optimizations benchmarks

`bench.py` measures Minecraft running headless with HeadlessMc, with and without hmc-optimizations. It covers three things:

1. **compare**: performance and memory (FPS, MSPT, heap, metaspace, NMT, RSS, GC) of three scenarios:
   `vanilla` (no mod), `optimized` (mod active) and `optimize-off` (mod installed, but switched off with the `optimize` command after joining).
2. **leak**: memory leak hunt with `jcmd GC.class_histogram` over time, heap dumps analysed by Eclipse MAT (leak suspects) and JFR `OldObjectSample`.
3. **render**: JFR execution and allocation samples of the optimized client, attributed to render path code and ranked.

Only the Python 3 standard library is used. Everything is written to `benchmark/work/<loader>-<mc>/` (git ignored). Dummy assets are used, so no Mojang account or asset download is needed.

## Requirements

- Linux, Python 3.8+.
- A JDK to run Minecraft with (`--java-home`, must contain `jcmd` and `jfr`; Java 21 for 1.21.x, Java 17 for 1.18-1.20.4, ...).
- Java 25+ for HeadlessMc itself (`--hmc-java-home`; defaults to `--java-home`).
- Internet access for `setup` (HeadlessMc, HMC-Specifics, Eclipse MAT, Minecraft).
- About 2.5 GB RAM per run (`--xmx 2G`) and about 3 GB disk per heap dump set. One Minecraft instance runs at a time.

## Setup

```sh
python3 -I benchmark/bench.py setup --mc 1.21.4 --loader fabric --java-home ~/.jdks/temurin-21.0.9 \
    --hmc-java-home ~/.jdks/temurin-25.0.1 --build --mat
python3 -I benchmark/bench.py world --mc 1.21.4 --loader fabric --java-home ~/.jdks/temurin-21.0.9 --hmc-java-home ~/.jdks/temurin-25.0.1
```

`setup` downloads the tools, builds (`--build`) or takes (`--jar`) the release jar from `hmc-optimizations-<mc>`, and installs Minecraft. `--mat` also downloads Eclipse MAT. `world` creates the template world that every run copies and joins.

## Running

All commands take `--mc`, `--loader`, `--java-home`, `--hmc-java-home`, `--xmx` and `--xvfb` (render with Xvfb instead of HeadlessMc headless mode).

```sh
COMMON="--mc 1.21.4 --loader fabric --java-home ~/.jdks/temurin-21.0.9 --hmc-java-home ~/.jdks/temurin-25.0.1"

# performance and memory, vanilla vs optimized vs mod-disabled
python3 -I benchmark/bench.py compare $COMMON --scenarios vanilla,optimized,optimize-off --reps 3 --duration 300

# leaks (histogram every 5 min, heap dumps at start and end, MAT, JFR OldObjectSample)
python3 -I benchmark/bench.py leak $COMMON --duration 3600 --heap-dumps --move-every 30

# render hotspots with the optimizations on
python3 -I benchmark/bench.py render $COMMON --duration 180

# redo reports
python3 -I benchmark/bench.py analyze $COMMON <render.jfr>
python3 -I benchmark/bench.py summarize <compare result dir>
```

Useful options: `--warmup` seconds in the world before measuring, `--interval` sampling period, `--nmt-interval`, `--move-every N` (teleports the player every N seconds so chunks keep loading), `--attempts` (retries a run when the game fails to start), `--scenario` for leak/render.

Results go to `benchmark/work/<loader>-<mc>/results/<mode>-<scenario>-<timestamp>/` with `summary.md` / `leak-report.md` / the render report, `samples.csv`, `gc.log`, JFR files and heap dumps.

`compare` and `summarize` also render SVG charts into `charts/` next to `summary.md` and embed them in it: bar charts of
the mean FPS, heap, RSS, native memory and GC per scenario, and line charts of heap and RSS over the measured time.
`leak` renders the heap and RSS of its run over time into `charts/memory.svg`.

## What the metrics mean

| Metric | Meaning |
|---|---|
| FPS / Client MSPT | Frames per second of the client loop, and average client tick time. Vsync is off and the FPS limit is raised, so scenarios are not capped equally. |
| Heap used / committed | Java heap in use / reserved from the OS, from `jcmd GC.heap_info`. |
| Heap after GC | Live heap occupancy after each GC pause (from the GC log). The best indicator of what is really retained. |
| Metaspace (NMT class) | Class metadata, from Native Memory Tracking (`jcmd VM.native_memory summary`). |
| NMT committed total / heap / threads / code cache / GC / other | Committed native memory per JVM area. "other" includes direct buffers. |
| RSS | Resident set size of the whole process tree (`/proc`), includes things NMT cannot see (native libraries). |
| GC pauses per minute / pause time | From the GC log in the measured window. |

Per metric mean, median and p95 are reported across all samples of all repetitions of a scenario, plus the relative difference to vanilla (negative is better for memory/GC, positive for FPS).

## Reading the leak report

`leak-report.md` has:

- **Memory over time**: heap, RSS, NMT at start and end. A leak is memory that keeps rising *after* the world has settled. A rise during the first minutes is expected (chunk loading, JIT, caches).
- **Live heap after full GC** per histogram. Compare the last values: a plateau means no leak, a steady slope means a leak.
- **Growing classes**: classes that grew in the class histograms. "Monotonic" and a high "Growing steps" percentage are the interesting ones. Growth in chunk, structure or palette classes while the player explores is world data, not a leak. Look for classes unrelated to what the test does, or ones that do not stop growing once the player stops moving.
- **Long lived allocations (JFR OldObjectSample)**: allocation sites of objects still alive at the end, with the GC root that holds them. Open `leak.jfr` in JDK Mission Control for path-to-GC-root.
- **Eclipse MAT**: `end_Leak_Suspects.zip` is the MAT leak suspects report of the end heap dump. Unzip and open `index.html`. A suspect that is only "the server's chunk map holds 30% of the heap" is normal.
Use a longer `--duration` and a stationary player (omit `--move-every`) to separate warm-up growth from leaks.

## Reading the render hotspot report

JFR records `ExecutionSample` (CPU, every `--cpu-period` ms) and `ObjectAllocationSample`. A stack counts as render path when a render class (`net.minecraft.client.renderer`, `gui`, `particle`, `texture`, `blaze3d`, `lwjgl`, HeadlessMc lwjgl redirections, ...) is on it, excluding server, networking and startup threads. Each sample is credited to the render frame closest to the top of the stack. Tables are ranked by own cost and by inclusive cost, with "% of all" (of all samples) and "% of render path". Call trees show where in the render path the samples come from. Only rendering counts; the rest of the process is ignored. Idle/parked threads are not sampled, so shares are shares of Java code that ran.

## Validation results

Minecraft 1.21.4, Fabric, Temurin 21, `-Xmx2G`, headless (`-lwjgl`), 2 repetitions x 90 s after 30 s warmup, sampled every 5 s (`benchmark/bench.py compare`).

| Metric (mean) | vanilla | optimized | optimize-off |
|---|---|---|---|
| FPS | 2,859 | 847,422 (render skipped) | 2,576 |
| Client MSPT | 1.3 | 1.4 | 1.4 |
| Heap used (MB) | 683 | 458 (-33%) | 704 |
| Heap committed (MB) | 1,124 | 639 (-43%) | 1,098 |
| Heap after GC (MB) | 416 | 391 (-6%) | 395 |
| Process RSS (MB) | 1,609 | 1,158 (-28%) | 1,618 |
| NMT committed total (MB) | 1,455 | 947 (-35%) | 1,446 |
| NMT class/metaspace (MB) | 21.7 | 21.6 | 21.5 |
| GC pauses per minute | 458 | 172 (-63%) | 342 |
| GC pause time (ms/min) | 926 | 468 (-49%) | 906 |

`optimize-off` behaves like vanilla (FPS -10%, memory within noise), so the mod costs nothing when disabled. The FPS figure of the optimized client is not a real frame rate: the optimizations skip the render work, so the loop is only limited by the (redirected) window calls. Short runs and 2 repetitions: treat differences of a few percent as noise.

### Leak run (optimized, 240 s, teleporting 256 blocks every 30 s)

- Heap after full GC: 279, 346, 410, 420, 419 MB at minutes 0 to 4. It rises while new chunks load and plateaus once the world area stops growing.
- Growing classes (`long[]`, `StructureTemplate$StructureBlockInfo`, `PalettedContainer`, `ReentrantLock`, `DensityFunctions`) are chunk, structure and worldgen data created by the server thread because the player keeps moving. Not rooted in the mod.
- MAT leak suspects: suspect 1 is the server `ChunkMap` (`class_3898`, 29.8% of the heap, 127 MB, held by the Server thread), suspect 2 is the 12,421 loaded classes (10.3%). Both are normal Minecraft state. No hmc-optimizations class appears in suspects, growing classes or OldObjectSample roots.
- NMT class/metaspace and thread counts are flat (21.5 -> 22.2 MB, 41 -> 40 threads).
- Limitation: 4 minutes is short. Run `leak --duration 3600` without `--move-every` for a stricter test.

### Render hotspots with the optimizations on (150 s JFR)

Of 11,909 CPU samples, 475 (4.0%) have a render frame; the render thread itself is only 8.5% of all samples (46.9% of it render path). Top render hotspots by own CPU cost (% of render path samples):

| # | Method | Category | % of render path |
|---|---|---|---|
| 1 | `MemoryUtil.memGetInt` (via `NativeImage.getPixel`, animated sprite interpolation) | lwjgl | 21.9% |
| 2 | `HeadlessMc DefaultRedirections.fallback` (glfwGetTime and others, `HashMap.getNode`) | hmc lwjgl redirection | 13.1% |
| 3 | `GLFW.glfwWindowShouldClose` | lwjgl | 11.4% |
| 4 | `GL11.glTexSubImage2D` (`SpriteContents.upload`) | lwjgl | 10.9% |
| 5 | `LwjglRedirections.lambda$register$2` (`Double.valueOf`, glfwGetTime) | hmc lwjgl redirection | 6.9% |

By inclusive cost, `TextureManager.tick` -> `TextureAtlas.cycleAnimationFrames` -> `SpriteContents$Ticker.tickAndUpload` -> `InterpolationData.uploadInterpolatedFrame` takes 49.3% of the render path (about 2% of all samples), so animated block textures are the main remaining render work. Next are the HeadlessMc lwjgl redirections (26.7%, `RedirectionManagerImpl.invoke`, `glfwGetTime` 19.6%). The same redirections also dominate allocation: `MemoryUtil.memPutInt` (31.5%), `LwjglRedirections.lambda$register$2` (23.5%, boxed doubles), `RedirectionManagerImpl.invoke` (14.6%), `glfwGetTime` (13.7%) of 103 GB allocated by the render path in 150 s.

## Limitations

- Validated on Fabric 1.21.4 only; other versions and loaders are untested (the script is version-generic: Mojang name remapping is done from the Fabric intermediary mappings).
- Dummy assets and a stubbed GL mean FPS numbers compare "work skipped" with "work stubbed"; they are not a GPU benchmark. Use `--xvfb` for software rendering.
- Few repetitions and short durations in the committed validation numbers; use `--reps 3+` and longer durations for publication.
- JFR sampling is statistical: small percentages (below about 1% of the render path) are noisy.
