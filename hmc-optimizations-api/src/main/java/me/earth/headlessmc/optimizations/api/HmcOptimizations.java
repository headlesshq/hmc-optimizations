package me.earth.headlessmc.optimizations.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Minecraft version independent state of HMC-Optimizations.
 * <p>
 * Optimizations that only skip work for the current frame (e.g. rendering the frame,
 * swapping buffers) check {@link #isActive()} every time and can be turned off and on at runtime.
 * Optimizations that change how the game is set up (e.g. not creating light engines, releasing models)
 * are decided when the game starts and can not be turned off at runtime.
 * <p>
 * If the game is not headless, e.g. running with Xvfb, a single frame can be rendered
 * without optimizations, e.g. to take a screenshot, see {@link #runAfterUnoptimizedFrame(Runnable)}.
 * The version specific code calls {@link #startFrame()} and {@link #endFrame()} on Minecraft's main thread
 * around every frame.
 */
public final class HmcOptimizations {
    public static final String NAME = "HMC-Optimizations";
    /**
     * Turns HMC-Optimizations off when set to false.
     * Optimizations that are decided at startup will not be applied at all.
     */
    public static final String ENABLED_PROPERTY = "hmc.optimizations.enabled";
    /**
     * Overrides the detection of HeadlessMc's headless mode, see {@link #isHeadless()}.
     */
    public static final String HEADLESS_PROPERTY = "hmc.optimizations.headless";

    private static final Logger LOGGER = Logger.getLogger(HmcOptimizations.class.getName());
    /**
     * Classes that HeadlessMc's LWJGL redirection, which makes the game headless, consists of.
     */
    private static final String[] HEADLESS_CLASSES = {
        "io.github.headlesshq.headlessmc.lwjgl.api.RedirectionApi",
        "me.earth.headlessmc.lwjgl.api.RedirectionApi"
    };

    private static final Queue<Runnable> AFTER_UNOPTIMIZED_FRAME = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger REQUESTED_FRAMES = new AtomicInteger();

    private static volatile boolean enabled = getBoolean(ENABLED_PROPERTY, true);
    private static volatile boolean runtimeToggleSupported;
    private static volatile boolean unoptimizedFrame;
    private static volatile Boolean headless;

    private HmcOptimizations() {
        throw new AssertionError();
    }

    /**
     * Checked by every optimization that can be turned off at runtime.
     *
     * @return {@code true} if optimizations are enabled and the current frame is not rendered without them.
     */
    public static boolean isActive() {
        return enabled && !unoptimizedFrame;
    }

    /**
     * @return {@code true} if optimizations are enabled, even if the current frame is rendered without them.
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Turns the optimizations that can be toggled at runtime on or off.
     *
     * @param enabled whether to enable optimizations.
     */
    public static void setEnabled(boolean enabled) {
        HmcOptimizations.enabled = enabled;
    }

    /**
     * @return {@code true} if the current frame is rendered without optimizations.
     */
    public static boolean isUnoptimizedFrame() {
        return unoptimizedFrame;
    }

    /**
     * @return {@code true} if the optimizations of this Minecraft version can be turned off at runtime.
     */
    public static boolean isRuntimeToggleSupported() {
        return runtimeToggleSupported;
    }

    /**
     * Called by Minecraft versions whose optimizations can be turned off at runtime, once they call
     * {@link #startFrame()} and {@link #endFrame()}.
     */
    public static void markRuntimeToggleSupported() {
        runtimeToggleSupported = true;
    }

    /**
     * Requests the given amount of frames to be rendered without optimizations.
     *
     * @param frames the amount of frames to render without optimizations.
     */
    public static void requestUnoptimizedFrames(int frames) {
        if (frames > 0) {
            REQUESTED_FRAMES.addAndGet(frames);
        }
    }

    /**
     * @return the amount of frames that will still be rendered without optimizations.
     */
    public static int getRequestedFrames() {
        return REQUESTED_FRAMES.get();
    }

    /**
     * Renders the next frame without optimizations and runs the given task on Minecraft's main thread,
     * after that frame has been rendered. E.g. take a screenshot in the task.
     * If optimizations are not active, the task still runs after the next frame.
     *
     * @param task the task to run after the next unoptimized frame.
     */
    public static void runAfterUnoptimizedFrame(Runnable task) {
        AFTER_UNOPTIMIZED_FRAME.add(task);
        requestUnoptimizedFrames(1);
    }

    /**
     * Called on Minecraft's main thread before a frame is rendered.
     * Decides if the frame is rendered without optimizations.
     */
    public static void startFrame() {
        if (!unoptimizedFrame && REQUESTED_FRAMES.get() > 0) {
            unoptimizedFrame = true;
        }
    }

    /**
     * Called on Minecraft's main thread after a frame has been rendered.
     * If the frame has been rendered without optimizations, the tasks waiting for it are executed.
     */
    public static void endFrame() {
        if (!unoptimizedFrame) {
            return;
        }

        try {
            List<Runnable> tasks = new ArrayList<>();
            Runnable task;
            while ((task = AFTER_UNOPTIMIZED_FRAME.poll()) != null) {
                tasks.add(task);
            }

            for (Runnable runnable : tasks) {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    LOGGER.log(Level.SEVERE, "Failed to run task after unoptimized frame", t);
                }
            }
        } finally {
            REQUESTED_FRAMES.updateAndGet(frames -> Math.max(frames - 1, 0));
            if (!AFTER_UNOPTIMIZED_FRAME.isEmpty()) {
                // a task has been added while we were running the others, make sure it gets its frame
                REQUESTED_FRAMES.updateAndGet(frames -> Math.max(frames, 1));
            }

            unoptimizedFrame = false;
        }
    }

    /**
     * Resets the frame state, for tests.
     */
    static void reset() {
        AFTER_UNOPTIMIZED_FRAME.clear();
        REQUESTED_FRAMES.set(0);
        unoptimizedFrame = false;
        enabled = getBoolean(ENABLED_PROPERTY, true);
    }

    /**
     * Whether the game runs in HeadlessMc's headless mode, in which the LWJGL calls are redirected
     * and nothing is rendered at all. Optimizations that would make rendering impossible should
     * only be applied by default if the game is headless.
     * The detection can be overridden with the system property {@value #HEADLESS_PROPERTY}.
     *
     * @return {@code true} if the game is headless.
     */
    public static boolean isHeadless() {
        Boolean result = headless;
        if (result == null) {
            String property = System.getProperty(HEADLESS_PROPERTY);
            if (property != null && !property.trim().isEmpty()) {
                result = parseBoolean(HEADLESS_PROPERTY, property);
            } else {
                result = false;
                for (String name : HEADLESS_CLASSES) {
                    if (isPresent(name)) {
                        result = true;
                        break;
                    }
                }
            }

            headless = result;
        }

        return result;
    }

    /**
     * Reads a boolean system property, accepting true/false, 1/0, yes/no and on/off.
     *
     * @param name the name of the system property.
     * @param defaultValue the value to use if the property is not set.
     * @return the value of the property.
     * @throws IllegalArgumentException if the value can not be parsed.
     */
    public static boolean getBoolean(String name, boolean defaultValue) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        return parseBoolean(name, value);
    }

    private static boolean parseBoolean(String name, String value) {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true":
            case "1":
            case "yes":
            case "on":
                return true;
            case "false":
            case "0":
            case "no":
            case "off":
                return false;
            default:
                throw new IllegalArgumentException("Invalid boolean value for -D" + name + ": " + value);
        }
    }

    private static boolean isPresent(String name) {
        ClassLoader[] classLoaders = {
            HmcOptimizations.class.getClassLoader(), Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader()
        };

        for (ClassLoader classLoader : classLoaders) {
            if (classLoader == null) {
                continue;
            }

            try {
                Class.forName(name, false, classLoader);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // not present on this ClassLoader
            }
        }

        return false;
    }

}
