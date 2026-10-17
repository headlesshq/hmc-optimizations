package me.earth.headlessmc.optimizations.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code RenderSystem#flipFrame} presents the frame (swaps the window buffers) through {@link GpuDevice#presentFrame()}.
 * The rest of flipFrame (clearing the Tesselator, resetting the dynamic uniforms, LevelRenderer#endFrame) still runs.
 */
@Mixin(GpuDevice.class)
public class MixinGpuDevice {
    @Inject(method = "presentFrame", at = @At("HEAD"), cancellable = true)
    private void presentFrameHook(CallbackInfo ci) {
        if (HmcOptimizations.isActive() && Minecraft.getInstance().isGameLoadFinished()) {
            ci.cancel();
        }
    }

}
