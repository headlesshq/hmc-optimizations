package me.earth.headlessmc.optimizations.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
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
        Minecraft mc = Minecraft.getInstance();
        if (HmcOptimizations.isActive() && mc.isGameLoadFinished()) {
            // Everything RenderSystem.flipFrame does per frame, except swapping buffers and polling events.
            // Screens might still render, their buffers and uniforms must not pile up.
            Tesselator.getInstance().clear();
            RenderSystem.getDynamicUniforms().reset();
            mc.levelRenderer.endFrame();
            ci.cancel();
        }
    }

}
