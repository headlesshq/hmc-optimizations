package me.earth.headlessmc.optimizations.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.model.sprite.SpriteId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

/** Releases retained CPU-side atlas sprite data while preserving reloadability. */
@Mixin(AtlasManager.class)
public abstract class MixinAtlasManager {
    @Shadow
    private Map<SpriteId, TextureAtlasSprite> spriteLookup;

    @Unique
    private void hmcOptimizations$releaseRenderResources() {
        AtlasManager self = (AtlasManager)(Object)this;
        self.forEach((id, atlas) -> atlas.clearTextureData());
        spriteLookup = Map.of();
    }
}
