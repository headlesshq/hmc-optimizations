package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Avoids selecting and retrying background music when audio is disabled. */
@Mixin(MusicManager.class)
public abstract class MixinMusicManager {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipMusicTick(CallbackInfo callback) {
        callback.cancel();
    }
}
