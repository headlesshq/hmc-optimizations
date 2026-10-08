package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaves the sound engine unloaded, avoiding OpenAL and decoded-buffer work. */
@Mixin(SoundEngine.class)
public abstract class MixinSoundEngine {
    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipAudioReload(CallbackInfo callback) {
        // The engine is deliberately never loaded. Avoid re-validating a
        // SoundManager registry that is released after its resource listener
        // completes, as well as redundant device teardown/setup callbacks.
        callback.cancel();
    }

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$keepAudioDeviceUnloaded(CallbackInfo callback) {
        // SoundEngine.play already returns NOT_STARTED while loaded is false.
        // reload() still validates definitions and destroys old resources;
        // only OpenAL initialization and buffer decoding are suppressed.
        callback.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipAudioTick(
        boolean paused,
        CallbackInfo callback
    ) {
        callback.cancel();
    }

    @Inject(method = "requestPreload", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipPreload(
        Sound sound,
        CallbackInfo callback
    ) {
        // loadLibrary() normally consumes this list. Since library loading is
        // disabled, do not retain sounds in an undrainable queue.
        callback.cancel();
    }

    @Inject(method = "queueTickingSound", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipTickingSound(
        TickableSoundInstance sound,
        CallbackInfo callback
    ) {
        callback.cancel();
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipDelayedSound(
        SoundInstance sound,
        int delay,
        CallbackInfo callback
    ) {
        callback.cancel();
    }
}
