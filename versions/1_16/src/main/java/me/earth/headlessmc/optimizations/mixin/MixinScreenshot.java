package me.earth.headlessmc.optimizations.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.function.Consumer;

/**
 * Optimized frames are not rendered, so a screenshot would only show an old or empty frame.
 * Instead, the next frame is rendered without optimizations and the screenshot is taken after it.
 * Every screenshot goes through here, e.g. F2, HMC-Specifics or HMC-Prometheus.
 */
@Mixin(Screenshot.class)
public abstract class MixinScreenshot {
    @Shadow
    private static void _grab(File gameDirectory, @Nullable String screenshotName, int width, int height, RenderTarget buffer, Consumer<Component> messageConsumer) {
        throw new AbstractMethodError();
    }

    @Inject(method = "_grab", at = @At("HEAD"), cancellable = true)
    private static void grabHook(File gameDirectory, @Nullable String screenshotName, int width, int height, RenderTarget buffer, Consumer<Component> messageConsumer, CallbackInfo ci) {
        if (HmcOptimizations.isActive()) {
            ci.cancel();
            HmcOptimizations.runAfterUnoptimizedFrame(() -> _grab(gameDirectory, screenshotName, width, height, buffer, messageConsumer));
        }
    }

}
