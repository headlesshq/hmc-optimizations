package me.earth.headlessmc.optimizations;

/**
 * Implemented by Minecraft through MixinMinecraft.
 */
public interface FrameRenderer {
    /**
     * Renders a frame into Minecraft's framebuffer, the way Minecraft#runGameLoop does.
     */
    void hmc_optimizations$renderFrame();

}
