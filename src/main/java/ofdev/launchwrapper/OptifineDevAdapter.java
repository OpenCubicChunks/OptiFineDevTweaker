package ofdev.launchwrapper;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.RemappingMethodAdapter;

import java.util.Arrays;
import java.util.List;

// this class is a modified version of FMLRemappingAdapter
@SuppressWarnings("deprecation")
public class OptifineDevAdapter extends RemappingClassAdapter {

    public OptifineDevAdapter(ClassVisitor cv) {
        super(cv, OptifineDevRemapper.NOTCH_MCP);
    }

    private static final List<Handle> META_FACTORIES = Arrays.asList(
            new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                    false),
            new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "altMetafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                            + "Ljava/lang/invoke/CallSite;",
                    false)
    );

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        if (interfaces == null) {
            interfaces = new String[0];
        }
        String notchName = OptifineDevRemapper.NOTCH_MCP.notchFromMcpOrDefault(name);
        String notchSuperName = OptifineDevRemapper.NOTCH_MCP.notchFromMcpOrDefault(superName);
        String[] notchInterfaces = Arrays.stream(interfaces).map(OptifineDevRemapper.NOTCH_MCP::notchFromMcpOrDefault).toArray(String[]::new);
        OptifineDevRemapper.NOTCH_MCP.mergeSuperMaps(notchName, notchSuperName, notchInterfaces);
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        OptifineDevRemapper remapper = OptifineDevRemapper.NOTCH_MCP;
        FieldVisitor fv = cv.visitField(access,
                remapper.mapMemberFieldName(className, name, desc),
                remapper.mapDesc(desc), remapper.mapSignature(signature, true),
                remapper.mapValue(value));
        return createRemappingFieldAdapter(fv);
    }

    @Override
    protected MethodVisitor createRemappingMethodAdapter(int access, String newDesc, MethodVisitor mv) {
        return new OptifineDevAdapter.StaticFixingMethodVisitor(access, newDesc, mv, remapper);
    }

    private static class StaticFixingMethodVisitor extends RemappingMethodAdapter {

        public StaticFixingMethodVisitor(int access, String desc, MethodVisitor mv, Remapper remapper) {
            super(access, desc, mv, remapper);
        }

        @Override
        public void visitFieldInsn(int opcode, String originalType, String originalName, String desc) {
            // This method solves the problem of a static field reference changing type. In all probability it is a
            // compatible change, however we need to fix up the desc to point at the new type
            String type = remapper.mapType(originalType);
            String fieldName = remapper.mapFieldName(originalType, originalName, desc);
            String newDesc = remapper.mapDesc(desc);
            if (opcode == Opcodes.GETSTATIC /*&& type.startsWith("net/minecraft/") && newDesc.startsWith("Lnet/minecraft/")*/) {
                String replDesc = OptifineDevRemapper.NOTCH_MCP.getStaticFieldType(originalType, originalName, type, fieldName);
                if (replDesc != null) {
                    newDesc = remapper.mapDesc(replDesc);
                }
            }
            // super.super
            if (mv != null) {
                mv.visitFieldInsn(opcode, type, fieldName, newDesc);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
            // Special case lambda metaFactory to get new name
            if (META_FACTORIES.contains(bsm)) {
                String owner = Type.getReturnType(desc).getInternalName();
                String odesc = ((Type) bsmArgs[0])
                        .getDescriptor(); // First constant argument is "samMethodType - Signature and return type of method to be implemented by
                // the function object."
                name = remapper.mapMethodName(owner, name, odesc);
            }

            super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
        }
    }
}