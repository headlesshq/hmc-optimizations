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
        // prevent light updates from being queued as that queue would never be cleared.
        // The runnable still has to run: it marks the chunk as light ready (setLightReady), which the
        // ReceivingLevelScreen needs in order to close. The light engines are null in that case, see MixinClientChunkCache.
        if (HmcOptimizations.isEnabled() && HmcOptimizations.isHeadless()) {
            runnable.run();
            ci.cancel();
        }
    }

}
