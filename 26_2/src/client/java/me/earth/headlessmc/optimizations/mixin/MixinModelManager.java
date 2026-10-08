package me.earth.headlessmc.optimizations.mixin;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.block.BlockModelSet;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Replaces baked block, item, fluid, and entity models with tiny fallbacks. */
@Mixin(ModelManager.class)
public abstract class MixinModelManager {
    @Shadow
    private Map<Identifier, ItemModel> bakedItemStackModels;

    @Shadow
    private Map<Identifier, ClientItem.Properties> itemProperties;

    @Shadow
    @Final
    private BlockColors blockColors;

    @Shadow
    private EntityModelSet entityModelSet;

    @Shadow
    private ModelBakery.MissingModels missingModels;

    @Shadow
    private BlockStateModelSet blockStateModelSet;

    @Shadow
    private BlockModelSet blockModelSet;

    @Shadow
    private FluidStateModelSet fluidStateModelSet;

    @Shadow
    private Object2IntMap<BlockState> modelGroups;

    @Unique
    private void hmcOptimizations$releaseRenderResources() {
        // Forge exposes an unmodifiable view backed by this map. Clear the
        // backing map when mutable before replacing the primary reference.
        try {
            bakedItemStackModels.clear();
        } catch (UnsupportedOperationException ignored) {
        }

        bakedItemStackModels = Map.of();
        itemProperties = Map.of();
        entityModelSet = EntityModelSet.EMPTY;
        modelGroups = Object2IntMaps.emptyMap();

        BlockStateModelSet fallbackBlocks = new BlockStateModelSet(
            Map.of(), missingModels.block());
        blockStateModelSet = fallbackBlocks;
        blockModelSet = new BlockModelSet(fallbackBlocks, Map.of(), blockColors);
        fluidStateModelSet = new FluidStateModelSet(Map.of(), missingModels.fluid());

        // Forge and NeoForge retain their bakery for extension callbacks;
        // vanilla/Fabric does not have this field. Release it when present.
        hmcOptimizations$clearOptionalField("bakedItemStackModelsView");
        hmcOptimizations$clearOptionalField("bakedStandaloneModels");
        hmcOptimizations$clearOptionalField("modelBakery");
    }

    @Unique
    private void hmcOptimizations$clearOptionalField(String name) {
        try {
            Field field = ModelManager.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(this);
            if (value instanceof AtomicReference<?> reference) {
                reference.set(null);
            } else if (value instanceof Map<?, ?>) {
                field.set(this, Map.of());
            } else {
                field.set(this, null);
            }
        } catch (NoSuchFieldException ignored) {
            // Loader-specific cache is absent.
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                "Could not clear ModelManager." + name, exception);
        }
    }
}
