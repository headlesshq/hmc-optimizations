package me.earth.headlessmc.optimizations.config;

import java.util.Locale;

/**
 * Startup-only configuration for the mixin plugin.
 *
 * <p>These properties are deliberately read before Minecraft classes are
 * transformed. Changing them while the game is running has no effect.</p>
 */
public final class OptimizationConfig {
    public static final String PREFIX = "hmc.optimizations.";

    private OptimizationConfig() {
    }

    public static boolean enabled() {
        return property("enabled", true);
    }

    public static boolean render() {
        return category("render", true);
    }

    /**
     * World render-state extraction is only suppressed when frame rendering is
     * suppressed as well, so stale extracted state can never be consumed.
     */
    public static boolean worldRenderState() {
        boolean configured = category("world_render_state", true);
        require(configured, render(), "world_render_state", "render");
        return configured;
    }

    public static boolean particles() {
        return category("particles", true);
    }

    public static boolean sound() {
        return category("sound", true);
    }

    /** Client light engines and their render updates are visual-only. */
    public static boolean lighting() {
        return category("lighting", true);
    }

    /** Animated texture advancement and uploads are visual-only. */
    public static boolean animatedTextures() {
        return category("animated_textures", true);
    }

    /** Chunk meshes are not required by core HeadlessMC automation. */
    public static boolean chunkMesh() {
        return category("chunk_mesh", true);
    }

    /**
     * Renderer staging and builder buffers are safe to shrink only while the
     * shared chunk-mesh optimization prevents vertex production.
     */
    public static boolean renderBuffers() {
        boolean configured = category("render_buffers", true);
        require(configured, chunkMesh(), "render_buffers", "chunk_mesh");
        return configured;
    }

    /**
     * Baked visual resources can be released only when both frame rendering
     * and world render-state extraction are suppressed.
     */
    public static boolean renderResources() {
        boolean configured = category("render_resources", true);
        require(configured, render(), "render_resources", "render");
        require(configured, worldRenderState(), "render_resources", "world_render_state");
        return configured;
    }

    public static String summary() {
        return "enabled=" + enabled()
            + ", render=" + render()
            + ", world_render_state=" + worldRenderState()
            + ", particles=" + particles()
            + ", sound=" + sound()
            + ", lighting=" + lighting()
            + ", animated_textures=" + animatedTextures()
            + ", chunk_mesh=" + chunkMesh()
            + ", render_buffers=" + renderBuffers()
            + ", render_resources=" + renderResources();
    }

    private static boolean category(String name, boolean defaultValue) {
        boolean configured = property(name, defaultValue);
        return enabled() && configured;
    }

    private static void require(
        boolean categoryEnabled,
        boolean dependencyEnabled,
        String category,
        String dependency
    ) {
        if (categoryEnabled && !dependencyEnabled) {
            throw new IllegalArgumentException(
                "-D" + PREFIX + category + "=true requires -D"
                    + PREFIX + dependency + "=true");
        }
    }

    private static boolean property(String name, boolean defaultValue) {
        String value = System.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException(
                "Invalid boolean value for -D" + PREFIX + name + ": " + value);
        };
    }
}
