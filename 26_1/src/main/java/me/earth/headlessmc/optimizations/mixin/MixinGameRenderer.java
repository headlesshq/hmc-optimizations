package me.earth.headlessmc.optimizations.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.1 renders a frame in two steps, {@code Minecraft#renderFrame} first calls
 * {@link GameRenderer#extract} to collect everything to draw (also entities, particles, the gui...)
 * and then {@link GameRenderer#render} to draw it. We skip both.
 * <p>
 * The light updates are not drained by rendering, {@code Minecraft#renderFrame} calls
 * {@code ClientLevel#update} before it, so skipping the frame does not make the light queues run full.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private FogRenderer fogRenderer;
    @Shadow @Final private CrossFrameResourcePool resourcePool;

    @Unique
    private boolean hmc_optimizations$skipFrame;

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void extractHook(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // finishing of a loading overlay is tied to rendering it, so in that case we need to render it.
        // Screens, e.g. the loading terrain screen, also need to be extracted.
        hmc_optimizations$skipFrame = HmcOptimizations.isActive() && minecraft.getOverlay() == null && minecraft.screen == null;
        if (hmc_optimizations$skipFrame) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void renderHook(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // only skip the frames we did not extract, the extracted state might not be there otherwise
        if (hmc_optimizations$skipFrame) {
            hmc_optimizations$skipFrame = false;
            // the lifecycle tail of render(), so that buffers rotate and pooled resources of earlier frames get released
            fogRenderer.endFrame();
            resourcePool.endFrame();
            ci.cancel();
        }
    }

}
