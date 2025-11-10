<h1 align="center" style="font-weight: normal;"><b>HMC-Optimizations</b></h1>
<p align="center">Version specific optimizations for the HeadlessMc runtime.</p>
<p align="center"><a href="https://github.com/headlesshq/mc-runtime-test">Mc-Runtime-Test</a> | <a href="https://github.com/headlesshq/headlessmc">HMC</a> | <a href="https://github.com/headlesshq/hmc-specifics">HMC-Specifics</a> | HMC-Optimizations</p>

<div align="center">

[![CodeFactor](https://www.codefactor.io/repository/github/headlesshq/hmc-optimizations/badge/1.20.4)](https://www.codefactor.io/repository/github/headlesshq/hmc-optimizations/overview/1.20.4)
[![GitHub All Releases](https://img.shields.io/github/downloads/headlesshq/hmc-optimizations/total.svg)](https://github.com/headlesshq/hmc-optimizations/releases)
![](https://github.com/headlesshq/hmc-optimizations/actions/workflows/run-mc-runtime-test.yml/badge.svg)
![GitHub](https://img.shields.io/github/license/headlesshq/hmc-optimizations)
![Github last-commit](https://img.shields.io/github/last-commit/headlesshq/hmc-optimizations)

</div>

> [!WARNING]
> This is work in progress. 
> We can confirm that it launches with Minecraft.
> But we are missing data from running this long term, 
> it is possible that there are memory leaks.
> Also mods that have mixins in rendering functions and NEED
> them to be called will not work, because we skip that code.

Version specific optimizations for [HeadlessMC](https://github.com/headlesshq/headlessmc).
The goal of HeadlessMC is to make the Minecraft client run on the command line. It does so by
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

Console output from a fresh, headless SinglePlayer world, without and with HMC-Optimizations:

```
> fps
1356 fps T: inffast fancy-clouds B: 2
> optimize
HMC-Optimizations enabled.
> fps
1826208 fps T: inffast fancy-clouds B: 2
```
