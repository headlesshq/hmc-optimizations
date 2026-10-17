package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundEngine.class)
public class MixinSoundEngine {
    // TODO: handle sound differently from rendering?
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tickHook(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void playHook(CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }

}
