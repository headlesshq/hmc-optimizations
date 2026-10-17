package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class MixinScreen {
    @Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true)
    private void extractTransparentBackgroundHook(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (HmcOptimizations.isActive()) {
            ci.cancel();
        }
    }

}
