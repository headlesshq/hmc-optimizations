package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/** Releases entity renderer instances and their baked model trees. */
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinEntityRenderDispatcher {
    @Shadow
    private Map<EntityType<?>, EntityRenderer<?, ?>> renderers;

    @Shadow
    private Map<PlayerModelType, AvatarRenderer<AbstractClientPlayer>> playerRenderers;

    @Shadow
    private Map<PlayerModelType, AvatarRenderer<ClientMannequin>> mannequinRenderers;

    @Shadow
    @Final
    private EquipmentAssetManager equipmentAssets;

    @Unique
    private void hmcOptimizations$releaseRenderResources() {
        renderers = Map.of();
        playerRenderers = Map.of();
        mannequinRenderers = Map.of();
        try {
            Method release = equipmentAssets.getClass().getDeclaredMethod(
                "hmcOptimizations$releaseRenderResources");
            release.setAccessible(true);
            release.invoke(equipmentAssets);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException(
                "Failed to release equipment render resources", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                "Missing equipment render-resource cleanup", exception);
        }
    }
}
