package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void renderHook(float f, long l, boolean bl, CallbackInfo ci) {
        // finishing of a loading overlay is tied to rendering it, so in that case we need to render it.
        if (HmcOptimizations.isActive() && minecraft.getOverlay() == null && minecraft.screen == null) {
            if (minecraft.level != null) {
                // LevelRenderer processes light updates while rendering, their queues would run full
                // while we skip frames. If the game is headless, there are usually no light engines, see MixinClientChunkCache
                minecraft.level.getChunkSource().getLightEngine().runUpdates(Integer.MAX_VALUE, true, true);
            }

            ci.cancel();
        }
    }

}
