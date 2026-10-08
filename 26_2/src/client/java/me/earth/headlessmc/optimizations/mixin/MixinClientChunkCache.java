package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Avoids allocating the client-only block and sky light engines. */
@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache {
    @ModifyArgs(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;<init>(Lnet/minecraft/world/level/chunk/LightChunkGetter;ZZ)V"
        )
    )
    private void hmcOptimizations$disableLightEngines(Args arguments) {
        arguments.set(1, false);
        arguments.set(2, false);
    }
}
