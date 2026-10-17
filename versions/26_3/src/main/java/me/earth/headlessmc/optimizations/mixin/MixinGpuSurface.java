package me.earth.headlessmc.optimizations.mixin;

import com.mojang.renderpearl.frontend.FrontendGpuSurface;
import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.3 presents a frame by acquiring the next image of the window's surface,
 * blitting the main render target onto it and presenting it.
 * If no image is acquired, Minecraft does neither blit nor present, see {@code Minecraft#renderFrame}.
 * This replaces the buffer swap, RenderTarget#blitToScreen and RenderTarget#bindWrite of older versions.
 */
@Mixin(FrontendGpuSurface.class)
public class MixinGpuSurface {
    @Inject(method = "acquireNextTexture", at = @At("HEAD"), cancellable = true)
    private void acquireNextTextureHook(CallbackInfo ci) {
        if (HmcOptimizations.isActive() && Minecraft.getInstance().isGameLoadFinished()) {
            ci.cancel();
        }
    }

}
