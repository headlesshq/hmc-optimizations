package me.earth.headlessmc.optimizations.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import me.earth.headlessmc.optimizations.config.OptimizationConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Skips submitting a frame once loading is complete and no UI needs a frame. */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {
    @Unique
    private static final boolean HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES =
        OptimizationConfig.renderResources();

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private FogRenderer fogRenderer;

    @Shadow
    @Final
    private GuiRenderer guiRenderer;

    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @Shadow
    @Final
    private CrossFrameResourcePool resourcePool;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Unique
    private boolean hmcOptimizations$skippedRenderFrame;

    @Unique
    private boolean hmcOptimizations$releasedRenderResources;

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$rebuildAfterSkippedFrames(
        DeltaTracker deltaTracker,
        boolean renderLevel,
        CallbackInfo callback
    ) {
        boolean resourcesLoaded = minecraft.isGameLoadFinished();
        boolean loadingOverlayVisible = minecraft.gui.overlay() != null;
        if (HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES
            && loadingOverlayVisible) {
            // A resource-pack reload rebuilt visual caches. Let its overlay
            // finish fading, then release the caches again on the next frame.
            hmcOptimizations$releasedRenderResources = false;
        }

        boolean willSkip = resourcesLoaded
            && !loadingOverlayVisible
            && (HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES
                || minecraft.gui.screen() == null);

        if (resourcesLoaded
            && !loadingOverlayVisible
            && HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES) {
            if (!hmcOptimizations$releasedRenderResources) {
                hmcOptimizations$releaseRenderResources();
                hmcOptimizations$releasedRenderResources = true;
            }

            // LevelLoadTracker normally waits until the player's section has
            // reached the renderer. No section is compiled in headless mode,
            // so acknowledge the equivalent point once packets and the local
            // player are available instead of relying on its 30-second escape.
            ClientPacketListener connection = minecraft.getConnection();
            if (connection != null && minecraft.player != null) {
                Runnable playerSectionReady =
                    connection.getPlayerCompiledSectionCallback();
                if (playerSectionReady != null) {
                    playerSectionReady.run();
                }
            }

            // Screen instances continue to tick and receive HMC-Specifics
            // commands; only their transient pixel render state is discarded.
            gameRenderState.guiRenderState.reset();
            gameRenderState.levelRenderState.reset();
            if (minecraft.level != null) {
                minecraft.level.getChunkSource().flipUpdateTrackingSets();
            }

            callback.cancel();
            return;
        }

        if (!willSkip && hmcOptimizations$skippedRenderFrame) {
            // Extraction can publish and clear chunk-render deltas even when
            // render() never consumes them. Rebuild before the first real UI
            // frame so a menu's world background cannot use stale geometry.
            hmcOptimizations$skippedRenderFrame = false;
            minecraft.levelExtractor.allChanged();
        }
    }

    /** Lets loading overlays advance without rendering their released world backdrop. */
    @ModifyVariable(
        method = {"extract", "render"},
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private boolean hmcOptimizations$skipReleasedLevel(boolean renderLevel) {
        return HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES ? false : renderLevel;
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void hmcOptimizations$skipHeadlessFrame(
        DeltaTracker deltaTracker,
        boolean renderLevel,
        CallbackInfo callback
    ) {
        // Loading overlays advance their completion from render callbacks.
        // Screens are also left intact for HMC-Specifics GUI inspection.
        if (minecraft.isGameLoadFinished()
            && minecraft.gui.overlay() == null
            && (HMC_OPTIMIZATIONS_RELEASE_RENDER_RESOURCES
                || minecraft.gui.screen() == null)) {
            // These calls are the lifecycle tail of vanilla render(). Even a
            // skipped frame must rotate staged buffers and age pooled native
            // resources so an earlier real UI/loading frame cannot be retained.
            fogRenderer.endFrame();
            guiRenderer.endFrame();
            renderBuffers.endFrame();
            resourcePool.endFrame();
            hmcOptimizations$skippedRenderFrame = true;
            callback.cancel();
        }
    }

    @Unique
    private void hmcOptimizations$releaseRenderResources() {
        try {
            Method release = minecraft.levelExtractor.getClass().getDeclaredMethod(
                "hmcOptimizations$releaseRenderResources");
            release.setAccessible(true);
            release.invoke(minecraft.levelExtractor);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException(
                "Failed to release visual resources", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                "Missing visual-resource cleanup", exception);
        }
    }
}
