package me.earth.headlessmc.optimizations;

import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Minecraft;

/**
 * Optimized frames are not rendered, so a screenshot would only show an old or empty frame.
 * On 1.7.10 screenshots read the pixels synchronously, so they can not wait for the next frame.
 * Instead, a frame is rendered without optimizations right before the pixels are read.
 */
public final class UnoptimizedFrame {
    private UnoptimizedFrame() {
        throw new AssertionError();
    }

    /**
     * Has to be called on Minecraft's main thread, between frames.
     */
    public static void renderIfOptimized() {
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
