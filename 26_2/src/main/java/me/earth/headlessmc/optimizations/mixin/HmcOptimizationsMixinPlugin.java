package me.earth.headlessmc.optimizations.mixin;

import me.earth.headlessmc.optimizations.config.OptimizationConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Selects optimization categories before their target classes are loaded. */
public final class HmcOptimizationsMixinPlugin implements IMixinConfigPlugin {
    private static final String MIXIN_PREFIX =
        "me.earth.headlessmc.optimizations.mixin.";

    @Override
    public void onLoad(String mixinPackage) {
        System.out.println("[HMC-Optimizations] Minecraft 26.2 configuration: "
            + OptimizationConfig.summary());
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simpleName = mixinClassName.startsWith(MIXIN_PREFIX)
            ? mixinClassName.substring(MIXIN_PREFIX.length())
            : mixinClassName;

        return switch (simpleName) {
            case "MixinGameRenderer" -> OptimizationConfig.render();
            case "MixinLevelExtractor" -> OptimizationConfig.worldRenderState();
            case "MixinParticleEngine", "MixinParticleResources" -> OptimizationConfig.particles();
            case "MixinMusicManager", "MixinSoundEngine", "MixinSoundManager" -> OptimizationConfig.sound();
            case "MixinClientChunkCache", "MixinClientLevel" -> OptimizationConfig.lighting();
            case "MixinTextureManager" -> OptimizationConfig.animatedTextures();
            case "MixinSectionCompiler" -> OptimizationConfig.chunkMesh();
            case "MixinChunkSectionLayer", "MixinRenderBuffers",
                "MixinSectionRenderDispatcher" -> OptimizationConfig.renderBuffers();
            case "MixinAtlasManager", "MixinBlockEntityRenderDispatcher",
                "MixinEntityRenderDispatcher", "MixinEquipmentAssetManager",
                "MixinModelManager" -> OptimizationConfig.renderResources();
            default -> false;
        };
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(
        String targetClassName,
        ClassNode targetClass,
        String mixinClassName,
        IMixinInfo mixinInfo
    ) {
    }

    @Override
    public void postApply(
        String targetClassName,
        ClassNode targetClass,
        String mixinClassName,
        IMixinInfo mixinInfo
    ) {
    }
}
