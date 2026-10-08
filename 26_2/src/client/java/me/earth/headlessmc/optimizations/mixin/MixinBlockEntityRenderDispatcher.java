package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

/** Releases block-entity renderer instances and their baked model trees. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class MixinBlockEntityRenderDispatcher {
    @Shadow
    private Map<BlockEntityType<?>, BlockEntityRenderer<?, ?>> renderers;

    @Unique
    private void hmcOptimizations$releaseRenderResources() {
        renderers = Map.of();
    }
}
