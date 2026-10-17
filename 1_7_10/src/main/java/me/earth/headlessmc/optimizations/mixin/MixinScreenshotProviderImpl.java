package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.UnoptimizedFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HMC-Specifics (and HMC-Prometheus through it) reads the pixels of the framebuffer itself on 1.7.10,
 * without ScreenShotHelper, see {@link UnoptimizedFrame}. Not applied if HMC-Specifics is not installed.
 */
@Pseudo
@Mixin(targets = "me.earth.headlessmc.mc.ScreenshotProviderImpl", remap = false)
public abstract class MixinScreenshotProviderImpl {
    @Inject(method = "takeScreenshot", at = @At("HEAD"), require = 0)
    private void takeScreenshotHook(CallbackInfo ci) {
        UnoptimizedFrame.renderIfOptimized();
    }

}
