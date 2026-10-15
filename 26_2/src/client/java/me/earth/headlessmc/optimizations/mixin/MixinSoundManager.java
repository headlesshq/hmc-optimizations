package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Releases parsed sound definitions and cached resources after reload. */
@Mixin(SoundManager.class)
public abstract class MixinSoundManager {
    @Shadow
    @Final
    private Map<Identifier, WeighedSoundEvents> registry;

    @Shadow
    @Final
    private Map<Identifier, Resource> soundCache;

    @Inject(
        method = "apply(Lnet/minecraft/client/sounds/SoundManager$Preparations;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("RETURN")
    )
    private void hmcOptimizations$releaseSoundResources(CallbackInfo callback) {
        registry.clear();
        soundCache.clear();
    }
}
