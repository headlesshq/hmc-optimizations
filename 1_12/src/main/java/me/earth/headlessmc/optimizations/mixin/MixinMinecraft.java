package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.FrameRenderer;
import me.earth.headlessmc.optimizations.api.HmcOptimizations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.Timer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft implements FrameRenderer {
    @Shadow
    private Framebuffer framebuffer;

    @Shadow
    @Final
    private GuiToast toastGui;

    @Shadow
    @Final
    private Timer timer;

    @Shadow
    private boolean isGamePaused;

    @Shadow
    private float renderPartialTicksPaused;

    @Shadow
    public EntityRenderer entityRenderer;

    @Shadow
    public boolean skipRenderWorld;

    @Shadow
    public abstract void updateDisplay();

    static {
        HmcOptimizations.markRuntimeToggleSupported();
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void runGameLoopHeadHook(CallbackInfo ci) {
        HmcOptimizations.startFrame();
    }

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void runGameLoopReturnHook(CallbackInfo ci) {
        HmcOptimizations.endFrame();
    }

    @Redirect(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V"))
    private void updateCameraAndRenderHook(EntityRenderer entityRenderer, float partialTicks, long nanoTime) {
        if (!HmcOptimizations.isActive()) {
            entityRenderer.updateCameraAndRender(partialTicks, nanoTime);
        }
    }

    @Redirect(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/toasts/GuiToast;drawToast(Lnet/minecraft/client/gui/ScaledResolution;)V"))
    private void drawToastHook(GuiToast guiToast, ScaledResolution resolution) {
        if (!HmcOptimizations.isActive()) {
            guiToast.drawToast(resolution);
        }
    }

    @Redirect(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/shader/Framebuffer;framebufferRender(II)V"))
    private void framebufferRenderHook(Framebuffer framebuffer, int width, int height) {
        if (!HmcOptimizations.isActive()) {
            framebuffer.framebufferRender(width, height);
        }
    }

    @Redirect(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EntityRenderer;renderStreamIndicator(F)V"))
    private void renderStreamIndicatorHook(EntityRenderer entityRenderer, float partialTicks) {
        if (!HmcOptimizations.isActive()) {
            entityRenderer.renderStreamIndicator(partialTicks);
        }
    }

    @Redirect(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;updateDisplay()V"))
    private void updateDisplayHook(Minecraft minecraft) {
        if (!HmcOptimizations.isActive()) {
            updateDisplay();
        }
    }

    @Inject(method = "displayDebugInfo", at = @At("HEAD"), cancellable = true)
    private void displayDebugInfoHook(CallbackInfo ci) {
        if (HmcOptimizations.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "checkGLError", at = @At("HEAD"), cancellable = true)
    private void checkGLErrorHook(CallbackInfo ci) {
        if (HmcOptimizations.isActive()) {
            ci.cancel();
        }
    }

    @Redirect(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Thread;yield()V",
            remap = false))
    private void yieldHook() {
        // without rendering and Display.update() the game loop runs hot
        if (HmcOptimizations.isActive()) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Thread.yield();
    }

    @Override
    public void hmc_optimizations$renderFrame() {
        float partialTicks = isGamePaused ? renderPartialTicksPaused : timer.renderPartialTicks;
        GlStateManager.pushMatrix();
        GlStateManager.clear(16640);
        framebuffer.bindFramebuffer(true);
        GlStateManager.enableTexture2D();
        if (!skipRenderWorld) {
            entityRenderer.updateCameraAndRender(partialTicks, System.nanoTime());
            toastGui.drawToast(new ScaledResolution((Minecraft) (Object) this));
        }

        framebuffer.unbindFramebuffer();
        GlStateManager.popMatrix();
    }

}
