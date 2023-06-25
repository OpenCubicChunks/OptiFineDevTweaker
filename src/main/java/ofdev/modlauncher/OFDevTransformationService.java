package ofdev.modlauncher;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.RETURN;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.INameMappingService;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import ofdev.common.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.zip.ZipFile;
public class OFDevTransformationService implements ITransformationService {

    private static final Logger LOGGER = LogManager.getLogger();
    private static Path mcJar;
    public static Path CLASS_DUMP_LOCATION;

    private static IEnvironment env;
    private static BiConsumer<ClassNode, ClassNode> fixMemberAccess;

    @Override public String name() {
        return "OptiFineDevTransformationService";
    }

    @Override public void initialize(IEnvironment environment) {
        mcJar = Utils.findMinecraftJar();
        try {
            Path classDump = Paths.get(".").toAbsolutePath().normalize().resolve(".optifineDev.classes");
            Utils.rm(classDump);
            CLASS_DUMP_LOCATION = classDump;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override public void beginScanning(IEnvironment environment) {

    }

    @Override public void onLoad(IEnvironment envIn, Set<String> otherServices) throws IncompatibleEnvironmentException {
        env = envIn;
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
                    Object newTransformer;
                    try {
                        newTransformer = constr.newInstance(ofZipFile);
                    } catch (InvocationTargetException e) {
                        constr = newClass.getConstructor(ZipFile.class, IEnvironment.class);
                        newTransformer = constr.newInstance(ofZipFile, envIn);
                    }
                    transformerField.set(null, newTransformer);

                    LOGGER.info("Finding OptiFine AccessFixer");

                    Class<?> accessFixer = null;
                    try {
                        accessFixer = Class.forName("optifine.AccessFixer", false, oldTransformer.getClass().getClassLoader());
                    } catch (ClassNotFoundException e) {
                        LOGGER.error("OptiFine access fixer not found. Either old version or things are not going to work");
                        LOGGER.catching(e);
                    }
                    if (accessFixer != null) {
                        Method fixMemberAccessMethod = accessFixer.getMethod("fixMemberAccess", ClassNode.class, ClassNode.class);
                        MethodHandle fixMemberAccessHandle = MethodHandles.lookup().unreflect(fixMemberAccessMethod);

                        fixMemberAccess = (nodeOld, nodeNew) -> {
                            try {
                                fixMemberAccessHandle.invoke(nodeOld, nodeNew);
                            } catch (Throwable t) {
                                throw new RuntimeException(t);
                            }
                        };
                    } else {
                        fixMemberAccess = (a, b) -> {
                        };
                    }
                }
            }

        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | NoSuchFieldException | InstantiationException e) {
            throw new Error(e);
        }
    }

    // called from asm-generated code
    @SuppressWarnings("unused") public static InputStream getResourceStream(String path) {
        try(FileSystem fs = FileSystems.newFileSystem(mcJar, OFDevTransformationService.class.getClassLoader())){
            if (!path.startsWith("/")) {
                path = '/' + path;
            }
            Path file = fs.getPath(path);
            return Files.newInputStream(file);
        } catch (IOException e) {
            return OFDevTransformationService.class.getResourceAsStream(path);
        }
    }

    @SuppressWarnings("unused") public static ClassNode wrapOptiFineTransform(ClassNode transformed, ClassNode original) {
        ClassNode output = new ClassNode();
        Optional<BiFunction<INameMappingService.Domain, String, String>> srgtomcp = env.findNameMapping("srg");
        if (!srgtomcp.isPresent()) {
            throw new IllegalStateException("No srgtomcp mappings found! Are you in dev environment?");
        }
        OfDevRemapper remapper = new OfDevRemapper(srgtomcp.get());
        ClassRemapper classRemapper = new ClassRemapper(output, remapper);
        transformed.accept(classRemapper);
        fixMemberAccess.accept(original, output);

        try {
            ClassWriter cw = new ClassWriter(0); // don't compute frames/maxs, this code is only for decompiler and IDE
            output.accept(cw);
            Utils.dumpBytecode(CLASS_DUMP_LOCATION, output.name, cw.toByteArray());
        } catch (Throwable t) {
            LOGGER.catching(t); // in case there is anything broken about the code, it's better for it to fail in modlauncher than here
        }
        return output;
    }

    @SuppressWarnings("rawtypes") @Override public List<ITransformer> transformers() {
        return Collections.singletonList(new OFDevRetransformer(env));
    }

    private static Class<?> makeNewOptiFineTransformer(ClassLoader parent) {

        /*
        Generates this java code:
            public class DevOptiFineTransformer extends OptiFineTransformer implements ITransformer<ClassNode> {
                public Test(ZipFile ofZipFile) {
                    super(ofZipFile);
                }

                @Override public InputStream getResourceStream(String path) {
                    return OFDevTransformationService.getResourceStream(path);
                }

                @Override public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
                    return OFDevTransformationService.wrapOptiFineTransform(super.transform(input, context), input);
                }
            }
        */
        String name = "ofdev/modlauncher/modlauncher/generated/DevOptiFineTransformer";

        ClassWriter cw = new ClassWriter(0);
        MethodVisitor mv;

        cw.visit(52, ACC_PUBLIC + ACC_SUPER, name,
                "Loptifine/OptiFineTransformer;Lcpw/mods/modlauncher/api/ITransformer<Lorg/objectweb/asm/tree/ClassNode;>;",
                "optifine/OptiFineTransformer", new String[]{"cpw/mods/modlauncher/api/ITransformer"});

        cw.visitSource("OFDevTransformationService.java", null);

        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/zip/ZipFile;Lcpw/mods/modlauncher/api/IEnvironment;)V", null, null);
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitLineNumber(10, l0);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESPECIAL, "optifine/OptiFineTransformer", "<init>", "(Ljava/util/zip/ZipFile;Lcpw/mods/modlauncher/api/IEnvironment;)V", false);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLineNumber(11, l1);
            mv.visitInsn(RETURN);
            Label l2 = new Label();
            mv.visitLabel(l2);
            mv.visitLocalVariable("this", "L" + name + ";", null, l0, l2, 0);
            mv.visitLocalVariable("ofZipFile", "Ljava/util/zip/ZipFile;", null, l0, l2, 1);
            mv.visitLocalVariable("env", "Lcpw/mods/modlauncher/api/IEnvironment;", null, l0, l2, 2);
            mv.visitMaxs(3, 3);
            mv.visitEnd();
        }
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
            mv.visitMethodInsn(INVOKESTATIC, "ofdev/modlauncher/OFDevTransformationService", "getResourceStream", "(Ljava/lang/String;)Ljava/io/InputStream;",
                    false);
            mv.visitInsn(ARETURN);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLocalVariable("this", "L" + name + ";", null, l0, l1, 0);
            mv.visitLocalVariable("path", "Ljava/lang/String;", null, l0, l1, 1);
            mv.visitMaxs(1, 2);
            mv.visitEnd();
        }
        {
            //public ClassNode transform(ClassNode input, ITransformerVotingContext context)
            mv = cw.visitMethod(ACC_PUBLIC, "transform",
                    "(Lorg/objectweb/asm/tree/ClassNode;Lcpw/mods/modlauncher/api/ITransformerVotingContext;)Lorg/objectweb/asm/tree/ClassNode;",
                    null, null);
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESPECIAL, "optifine/OptiFineTransformer",
                    "transform",
                    "(Lorg/objectweb/asm/tree/ClassNode;"
                            + "Lcpw/mods/modlauncher/api/ITransformerVotingContext;)"
                            + "Lorg/objectweb/asm/tree/ClassNode;",
                    false);

            mv.visitVarInsn(ALOAD, 1);

            // pass transformed and original class node
            mv.visitMethodInsn(INVOKESTATIC, "ofdev/modlauncher/OFDevTransformationService",
                    "wrapOptiFineTransform",
                    "(Lorg/objectweb/asm/tree/ClassNode;Lorg/objectweb/asm/tree/ClassNode;)Lorg/objectweb/asm/tree/ClassNode;",
                    false);
            mv.visitInsn(ARETURN);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLocalVariable("this", "L" + name + ";", null, l0, l1, 0);
            mv.visitLocalVariable("input", "Lorg/objectweb/asm/tree/ClassNode;", null, l0, l1, 1);
            mv.visitLocalVariable("context", "Lcpw/mods/modlauncher/api/ITransformerVotingContext;", null, l0, l1, 1);
            mv.visitMaxs(3, 3);
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
