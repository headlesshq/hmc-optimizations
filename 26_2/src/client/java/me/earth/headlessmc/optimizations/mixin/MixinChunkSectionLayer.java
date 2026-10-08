package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses tiny growable builders because the chunk-mesh patch emits no vertices. */
@Mixin(ChunkSectionLayer.class)
public abstract class MixinChunkSectionLayer {
    @Inject(method = "bufferSize", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$useTinyInitialBuffer(
        CallbackInfoReturnable<Integer> callback
    ) {
        callback.setReturnValue(256);
    }
}
