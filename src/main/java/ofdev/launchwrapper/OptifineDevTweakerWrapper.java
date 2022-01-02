package ofdev.launchwrapper;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import ofdev.common.Utils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

// this is needed only in dev environment to get deobfuscated version of OptiFine running
public class OptifineDevTweakerWrapper implements ITweaker {

    // this requires the jar to be loaded by FML before OptiFine, the easiest way to do it is to name it aa_SomeJar
    public static final Class<?> OF_TRANSFORMER_LAUNCH_CLASSLOADER;

    static {
        Launch.classLoader.registerTransformer("ofdev.launchwrapper.OptifineDevTweakerWrapper$OptiFineTransformerTransformer");
        OF_TRANSFORMER_LAUNCH_CLASSLOADER = UtilsLW.loadClassLW("optifine.OptiFineClassTransformer");
    }

    public static class OptiFineTransformerTransformer implements IClassTransformer {
        @Override public byte[] transform(String name, String transformedName, byte[] basicClass) {
            if (name != null && name.equals("optifine.OptiFineClassTransformer")) {
                ClassReader cr = new ClassReader(basicClass);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);
                for (MethodNode method : cn.methods) {
                    if (method.name.equals("<init>")) {
                        AbstractInsnNode insn = null;
                        for (int i = 0; i < method.instructions.size(); i++) {
                            insn = method.instructions.get(i);
                            if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                                break;
                            }
                        }
                        IntInsnNode loadThis = new IntInsnNode(Opcodes.ALOAD, 0);
                        MethodInsnNode initOptiTransformer = new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "ofdev/launchwrapper/OptifineDevTweakerWrapper",
                                "initOptiTransformer", "(Ljava/lang/Object;)V", false);
                        method.instructions.insert(insn, loadThis);
                        method.instructions.insert(loadThis, initOptiTransformer);
                        method.instructions.insert(initOptiTransformer, new InsnNode(Opcodes.RETURN));
                    }
                }
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                cn.accept(cw);
                return cw.toByteArray();
            }
            return basicClass;
        }
    }

    public static void initOptiTransformer(Object ofTransformer) {
        OptifineDevTransformerWrapper.ofTransformer = (IClassTransformer) ofTransformer;
        try {
            Class<? extends IClassTransformer> ofTransformerClass =
                    (Class<? extends IClassTransformer>) OptifineDevTweakerWrapper.OF_TRANSFORMER_LAUNCH_CLASSLOADER;

            URL ofUrl = ofTransformer.getClass().getProtectionDomain().getCodeSource().getLocation();

            JarURLConnection connection = (JarURLConnection) ofUrl.openConnection();
            ZipFile file = new ZipFile(new File(connection.getJarFileURL().toURI()));
            UtilsLW.setFieldValue(ofTransformerClass, "ofZipFile", ofTransformer, file);

            Class<?> ofPatcher = Launch.classLoader.findClass("optifine.Patcher");

            Object patchMapVal = UtilsLW.invokeMethod(ofPatcher, null, "getConfigurationMap", file);
            Object patternsVal = UtilsLW.invokeMethod(ofPatcher, null, "getConfigurationPatterns", patchMapVal);

            UtilsLW.setFieldValue(ofTransformerClass, "patchMap", ofTransformer, patchMapVal);
            UtilsLW.setFieldValue(ofTransformerClass, "patterns", ofTransformer, patternsVal);
            //System.out.println("Ignore the above, OptiFine should run anyway");
            UtilsLW.setFieldValue(ofTransformer.getClass(), "instance", null, ofTransformer);

        } catch (IOException | URISyntaxException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path CLASS_DUMP_LOCATION;

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
        // also remove all the new exclusions
        Set<String> classLoaderExceptions =
                UtilsLW.getFieldValue(LaunchClassLoader.class, Launch.classLoader, "classLoaderExceptions");
        classLoaderExceptions.removeIf(t -> t.startsWith("optifine"));
        Set<String> transformerExceptions =
                UtilsLW.getFieldValue(LaunchClassLoader.class, Launch.classLoader, "transformerExceptions");
        transformerExceptions.removeIf(t -> t.startsWith("optifine"));
        return new String[0];
    }
}