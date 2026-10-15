package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.particle.ParticleResources;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Releases particle factories and sprite bindings after reload has completed. */
@Mixin(ParticleResources.class)
public abstract class MixinParticleResources {
    @Shadow
    @Final
    private Map<Identifier, ?> spriteSets;

    @Inject(method = "reload", at = @At("RETURN"), cancellable = true)
    private void hmcOptimizations$releaseParticleResources(
        CallbackInfoReturnable<CompletableFuture<Void>> callback
    ) {
        CompletableFuture<Void> reload = callback.getReturnValue();
        callback.setReturnValue(reload.thenRun(() -> {
            ParticleResources self = (ParticleResources)(Object)this;
            self.getProviders().clear();
            hmcOptimizations$clearForgeNamedProviders();
            spriteSets.clear();
        }));
    }

    @Unique
    private void hmcOptimizations$clearForgeNamedProviders() {
        try {
            Field field = ParticleResources.class.getDeclaredField("providersByName");
            field.setAccessible(true);
            ((Map<?, ?>)field.get(this)).clear();
        } catch (NoSuchFieldException ignored) {
            // Fabric and NeoForge have one provider lookup.
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                "Could not clear Forge particle providers", exception);
        }
    }
}
