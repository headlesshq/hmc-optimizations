package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.resources.model.EquipmentAssetManager;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.equipment.EquipmentAsset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

/** Releases parsed visual equipment-layer definitions. */
@Mixin(EquipmentAssetManager.class)
public abstract class MixinEquipmentAssetManager {
    @Shadow
    private Map<ResourceKey<EquipmentAsset>, EquipmentClientInfo> equipmentAssets;

    @Unique
    private void hmcOptimizations$releaseRenderResources() {
        equipmentAssets = Map.of();
    }
}
