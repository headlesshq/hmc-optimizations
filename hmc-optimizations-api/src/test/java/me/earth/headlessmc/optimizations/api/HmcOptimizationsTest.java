package me.earth.headlessmc.optimizations.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HmcOptimizationsTest {
    @AfterEach
    public void reset() {
        HmcOptimizations.reset();
    }

    @Test
    public void testActiveByDefault() {
        assertTrue(HmcOptimizations.isEnabled());
        assertTrue(HmcOptimizations.isActive());
        HmcOptimizations.startFrame();
        assertTrue(HmcOptimizations.isActive());
        HmcOptimizations.endFrame();
        assertTrue(HmcOptimizations.isActive());
    }

    @Test
    public void testSetEnabled() {
        HmcOptimizations.setEnabled(false);
        assertFalse(HmcOptimizations.isActive());
        HmcOptimizations.setEnabled(true);
        assertTrue(HmcOptimizations.isActive());
    }

    @Test
    public void testRunAfterUnoptimizedFrame() {
        AtomicBoolean activeInTask = new AtomicBoolean(true);
        AtomicInteger runs = new AtomicInteger();
        HmcOptimizations.runAfterUnoptimizedFrame(() -> {
            activeInTask.set(HmcOptimizations.isActive());
            runs.incrementAndGet();
        });

        // the frame that was already running when the task got scheduled stays optimized
        assertTrue(HmcOptimizations.isActive());
        HmcOptimizations.startFrame();
        assertFalse(HmcOptimizations.isActive());
        assertTrue(HmcOptimizations.isUnoptimizedFrame());
        HmcOptimizations.endFrame();

        assertEquals(1, runs.get());
        assertFalse(activeInTask.get());
        assertTrue(HmcOptimizations.isActive());
        assertEquals(0, HmcOptimizations.getRequestedFrames());

        HmcOptimizations.startFrame();
        assertTrue(HmcOptimizations.isActive());
        HmcOptimizations.endFrame();
        assertEquals(1, runs.get());
    }

    @Test
    public void testTaskScheduledByTaskGetsItsOwnFrame() {
        AtomicInteger runs = new AtomicInteger();
        HmcOptimizations.runAfterUnoptimizedFrame(() -> HmcOptimizations.runAfterUnoptimizedFrame(runs::incrementAndGet));
        HmcOptimizations.startFrame();
        HmcOptimizations.endFrame();
        assertEquals(0, runs.get());
        assertEquals(1, HmcOptimizations.getRequestedFrames());

        HmcOptimizations.startFrame();
        assertFalse(HmcOptimizations.isActive());
        HmcOptimizations.endFrame();
        assertEquals(1, runs.get());
        assertEquals(0, HmcOptimizations.getRequestedFrames());
    }

    @Test
    public void testRequestFrames() {
        HmcOptimizations.requestUnoptimizedFrames(2);
        for (int i = 0; i < 2; i++) {
            HmcOptimizations.startFrame();
            assertFalse(HmcOptimizations.isActive());
            HmcOptimizations.endFrame();
        }

        HmcOptimizations.startFrame();
        assertTrue(HmcOptimizations.isActive());
        HmcOptimizations.endFrame();
    }

    @Test
    public void testFailingTaskDoesNotBreakFrame() {
        AtomicInteger runs = new AtomicInteger();
        HmcOptimizations.runAfterUnoptimizedFrame(() -> {
            throw new IllegalStateException("test");
        });
        HmcOptimizations.runAfterUnoptimizedFrame(runs::incrementAndGet);
        HmcOptimizations.startFrame();
        HmcOptimizations.endFrame();
        assertEquals(1, runs.get());
        assertTrue(HmcOptimizations.isActive());
    }

    @Test
    public void testGetBoolean() {
        String name = "hmc.optimizations.test.boolean";
        try {
            assertTrue(HmcOptimizations.getBoolean(name, true));
            System.setProperty(name, " off ");
            assertFalse(HmcOptimizations.getBoolean(name, true));
            System.setProperty(name, "1");
            assertTrue(HmcOptimizations.getBoolean(name, false));
        } finally {
            System.clearProperty(name);
        }
    }

    @Test
    public void testCommand() {
        assertEquals("HMC-Optimizations enabled, this Minecraft version only supports turning optimizations off at startup with "
                         + "-Dhmc.optimizations.enabled=false.", execute());
        HmcOptimizations.markRuntimeToggleSupported();
        assertEquals("HMC-Optimizations disabled.", execute("off"));
        assertFalse(HmcOptimizations.isActive());
        assertEquals("HMC-Optimizations enabled.", execute("toggle"));
        assertEquals("HMC-Optimizations enabled, rendering the next 3 frames without optimizations.", execute("--frames", "3"));
        assertEquals("HMC-Optimizations disabled, rendering the next 3 frames without optimizations.", execute("OFF"));
        assertEquals("HMC-Optimizations enabled, rendering the next 3 frames without optimizations.", execute("on"));
    }

    private static String execute(String... args) {
        StringWriter out = new StringWriter();
        CommandLine commandLine = new CommandLine(new OptimizeCommand());
        commandLine.setOut(new PrintWriter(out, true));
        assertEquals(0, commandLine.execute(args));
        return out.toString().trim();
    }

}
