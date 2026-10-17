package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 26.2 renders a frame in two steps, {@code Minecraft#renderFrame} first calls
 * {@link GameRenderer#extract} to collect everything to draw (also compiles chunk sections, extracts entities...)
 * and then {@link GameRenderer#render} to draw it. We skip both.
 * <p>
 * The light updates are not drained by rendering anymore, {@code Minecraft#renderFrame} calls
 * {@code ClientLevel#update} before it, so skipping the frame does not make the light queues run full.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Shadow @Final private Minecraft minecraft;

    @Unique
    private boolean hmc_optimizations$skipFrame;
    @Unique
    private boolean hmc_optimizations$needsRebuild;
    /**
     * LevelExtractor consumes the chunk updates the client has received since the last frame it extracted.
     * While we skip extraction they are kept for the next real extraction, so that the already compiled
     * sections stay valid. This is the maximum amount of them we keep, so exploring does not make them grow forever.
     */
    @Unique
    private static final int hmc_optimizations$MAX_PENDING_UPDATES = 100_000;

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void extractHook(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // finishing of a loading overlay is tied to rendering it, so in that case we need to render it.
        // Screens, e.g. the loading terrain screen, also need to be extracted, it waits for the first chunk sections.
        hmc_optimizations$skipFrame = HmcOptimizations.isActive() && minecraft.gui.overlay() == null && minecraft.gui.screen() == null;
        if (hmc_optimizations$skipFrame) {
            ClientLevel level = minecraft.level;
            if (level != null) {
                ClientChunkCache chunkSource = level.getChunkSource();
                int pending = chunkSource.addedEmptySections().size()
                    + chunkSource.removedEmptySections().size()
                    + chunkSource.addedLoadedChunks().size()
                    + chunkSource.removedLoadedChunks().size();
                if (pending > hmc_optimizations$MAX_PENDING_UPDATES) {
                    // Only LevelExtractor#extract flips these sets, which discards the updates.
                    // That means LevelExtractor needs to start over once we render again.
                    chunkSource.flipUpdateTrackingSets();
                    hmc_optimizations$needsRebuild = true;
                }
            }

            ci.cancel();
        } else if (hmc_optimizations$needsRebuild) {
            hmc_optimizations$needsRebuild = false;
            if (minecraft.level != null) {
                minecraft.levelExtractor.allChanged();
            }
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void renderHook(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        // only skip the frames we did not extract, the extracted state might not be there otherwise
        if (hmc_optimizations$skipFrame) {
            hmc_optimizations$skipFrame = false;
            ci.cancel();
        }
    }

}
