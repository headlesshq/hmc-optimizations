package me.earth.headlessmc.optimizations.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Produces an empty, fully-visible mesh without walking blocks or allocating vertices. */
@Mixin(SectionCompiler.class)
public abstract class MixinSectionCompiler {
    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$compileEmptyMesh(
        SectionPos sectionPosition,
        RenderSectionRegion region,
        VertexSorting vertexSorting,
        SectionBufferBuilderPack buffers,
        CallbackInfoReturnable<SectionCompiler.Results> callback
    ) {
        SectionCompiler.Results empty = new SectionCompiler.Results();
        // Treat every face as mutually visible so the occlusion graph does not
        // repeatedly consider the synthetic empty result unresolved.
        empty.visibilitySet.setAll(true);
        callback.setReturnValue(empty);
    }
}
