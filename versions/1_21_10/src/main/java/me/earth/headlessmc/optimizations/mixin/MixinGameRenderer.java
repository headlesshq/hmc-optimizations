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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Shadow @Final Minecraft minecraft;
    @Shadow @Final private FogRenderer fogRenderer;
    @Shadow @Final private CrossFrameResourcePool resourcePool;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void renderHook(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
        // finishing of a loading overlay is tied to rendering it, so in that case we need to render it.
        if (HmcOptimizations.isActive() && minecraft.getOverlay() == null && minecraft.screen == null) {
            if (minecraft.level != null) {
                // LevelRenderer processes light updates while rendering, their queues would run full
                // while we skip frames. If the game is headless, there are usually no light engines, see MixinClientChunkCache
                minecraft.level.pollLightUpdates();
                minecraft.level.getChunkSource().getLightEngine().runLightUpdates();
            }

            // the lifecycle tail of render(), so that buffers rotate and pooled resources of earlier frames get released
            fogRenderer.endFrame();
            resourcePool.endFrame();
            ci.cancel();
        }
    }

}
