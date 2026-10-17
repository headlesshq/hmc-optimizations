package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.FrameRenderer;
import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.image.BufferedImage;

/**
 * Optimized frames are not rendered, so a screenshot would only show an old or empty frame.
 * On 1.12.2 screenshots are read synchronously (F2, HMC-Specifics), so they can not wait for the next frame.
 * Instead, a frame is rendered without optimizations right before the pixels are read,
 * the same way Minecraft#runGameLoop would render it.
 */
@Mixin(ScreenShotHelper.class)
public abstract class MixinScreenShotHelper {
    @Inject(method = "createScreenshot", at = @At("HEAD"))
    private static void createScreenshotHook(int width, int height, Framebuffer framebuffer, CallbackInfoReturnable<BufferedImage> cir) {
        if (HmcOptimizations.isActive()) {
            HmcOptimizations.requestUnoptimizedFrames(1);
            HmcOptimizations.startFrame();
            try {
                ((FrameRenderer) Minecraft.getMinecraft()).hmc_optimizations$renderFrame();
            } finally {
                HmcOptimizations.endFrame();
            }
        }
    }

}
