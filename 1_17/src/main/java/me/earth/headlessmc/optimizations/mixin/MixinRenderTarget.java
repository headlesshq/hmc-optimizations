package me.earth.headlessmc.optimizations.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public class MixinRenderTarget {
    @Inject(method = "bindWrite", at = @At("HEAD"), cancellable = true)
    private void bindWriteHook(boolean bl, CallbackInfo ci) {
        if (HmcOptimizations.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "unbindWrite", at = @At("HEAD"), cancellable = true)
    private void unbindWriteHook(CallbackInfo ci) {
        if (HmcOptimizations.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "blitToScreen(II)V", at = @At("HEAD"), cancellable = true)
    private void blitToScreenHook(CallbackInfo ci) {
        if (HmcOptimizations.isActive()) {
            ci.cancel();
        }
    }

}
