package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips animated-sprite advancement and GPU uploads in the opt-in profile. */
@Mixin(TextureManager.class)
public abstract class MixinTextureManager {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipAnimatedTextures(CallbackInfo callback) {
        callback.cancel();
    }
}
