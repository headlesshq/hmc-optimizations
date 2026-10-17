package me.earth.headlessmc.optimizations.tweaker;

import io.github.impactdevelopment.simpletweaker.SimpleTweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

@SuppressWarnings("unused")
public class HmcOptimizationsTweaker extends SimpleTweaker {
    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        super.injectIntoClassLoader(classLoader);
        MixinBootstrap.init();
        Mixins.addConfiguration("hmcoptimizations.mixins.json");
    }

}
