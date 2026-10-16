package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
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
    private void tickHook(boolean paused, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void playHook(SoundInstance soundInstance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
    }

    // The sounds queued by these two are only moved on by SoundEngine#tick, which we cancel.
    // Without this they would pile up forever.
    @Inject(method = "queueTickingSound", at = @At("HEAD"), cancellable = true)
    private void queueTickingSoundHook(TickableSoundInstance soundInstance, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void playDelayedHook(SoundInstance soundInstance, int delay, CallbackInfo ci) {
        ci.cancel();
    }

}
