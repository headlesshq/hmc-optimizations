package me.earth.headlessmc.optimizations.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.ProfileResults;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    static {
        HmcOptimizations.markRuntimeToggleSupported();
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void runTickHeadHook(boolean renderLevel, CallbackInfo ci) {
        HmcOptimizations.startFrame();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void runTickReturnHook(boolean renderLevel, CallbackInfo ci) {
        HmcOptimizations.endFrame();
    }

    @Inject(method = "renderFpsMeter", at = @At("HEAD"), cancellable = true)
    private void renderFpsMeterHook(PoseStack poseStack, ProfileResults profileResults, CallbackInfo ci) {
        if (HmcOptimizations.isActive()) {
            ci.cancel();
        }
    }

}
