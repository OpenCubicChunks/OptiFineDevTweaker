package ofdev.launchwrapper;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.util.List;
import java.util.Set;

// this is needed only in dev environment to get deobfuscated version of OptiFine running
public class OptifineDevTweakerWrapper implements ITweaker {

    // this requires the jar to be loaded by FML before OptiFine, the easiest way to do it is to name it aa_SomeJar
    public static final Class<?> OF_TRANSFORMER_LAUNCH_CLASSLOADER = Utils.loadClassLW("optifine.OptiFineClassTransformer");

    @Override public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
    }

    @Override public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        classLoader.registerTransformer("ofdev.launchwrapper.OptifineDevTransformerWrapper");
    }

    @Override public String getLaunchTarget() {
        return "net.minecraft.client.main.Main";
    }

    @Override public String[] getLaunchArguments() {
        // force remove optifine's transformers
        // getLaunchArguments is called after all tweakers are initialized, so OF transformer should already exist
        List<IClassTransformer> transformers =
                Utils.getFieldValue(LaunchClassLoader.class, Launch.classLoader, "transformers");
        transformers.removeIf(t -> t.getClass().getName().equals("optifine.OptiFineClassTransformer"));
        // also remove all the new exclusions
        Set<String> classLoaderExceptions =
                Utils.getFieldValue(LaunchClassLoader.class, Launch.classLoader, "classLoaderExceptions");
        classLoaderExceptions.removeIf(t -> t.startsWith("optifine"));
        Set<String> transformerExceptions =
                Utils.getFieldValue(LaunchClassLoader.class, Launch.classLoader, "transformerExceptions");
        transformerExceptions.removeIf(t -> t.startsWith("optifine"));

        // OptiFine tweaker constructed new instance of optifine transformer, so it changed it's instance field
        // now that OptiFine tweaker setup is done,fix it
        Object ofTransformer = OptifineDevTransformerWrapper.ofTransformer;
        Utils.setFieldValue(ofTransformer.getClass(), "instance", null, ofTransformer);

        return new String[0];
    }
}