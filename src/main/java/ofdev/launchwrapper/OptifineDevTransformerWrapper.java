package ofdev.launchwrapper;

import static java.lang.reflect.Modifier.isPrivate;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.RETURN;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import ofdev.common.Utils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

// this is needed only in dev environment to get deobfuscated version of OptiFine running
public class OptifineDevTransformerWrapper implements IClassTransformer {

    // TODO: will it work on windows?
    private static final String MC_JAR = System.getProperty("ofdev.mcjar",
            System.getProperty("user.home") + "/.gradle/caches/minecraft/net/minecraft/minecraft/" +
                    UtilsLW.mcVersion() + "/minecraft-" + UtilsLW.mcVersion() + ".jar");


    private static final FileSystem mcJar;

    static {
        try {
            mcJar = FileSystems.newFileSystem(Paths.get(MC_JAR), Launch.classLoader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final OptifineDevRemapper remapper = OptifineDevRemapper.NOTCH_MCP;


    public static IClassTransformer ofTransformer;

    {
        try {
            Class<? extends IClassTransformer> ofTransformerClass =
                    (Class<? extends IClassTransformer>) OptifineDevTweakerWrapper.OF_TRANSFORMER_LAUNCH_CLASSLOADER;

            ofTransformer = ofTransformerClass.newInstance();
            URL ofUrl = ofTransformer.getClass().getProtectionDomain().getCodeSource().getLocation();

            JarURLConnection connection = (JarURLConnection) ofUrl.openConnection();
            ZipFile file = new ZipFile(new File(connection.getJarFileURL().toURI()));
            UtilsLW.setFieldValue(ofTransformerClass, "ofZipFile", ofTransformer, file);

            Class<?> ofPatcher = Launch.classLoader.findClass("optifine.Patcher");

            Object patchMapVal = UtilsLW.invokeMethod(ofPatcher, null, "getConfigurationMap", file);
            Object patternsVal = UtilsLW.invokeMethod(ofPatcher, null, "getConfigurationPatterns", patchMapVal);

            UtilsLW.setFieldValue(ofTransformerClass, "patchMap", ofTransformer, patchMapVal);
            UtilsLW.setFieldValue(ofTransformerClass, "patterns", ofTransformer, patternsVal);
            System.out.println("Ignore the above, OptiFine should run anyway");

            Launch.classLoader.addURL(new File(MC_JAR).toURI().toURL());
        } catch (InstantiationException | IllegalAccessException | IOException | URISyntaxException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || name == null) {
            return null;
        }
        if (ofTransformer == null) {
            // no initialization yet, I really don't know how to handle this. Hope for the best?
            return basicClass;
        }
        if (!remapper.map(name).equals(name)) {
            // NOTCH CLASS! This class most likely comes from the MC jar we injected into classpath and something is trying to load it
            // let it crash
            return null;
        }

        try {
            String classJvmName = name.replace(".", "/");

            String notchName = remapper.notchFromMcp(classJvmName);
            byte[] vanillaCode = extractVanillaBytecode(basicClass, notchName);

            Mutable<Boolean> isModified = new Mutable(false);

            byte[] ofTransformedCode = getOptifineTransformedBytecode(name, basicClass, notchName, vanillaCode, isModified);
            // deobfuscate OptiFine transformed code to MCP names
            // this attempts to transform all the code but it shouldn't be an issue
            ClassNode ofTransformedDeobfNode = toDeobfClassNode(ofTransformedCode);
            ClassNode vanillaDeobfNode = toDeobfClassNode(vanillaCode);
            ClassNode originalForgeNode = getClassNode(basicClass);

            Set<AccessChange> accessChanges = reconstructAccessTransformers(vanillaDeobfNode, originalForgeNode);

            applyAccessChanges(accessChanges, ofTransformedDeobfNode);

            if (name.equals("Reflector")) {
                addReflectorFix(ofTransformedDeobfNode);
            }
            // 1.7.10 has srg named strings there
            if (name.equals("EntityUtils")) {
                remapEntityUtils(ofTransformedDeobfNode);
            }
            ClassWriter classWriter = new ClassWriter(0);
            ofTransformedDeobfNode.accept(classWriter);
            byte[] output = classWriter.toByteArray();

            if (isModified.get() || (!transformedName.contains(".") || transformedName.startsWith("optifine") || transformedName.startsWith("net.optifine"))) {
                Utils.dumpBytecode(OptifineDevTweakerWrapper.CLASS_DUMP_LOCATION, transformedName, output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void remapEntityUtils(ClassNode ofTransformedDeobfNode) {
        MethodNode clinit = ofTransformedDeobfNode.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst()
                .orElseThrow(()->new RuntimeException("No <clinit>"));
        InsnList instructions = clinit.instructions;
        instructions.iterator().forEachRemaining(node -> {
            if (node instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) node).cst;
                if (cst instanceof String) {
                    cst = SrgMappings.getNameFromSrg((String) cst);
                }
                ((LdcInsnNode) node).cst = cst;
            }
        });
    }

    private void addReflectorFix(ClassNode ofTransformedDeobfNode) {
        MethodNode clinit = ofTransformedDeobfNode.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst()
                        .orElseThrow(()->new RuntimeException("No <clinit>"));
        AbstractInsnNode _return = null;
        for (AbstractInsnNode insn : clinit.instructions.toArray()) {
            if (insn.getOpcode() == RETURN) {
                _return = insn;
                break;
            }
        }
        clinit.instructions.insertBefore(_return, new MethodInsnNode(
                Opcodes.INVOKESTATIC, "ofdev/launchwrapper/UtilsLW", "fixReflector", "()V", false));
    }

    private void applyAccessChanges(Set<AccessChange> accessChanges, ClassNode node) {
        Map<String, MethodNode> methods = new HashMap<>();
        for (MethodNode method : node.methods) {
            methods.put(method.name + method.desc, method);
        }

        Map<String, FieldNode> fields = new HashMap<>();
        for (FieldNode field : node.fields) {
            fields.put(field.name + ";" + field.desc, field);
        }

        Set<MethodNode> nowOverrideable = new HashSet<>();

        for (AccessChange change : accessChanges) {
            switch (change.target) {
                case CLASS:
                    node.access = change.apply(node.access);
                    break;
                case FIELD:
                    FieldNode field = fields.get(change.name);
                    if (field != null) {
                        field.access = change.apply(field.access);
                    }
                    break;
                case METHOD:
                    MethodNode method = methods.get(change.name);
                    if (method != null) {
                        int newAccess = change.apply(method.access);

                        // constructors always use INVOKESPECIAL
                        if (!method.name.equals("<init>")) {
                            // if we changed from private to something else we need to replace all INVOKESPECIAL calls to this method with
                            // INVOKEVIRTUAL
                            // so that overridden methods will be called. Only need to scan this class, because obviously the method was private.
                            boolean wasPrivate = (method.access & ACC_PRIVATE) == ACC_PRIVATE;
                            boolean isNowPrivate = (newAccess & ACC_PRIVATE) == ACC_PRIVATE;

                            if (wasPrivate && !isNowPrivate) {
                                nowOverrideable.add(method);
                            }

                        }
                        method.access = newAccess;
                    }
                    break;
            }
        }

        replaceInvokeSpecial(node, nowOverrideable);
    }

    private void replaceInvokeSpecial(ClassNode clazz, Set<MethodNode> toReplace) {
        for (MethodNode method : clazz.methods) {
            for (Iterator<AbstractInsnNode> it = method.instructions.iterator(); it.hasNext(); ) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() == INVOKESPECIAL) {
                    MethodInsnNode mInsn = (MethodInsnNode) insn;
                    for (MethodNode n : toReplace) {
                        if (n.name.equals(mInsn.name) && n.desc.equals(mInsn.desc)) {
                            mInsn.setOpcode(INVOKEVIRTUAL);
                            break;
                        }
                    }
                }
            }
        }
    }

    private Set<AccessChange> reconstructAccessTransformers(ClassNode vanilla, ClassNode forge) {
        Set<AccessChange> changes = new HashSet<>();

        if ((vanilla.access & AccessChange.RELEVANT_BITS) != (forge.access & AccessChange.RELEVANT_BITS)) {
            changes.add(new AccessChange(AccessChange.Target.CLASS, vanilla.name, vanilla.access, forge.access));
        }


        Map<String, MethodNode> forgeMethods = new HashMap<>();
        for (MethodNode method : forge.methods) {
            forgeMethods.put(method.name + method.desc, method);
        }

        for (MethodNode vanillaMethod : vanilla.methods) {
            MethodNode forgeMethod = forgeMethods.get(vanillaMethod.name + vanillaMethod.desc);
            if (forgeMethod != null) {
                if (Modifier.isStatic(vanillaMethod.access) == Modifier.isStatic(forgeMethod.access)
                        && (vanillaMethod.access & AccessChange.RELEVANT_BITS) != (forgeMethod.access & AccessChange.RELEVANT_BITS)) {
                    changes.add(new AccessChange(AccessChange.Target.METHOD,
                            vanillaMethod.name + vanillaMethod.desc, vanillaMethod.access, forgeMethod.access));
                }
            }
        }

        Map<String, FieldNode> forgeFields = new HashMap<>();
        for (FieldNode field : forge.fields) {
            forgeFields.put(field.name + ";" + field.desc, field);
        }

        for (FieldNode vanillaField : vanilla.fields) {
            FieldNode forgeField = forgeFields.get(vanillaField.name + ";" + vanillaField.desc);
            if (forgeField != null) {
                if (Modifier.isStatic(vanillaField.access) == Modifier.isStatic(forgeField.access)
                        && (vanillaField.access & AccessChange.RELEVANT_BITS) != (forgeField.access & AccessChange.RELEVANT_BITS)) {
                    changes.add(new AccessChange(AccessChange.Target.FIELD,
                            vanillaField.name + ";" + vanillaField.desc, vanillaField.access, forgeField.access));
                }
            }
        }
        return changes;
    }

    private ClassNode toDeobfClassNode(byte[] code) {
        ClassReader classReader = new ClassReader(code);
        ClassNode transformedNode = new ClassNode(Opcodes.ASM5);
        RemappingClassAdapter remapAdapter = new OptifineDevAdapter(transformedNode);

        // 1.7.10 has a name conflict with superclass and the field shadows parent class field
        // but optifine user the parent class field to set it's own
        Remapper videoSettingsFixer = new Remapper() {
            @Override
            public String mapFieldName(String owner, String name, String desc) {
                if ((owner.startsWith("optifine") || owner.startsWith("shadersmod") || owner.equals("net/minecraft/client/gui/GuiVideoSettings") || owner.equals("bef")) && name.equals("fontRendererObj")) {
                    return "fontRendererObj_OF";
                }
                return super.mapFieldName(owner, name, desc);
            }
        };
        ClassRemapper finalRemapper = new ClassRemapper(remapAdapter, videoSettingsFixer);

        classReader.accept(finalRemapper, ClassReader.EXPAND_FRAMES);
        return transformedNode;
    }

    private byte[] getOptifineTransformedBytecode(String name, byte[] basicClass, String notchName, byte[] vanillaCode, Mutable<Boolean> isModified) {
        byte[] ofTransformedCode = name.startsWith("optifine") ? vanillaCode : ofTransformer.transform(notchName, notchName, vanillaCode);

        if (ofTransformedCode == vanillaCode) {
            ofTransformedCode = basicClass;
        } else {
            isModified.set(true);
        }
        return ofTransformedCode;
    }

    private byte[] extractVanillaBytecode(byte[] basicClass, String notchName) throws IOException {
        byte[] vanillaCode = basicClass;
        if (notchName != null) {
            Path classPath = mcJar.getPath(notchName.replace(".", "/") + ".class");
            if (Files.exists(classPath)) {
                return Files.readAllBytes(classPath);
            }
        }
        return vanillaCode;
    }

    private static ClassNode getClassNode(byte[] data) {
        ClassReader cr = new ClassReader(data);
        ClassNode cn = new ClassNode(Opcodes.ASM4);
        cr.accept(cn, ClassReader.EXPAND_FRAMES);
        return cn;
    }

    private static class AccessChange {

        public static final int RELEVANT_BITS = Modifier.PRIVATE | Modifier.PUBLIC | Modifier.PROTECTED | Modifier.FINAL;

        private final Target target;
        private final String name;
        private final boolean isStatic;

        private final Visibility newVisibility;
        private boolean changeFinal = false;
        private boolean markFinal = false;

        private AccessChange(Target target, String name, int oldMod, int newMod) {
            if (Modifier.isStatic(oldMod) != Modifier.isStatic(newMod)) {
                throw new IllegalArgumentException("Both targets must be either static or nonstatic!");
            }
            this.isStatic = Modifier.isStatic(oldMod);
            this.target = target;
            this.name = name;
            this.newVisibility = getModVisibility(newMod);
            if (Modifier.isFinal(oldMod) && !Modifier.isFinal(newMod)) {
                changeFinal = true;
                markFinal = false;
            } else if (!Modifier.isFinal(oldMod) && Modifier.isFinal(newMod)) {
                changeFinal = true;
                markFinal = true;
            }
        }

        int apply(int mod) {
            if (Modifier.isStatic(mod) != isStatic) {
                return mod;
            }

            Visibility t = newVisibility;
            int ret = (mod & ~7);

            switch (mod & 7) {
                case ACC_PRIVATE:
                    ret |= t.asInt();
                    break;
                case 0: // default
                    ret |= (t.asInt() != ACC_PRIVATE ? t.asInt() : 0 /* default */);
                    break;
                case ACC_PROTECTED:
                    ret |= (t.asInt() != ACC_PRIVATE && t.asInt() != 0 /* default */ ? t.asInt() : ACC_PROTECTED);
                    break;
                case ACC_PUBLIC:
                    ret |= (t.asInt() != ACC_PRIVATE && t.asInt() != 0 /* default */ && t.asInt() != ACC_PROTECTED ? t.asInt() : ACC_PUBLIC);
                    break;
                default:
                    throw new RuntimeException("The fuck?");
            }

            // Clear the "final" marker on fields only if specified in control field
            if (changeFinal) {
                if (markFinal) {
                    ret |= ACC_FINAL;
                } else {
                    ret &= ~ACC_FINAL;
                }
            }
            return ret;
        }

        private static Visibility getModVisibility(int modifiers) {
            if (Modifier.isPublic(modifiers)) {
                return Visibility.PUBLIC;
            }
            if (Modifier.isProtected(modifiers)) {
                return Visibility.PROTECTED;
            }
            if (isPrivate(modifiers)) {
                return Visibility.PRIVATE;
            }
            return Visibility.DEFAULT;
        }

        private enum Target {
            CLASS, METHOD, FIELD
        }

        private enum Visibility {
            PRIVATE, DEFAULT, PROTECTED, PUBLIC;

            public int set(int mod) {
                // remove existing modifiers
                mod &= ~(Modifier.PRIVATE | Modifier.PUBLIC | Modifier.PROTECTED);
                // apply new modifier
                switch (this) {
                    case DEFAULT:
                        break;
                    case PRIVATE:
                        mod |= Modifier.PRIVATE;
                        break;
                    case PROTECTED:
                        mod |= Modifier.PROTECTED;
                        break;
                    case PUBLIC:
                        mod |= Modifier.PUBLIC;
                        break;
                }
                return mod;
            }

            public int asInt() {
                switch (this) {
                    case PUBLIC:
                        return Modifier.PUBLIC;
                    case PROTECTED:
                        return Modifier.PROTECTED;
                    case DEFAULT:
                        return 0;
                    case PRIVATE:
                        return Modifier.PRIVATE;
                }
                throw new Error();
            }
        }
    }

    private static class Mutable<T> {
        T value;

        public Mutable(T value) {
            this.value = value;
        }

        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value;
        }
    }
}