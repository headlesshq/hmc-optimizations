package me.earth.headlessmc.optimizations.api;

import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Turns HMC-Optimizations on and off at runtime.
 * Registered with HMC-Specifics through {@code META-INF/hmc-specifics-commands},
 * it only depends on picocli, which HMC-Specifics provides.
 */
@CommandLine.Command(
    name = "optimize",
    description = "Turns HMC-Optimizations on or off, e.g. to look at the game when it is not headless.")
public class OptimizeCommand implements Runnable {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @CommandLine.Parameters(
        index = "0",
        arity = "0..1",
        paramLabel = "<on/off/toggle/status>",
        completionCandidates = Actions.class,
        description = "Turn optimizations on, off, toggle them or show their status (default).")
    private String action = "status";

    @CommandLine.Option(
        names = {"-f", "--frames"},
        paramLabel = "<frames>",
        description = "Renders the given amount of frames without optimizations, then turns them back on.")
    private int frames;

    @Override
    public void run() {
        switch (action.toLowerCase(Locale.ROOT)) {
            case "on":
                HmcOptimizations.setEnabled(true);
                break;
            case "off":
                HmcOptimizations.setEnabled(false);
                break;
            case "toggle":
                HmcOptimizations.setEnabled(!HmcOptimizations.isEnabled());
                break;
            case "status":
                break;
            default:
                throw new CommandLine.ParameterException(spec.commandLine(), "Unknown action '" + action + "', expected on, off, toggle or status.");
        }

        if (frames > 0) {
            HmcOptimizations.requestUnoptimizedFrames(frames);
        }

        spec.commandLine().getOut().println(status());
    }

    static String status() {
        StringBuilder sb = new StringBuilder(HmcOptimizations.NAME)
            .append(HmcOptimizations.isEnabled() ? " enabled" : " disabled");
        if (!HmcOptimizations.isRuntimeToggleSupported()) {
            sb.append(", this Minecraft version only supports turning optimizations off at startup with -D")
              .append(HmcOptimizations.ENABLED_PROPERTY).append("=false");
        }

        int requested = HmcOptimizations.getRequestedFrames();
        if (requested > 0) {
            sb.append(", rendering the next ").append(requested).append(requested == 1 ? " frame" : " frames")
              .append(" without optimizations");
        }

        return sb.append('.').toString();
    }

    public static final class Actions extends ArrayList<String> {
        public Actions() {
            super(Arrays.asList("on", "off", "toggle", "status"));
        }
    }

}
