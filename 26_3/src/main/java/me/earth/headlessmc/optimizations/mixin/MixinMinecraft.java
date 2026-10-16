package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    static {
        HmcOptimizations.markRuntimeToggleSupported();
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void runTickHeadHook(boolean renderLevel, CallbackInfo ci) {
        HmcOptimizations.startFrame();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void runTickReturnHook(boolean renderLevel, CallbackInfo ci) {
        HmcOptimizations.endFrame();
    }

}
