package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache {
    // prevent LevelLightEngine from creating its engines if the game is headless and can never be rendered.
    // Those engines queues would run full without a render event to poll from them.
    // If the game is not headless, the queues are polled by MixinGameRenderer instead.
    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;ZZ)V"),
        index = 1)
    private boolean shouldCreateBlockLightEngine(boolean shouldCreateBlockLightEngine) {
        return shouldCreateBlockLightEngine && !hmc_optimizations$optimizeLighting();
    }

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;ZZ)V"),
        index = 2)
    private boolean shouldCreateSkyLightEngine(boolean shouldCreateSkylightEngine) {
        return shouldCreateSkylightEngine && !hmc_optimizations$optimizeLighting();
    }

    @Unique
    private static boolean hmc_optimizations$optimizeLighting() {
        return HmcOptimizations.isEnabled() && HmcOptimizations.isHeadless();
    }

}
