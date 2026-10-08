package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.config.OptimizationConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Skips building transient world render state when no UI is being rendered. */
@Mixin(LevelExtractor.class)
public abstract class MixinLevelExtractor {
    @Unique
    private static final boolean HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES =
        OptimizationConfig.renderResources();

    @Unique
    private static final String HMC_OPTIMIZATIONS_RELEASE_METHOD =
        "hmcOptimizations$releaseRenderResources";

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Shadow
    @Final
    private LevelRenderer levelRenderer;

    @Shadow
    private SectionUpdateTracker sectionUpdateTracker;

    @Shadow
    private boolean shouldInvalidateCompiledGeometry;

    @Shadow
    private boolean shouldResetLevelRenderData;

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipWorldRenderState(
        DeltaTracker deltaTracker,
        Camera camera,
        float partialTick,
        CallbackInfo callback
    ) {
        boolean skip = minecraft.isGameLoadFinished()
            && (HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES
                || minecraft.gui.overlay() == null && minecraft.gui.screen() == null);

        if (skip) {
            // Drop entity, block-entity, weather, particle, and section state
            // retained from the last real frame. This is vanilla's own early
            // per-frame reset, not a gameplay/world mutation.
            levelRenderState.reset();

            // Vanilla swaps these four tracking sets only from extract(). Keep
            // draining them while extraction is disabled so movement cannot
            // retain every chunk/section delta encountered by the client.
            if (minecraft.level != null) {
                minecraft.level.getChunkSource().flipUpdateTrackingSets();
            }

            callback.cancel();
        }
    }

    @Inject(method = "allChanged", at = @At("TAIL"))
    private void hmcOptimizations$discardChangedRenderGraph(CallbackInfo callback) {
        if (HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES) {
            hmcOptimizations$dropSectionResources();
        }
    }

    @Inject(
        method = "setSectionDirty(IIIZ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hmcOptimizations$discardSectionUpdate(
        int sectionX,
        int sectionY,
        int sectionZ,
        boolean playerChanged,
        CallbackInfo callback
    ) {
        if (HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES) {
            callback.cancel();
        }
    }

    @Unique
    private void hmcOptimizations$dropSectionResources() {
        sectionUpdateTracker = null;
        shouldInvalidateCompiledGeometry = false;
        shouldResetLevelRenderData = false;
        levelRenderState.reset();
        levelRenderer.resetLevelRenderData();
    }

    /** Invoked once the loading overlay has fully completed its fade. */
    @Unique
    private void hmcOptimizations$releaseRenderResources() {
        hmcOptimizations$release(levelRenderer.blockEntityRenderDispatcher());
        hmcOptimizations$release(levelRenderer.entityRenderDispatcher());
        hmcOptimizations$release(minecraft.getModelManager());
        hmcOptimizations$release(minecraft.getAtlasManager());
        hmcOptimizations$dropSectionResources();
    }

    @Unique
    private static void hmcOptimizations$release(Object owner) {
        try {
            Method release = owner.getClass().getDeclaredMethod(
                HMC_OPTIMIZATIONS_RELEASE_METHOD);
            release.setAccessible(true);
            release.invoke(owner);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException(
                "Failed to release render resources from " + owner.getClass().getName(),
                exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                "Missing render-resource cleanup on " + owner.getClass().getName(),
                exception);
        }
    }
}
