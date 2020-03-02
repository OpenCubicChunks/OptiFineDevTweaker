package ofdev.launchwrapper;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import ofdev.common.Utils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

// this is needed only in dev environment to get deobfuscated version of OptiFine running
public class OptifineDevTweakerWrapper implements ITweaker {

    // this requires the jar to be loaded by FML before OptiFine, the easiest way to do it is to name it aa_SomeJar
    public static final Class<?> OF_TRANSFORMER_LAUNCH_CLASSLOADER;
    public static Path CLASS_DUMP_LOCATION;

    static {
        Launch.classLoader.addTransformerExclusion("optifine.");
        OF_TRANSFORMER_LAUNCH_CLASSLOADER = UtilsLW.loadClassLW("optifine.OptiFineClassTransformer");
    }

    @Override public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        try {
            Path classDump = Paths.get(".").toAbsolutePath().normalize().resolve(".optifineDev.classes");
            Utils.rm(classDump);
            CLASS_DUMP_LOCATION = classDump;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
                UtilsLW.getFieldValue(LaunchClassLoader.class, Launch.classLoader, "transformers");
        transformers.removeIf(t -> t.getClass().getName().equals("optifine.OptiFineClassTransformer"));

        // OptiFine tweaker constructed new instance of optifine transformer, so it changed it's instance field
        // now that OptiFine tweaker setup is done,fix it
        Object ofTransformer = OptifineDevTransformerWrapper.ofTransformer;
        UtilsLW.setFieldValue(ofTransformer.getClass(), "instance", null, ofTransformer);

        return new String[0];
    }
}