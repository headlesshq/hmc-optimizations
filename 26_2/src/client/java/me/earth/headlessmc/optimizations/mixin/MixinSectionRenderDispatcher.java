package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Shrinks chunk-upload staging from 98 MiB when chunk meshes are disabled. */
@Mixin(SectionRenderDispatcher.class)
public abstract class MixinSectionRenderDispatcher {
    @ModifyConstant(
        method = "<init>",
        constant = @Constant(intValue = 102_760_448)
    )
    private int hmcOptimizations$shrinkChunkStagingBuffer(int originalSize) {
        return 1_048_576;
    }
}
