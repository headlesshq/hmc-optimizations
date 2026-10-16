package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.UnoptimizedFrame;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ScreenShotHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

/**
 * F2 screenshots, see {@link UnoptimizedFrame}.
 */
@Mixin(ScreenShotHelper.class)
public abstract class MixinScreenShotHelper {
    @Inject(
        method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;IILnet/minecraft/client/shader/Framebuffer;)Lnet/minecraft/util/IChatComponent;",
        at = @At("HEAD"))
    private static void saveScreenshotHook(File gameDirectory, String screenshotName, int width, int height, Framebuffer buffer,
                                           CallbackInfoReturnable<IChatComponent> cir) {
        UnoptimizedFrame.renderIfOptimized();
    }

}
