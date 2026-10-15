package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents allocation and ticking of client-only visual particles. */
@Mixin(ParticleEngine.class)
public abstract class MixinParticleEngine {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipParticleTick(CallbackInfo callback) {
        callback.cancel();
    }

    @Inject(
        method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hmcOptimizations$skipParticleCreation(
        ParticleOptions options,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        CallbackInfoReturnable<Particle> callback
    ) {
        callback.setReturnValue(null);
    }

    @Inject(
        method = "add(Lnet/minecraft/client/particle/Particle;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hmcOptimizations$skipDirectParticleAdd(
        Particle particle,
        CallbackInfo callback
    ) {
        callback.cancel();
    }

    @Inject(
        method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hmcOptimizations$skipTrackingEmitter(
        Entity entity,
        ParticleOptions options,
        CallbackInfo callback
    ) {
        callback.cancel();
    }

    @Inject(
        method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hmcOptimizations$skipTimedTrackingEmitter(
        Entity entity,
        ParticleOptions options,
        int lifetime,
        CallbackInfo callback
    ) {
        callback.cancel();
    }
}
