package me.earth.headlessmc.optimizations.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.Tesselator;
import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class MixinWindow {
    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void updateDisplayHook(CallbackInfo ci) {
        if (HmcOptimizations.isActive() && Minecraft.getInstance().isGameLoadFinished()) {
            // RenderSystem.flipFrame clears the Tesselator every frame, screens might still use it
            Tesselator.getInstance().clear();
            ci.cancel();
        }
    }

}
