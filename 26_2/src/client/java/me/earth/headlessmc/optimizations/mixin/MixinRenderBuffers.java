package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keeps one section-builder pack instead of scaling the unused pool by CPU count. */
@Mixin(RenderBuffers.class)
public abstract class MixinRenderBuffers {
    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionBufferBuilderPool;allocate(I)Lnet/minecraft/client/renderer/SectionBufferBuilderPool;"
        )
    )
    private int hmcOptimizations$useOneSectionBuilder(int maxSectionBuilders) {
        return 1;
    }
}
