package ofdev.launchwrapper;

import net.minecraft.launchwrapper.Launch;
import ofdev.common.Utils;

public class UtilsLW {

    // Note: all of these reflection methods are expected to be very slow, and not used too frequently
    public static Class<?> loadClassLW(String name) {
        try {
            Class<?> cl = Launch.classLoader.findClass(name);
            if (cl.getClassLoader() != Launch.classLoader) {
                throw new RuntimeException(
                        "Class " + name + " has been loaded with classloader " + cl.getClassLoader() + " instead of " + Launch.classLoader);
            }
            return cl;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    // invoked from ASMed optifine code
    @SuppressWarnings("unused") public static void fixReflector() {
        try {
            Class<?> reflector = UtilsLW.loadClassLW("Reflector");

            try {
                reflector.getDeclaredField("ForgeBlock_getLightOpacity");
            } catch (NoSuchFieldException ignored) {
                // 1.7.10 doesn't have this
                return;
            }
            Object ForgeBlock = Utils.getFieldValue(reflector, null, "ForgeBlock");
            Object ForgeBlock_getLightOpacity = Utils.getFieldValue(reflector, null, "ForgeBlock_getLightOpacity");
            Object ForgeBlock_getLightValue = Utils.getFieldValue(reflector, null, "ForgeBlock_getLightValue");

            Class<?> ReflectorMethod = ForgeBlock_getLightOpacity.getClass();
            Class<?> IBlockState = UtilsLW.loadClassLW("net.minecraft.block.state.IBlockState");
            Class<?> IBlockAccess = UtilsLW.loadClassLW("net.minecraft.world.IBlockAccess");
            Class<?> BlockPos = UtilsLW.loadClassLW("net.minecraft.util.math.BlockPos");

            if (Utils.invokeMethod(ReflectorMethod, ForgeBlock_getLightOpacity, "getTargetMethod") == null) {
                Object new_ForgeBlock_getLightOpacity = Utils.construct(ReflectorMethod, ForgeBlock, "getLightOpacity", new Class[]{
                        IBlockState, IBlockAccess, BlockPos
                });
                Utils.setFieldValue(reflector, "ForgeBlock_getLightOpacity", null, new_ForgeBlock_getLightOpacity);
            }
            if (Utils.invokeMethod(ReflectorMethod, ForgeBlock_getLightValue, "getTargetMethod") == null) {
                Object new_ForgeBlock_getLightOpacity = Utils.construct(ReflectorMethod, ForgeBlock, "getLightValue", new Class[]{
                        IBlockState, IBlockAccess, BlockPos
                });
                Utils.setFieldValue(reflector, "ForgeBlock_getLightValue", null, new_ForgeBlock_getLightOpacity);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
