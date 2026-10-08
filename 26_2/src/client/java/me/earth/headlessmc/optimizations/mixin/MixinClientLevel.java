package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops queued packet-to-render light updates when both light engines are absent. */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {
    @Inject(method = "queueLightUpdate", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$discardLightUpdate(
        Runnable update,
        CallbackInfo callback
    ) {
        callback.cancel();
    }
}
