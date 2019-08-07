package ofdev;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.RETURN;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import mcp.MethodsReturnNonnullByDefault;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class OFDevTransformationService implements ITransformationService {

    private static final Logger LOGGER = LogManager.getLogger();

    // TODO: find it automatically
    private static final String MC_JAR = System.getProperty("ofdev.mcjar", "/home/bartosz/.minecraft/versions/1.14.4/1.14.4.jar");

    private List<ITransformer.Target> targets;
    private IEnvironment env;

    @Nonnull @Override public String name() {
        return "OptiFineDevTransformationService";
    }

    @Override public void initialize(IEnvironment environment) {
    }

    @Override public void beginScanning(IEnvironment environment) {

    }

    @Override public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
        this.env = env;
        if (!otherServices.contains("OptiFine")) {
            throw new IncompatibleEnvironmentException("Couldn't find OptiFine!");
        }
        LOGGER.info("OptiFine dev transformation service loading");
        try {
            // I'm sorry :(
            Launcher launcher = Launcher.INSTANCE;
            Field serviceHandlerField = Launcher.class.getDeclaredField("transformationServicesHandler");
            serviceHandlerField.setAccessible(true);
            Object handler = serviceHandlerField.get(launcher);

            Field serviceLookupField = handler.getClass().getDeclaredField("serviceLookup");
            serviceLookupField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> serviceLookup = (Map<String, ?>) serviceLookupField.get(handler);
            Collection<?> serviceHolders = serviceLookup.values();

            for (Object holder : serviceHolders) {
                Field serviceField = holder.getClass().getDeclaredField("service");
                serviceField.setAccessible(true);
                ITransformationService service = (ITransformationService) serviceField.get(holder);
                if (service.name().equals("OptiFine")) {
                    LOGGER.info("Found OptiFine service, overwriting transformer");

                    Field transformerField = service.getClass().getDeclaredField("transformer");
                    transformerField.setAccessible(true);

                    Object oldTransformer = transformerField.get(null);

                    Field ofZipFileField = oldTransformer.getClass().getDeclaredField("ofZipFile");
                    ofZipFileField.setAccessible(true);
                    ZipFile ofZipFile = (ZipFile) ofZipFileField.get(oldTransformer);

                    Class<?> newClass = makeNewOptiFineTransformer(oldTransformer.getClass().getClassLoader());
                    Constructor<?> constr = newClass.getConstructor(ZipFile.class);
                    Object newTransformer = constr.newInstance(ofZipFile);

                    transformerField.set(null, newTransformer);

                    LOGGER.info("Extracting targets");
                    //noinspection unchecked
                    this.targets = ((List<ITransformer<?>>) (Object) service.transformers())
                            .stream().map(ITransformer::targets).flatMap(Collection::stream)
                            .collect(Collectors.toList());
                }
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | NoSuchFieldException | InstantiationException e) {
            throw new Error(e);
        }
    }

    public static InputStream getResourceStream(String path) {
        try {
            if (!path.startsWith("/")) {
                path = '/' + path;
            }
            FileSystem fs = FileSystems.newFileSystem(Paths.get(MC_JAR), OFDevTransformationService.class.getClassLoader());
            Path file = fs.getPath(path);
            return Files.newInputStream(file);
        } catch (IOException e) {
            return OFDevTransformationService.class.getResourceAsStream(path);
        }
    }

    @Nonnull @Override public List<ITransformer> transformers() {
        return Collections.singletonList(new OFDevRetransformer(env, targets));
    }

    private static Class<?> makeNewOptiFineTransformer(ClassLoader parent) {

        /*
        Generates this java code:

            import cpw.mods.modlauncher.api.ITransformer;
            import java.io.InputStream;
            import java.util.zip.ZipFile;
            import ofdev.OFDevTransformationService;
            import optifine.OptiFineTransformer;
            import org.objectweb.asm.tree.ClassNode;

            public class Test extends OptiFineTransformer implements ITransformer<ClassNode> {
                public Test(ZipFile ofZipFile) {
                    super(ofZipFile);
                }

                public InputStream getResourceStream(String path) {
                    return OFDevTransformationService.getResourceStream(path);
                }
            }
        */
        String name = "ofdev/DevOptiFineTransformer";

        ClassWriter cw = new ClassWriter(0);
        MethodVisitor mv;

        cw.visit(52, ACC_PUBLIC + ACC_SUPER, name,
                "Loptifine/OptiFineTransformer;Lcpw/mods/modlauncher/api/ITransformer<Lorg/objectweb/asm/tree/ClassNode;>;",
                "optifine/OptiFineTransformer", new String[]{"cpw/mods/modlauncher/api/ITransformer"});

        cw.visitSource("Test.java", null);

        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/zip/ZipFile;)V", null, null);
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitLineNumber(10, l0);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, "optifine/OptiFineTransformer", "<init>", "(Ljava/util/zip/ZipFile;)V", false);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLineNumber(11, l1);
            mv.visitInsn(RETURN);
            Label l2 = new Label();
            mv.visitLabel(l2);
            mv.visitLocalVariable("this", "L" + name + ";", null, l0, l2, 0);
            mv.visitLocalVariable("ofZipFile", "Ljava/util/zip/ZipFile;", null, l0, l2, 1);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
        }
        {
            mv = cw.visitMethod(ACC_PUBLIC, "getResourceStream", "(Ljava/lang/String;)Ljava/io/InputStream;", null, null);
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitLineNumber(14, l0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, "ofdev/OFDevTransformationService", "getResourceStream", "(Ljava/lang/String;)Ljava/io/InputStream;",
                    false);
            mv.visitInsn(ARETURN);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLocalVariable("this", "L" + name + ";", null, l0, l1, 0);
            mv.visitLocalVariable("path", "Ljava/lang/String;", null, l0, l1, 1);
            mv.visitMaxs(1, 2);
            mv.visitEnd();
        }
        cw.visitEnd();

        ASMClassLoader cl = new ASMClassLoader(parent);
        return cl.define(name.replace('/', '.'), cw.toByteArray());
    }

    private static class ASMClassLoader extends ClassLoader {

        private ASMClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] data) {
            return defineClass(name, data, 0, data.length);
        }
    }
}
