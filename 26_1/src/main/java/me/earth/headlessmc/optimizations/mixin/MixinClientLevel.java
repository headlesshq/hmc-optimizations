package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class MixinClientLevel {
    @Inject(method = "queueLightUpdate", at = @At("HEAD"), cancellable = true)
    private void queueLightUpdateHook(Runnable runnable, CallbackInfo ci) {
        // prevent light updates from being queued as that queue would never be cleared
        if (HmcOptimizations.isEnabled() && HmcOptimizations.isHeadless()) {
            ci.cancel();
        }
    }

}
