package ofdev.launchwrapper;

import net.minecraft.launchwrapper.Launch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Utils {

    public static final String mcVersion() {
        // because javac authors decided to do an optimization for this one specialcase thing of compiletime expressions
        // we need to reflectively access a *public static final* field just so that it doesn't get inlined at compiletime
        // and so this can be MC-version independent
        return getFieldValue(loadClassLW("net.minecraftforge.common.ForgeVersion"), null, "mcVersion");
    }

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

    public static <T, C> T getFieldValue(Class<C> clazz, C obj, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(obj);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T, C> T invokeMethod(Class<? extends C> clazz, C obj, String methodName, Object... args) {
        try {
            Method method = findMethod(clazz, methodName, args);
            method.setAccessible(true);
            return (T) method.invoke(obj, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public static Method findMethod(Class<?> cl, String name, Object... argValues) {
        String toFindString = name + "(" +
                Arrays.stream(argValues).map(x -> x.getClass().toString()).reduce((a, b) -> a + ", " + b).orElse("") + ")";

        Method found = null;
        StringBuilder errorString = null;
        searching:
        for (Method m : getAllMethods(cl)) {
            if (!m.getName().equals(name)) {
                continue;
            }
            if (m.getParameterCount() != argValues.length) {
                continue;
            }
            Class<?>[] argTypes = m.getParameterTypes();
            for (int i = 0; i < argValues.length; i++) {
                if (!argTypes[i].isAssignableFrom(argValues[i].getClass())) {
                    continue searching;
                }
            }
            if (found != null) {
                if (errorString == null) {
                    String candidateArgs = Arrays.stream(found.getParameterTypes()).map(Object::toString).reduce((a, b) -> a + ", " + b).orElse("");
                    errorString = new StringBuilder("Ambiguous method for specified name and types: " + toFindString + ", found candidate methods\n" +
                                    found.getReturnType() + " " + name + "(" + candidateArgs + ")\n");
                }
                String candidateArgs = Arrays.stream(m.getParameterTypes()).map(Object::toString).reduce((a, b) -> a + ", " + b).orElse("");
                errorString.append(m.getReturnType()).append(" ").append(name).append("(").append(candidateArgs).append(")\n");
            }
            found = m;
        }
        if (errorString != null) {
            throw new RuntimeException(errorString.toString());
        }

        if (found == null) {
            throw new RuntimeException(new NoSuchMethodException(toFindString));
        }
        return found;
    }

    private static Set<Method> getAllMethods(Class<?> cl) {
        Set<Method> methods = new HashSet<>(Arrays.asList(cl.getDeclaredMethods()));
        if (cl.getSuperclass() != null) {
            methods.addAll(getAllMethods(cl.getSuperclass()));
        }
        for (Class<?> i : cl.getInterfaces()) {
            methods.addAll(getAllMethods(i));
        }
        return methods;
    }

    public static void setFieldValue(Class<?> clazz, String fieldName, Object instance, Object newObject) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(instance, newObject);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T construct(Class<T> clazz, Object... args) {
        try {
            Constructor<?> constr = findConstructor(clazz, args);
            return (T) constr.newInstance(args);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    public static Constructor<?> findConstructor(Class<?> cl, Object... argValues) {
        String toFindString = "<init>(" +
                Arrays.stream(argValues).map(x -> x.getClass().toString()).reduce((a, b) -> a + ", " + b).orElse("") + ")";

        Constructor<?> found = null;
        StringBuilder errorString = null;
        searching:
        for (Constructor<?> c : cl.getDeclaredConstructors()) {
            if (c.getParameterCount() != argValues.length) {
                continue;
            }
            Class<?>[] argTypes = c.getParameterTypes();
            for (int i = 0; i < argValues.length; i++) {
                if (!argTypes[i].isAssignableFrom(argValues[i].getClass())) {
                    continue searching;
                }
            }
            if (found != null) {
                if (errorString == null) {
                    String candidateArgs = Arrays.stream(found.getParameterTypes()).map(Object::toString).reduce((a, b) -> a + ", " + b).orElse("");
                    errorString = new StringBuilder("Ambiguous constructor for specified arg types: " + toFindString +
                            ", found candidate constructors\n<init>(" + candidateArgs + ")\n");
                }
                String candidateArgs = Arrays.stream(c.getParameterTypes()).map(Object::toString).reduce((a, b) -> a + ", " + b).orElse("");
                errorString.append("<init>(").append(candidateArgs).append(")\n");
            }
            found = c;
        }
        if (errorString != null) {
            throw new RuntimeException(errorString.toString());
        }

        if (found == null) {
            throw new RuntimeException(new NoSuchMethodException(toFindString));
        }
        return found;
    }

    // invoked from ASMed optifine code
    public static void fixReflector() {
        try {
            Class<?> reflector = Utils.loadClassLW("Reflector");

            Object ForgeBlock = Utils.getFieldValue(reflector, null, "ForgeBlock");
            Object ForgeBlock_getLightOpacity = Utils.getFieldValue(reflector, null, "ForgeBlock_getLightOpacity");
            Object ForgeBlock_getLightValue = Utils.getFieldValue(reflector, null, "ForgeBlock_getLightValue");

            Class<?> ReflectorMethod = ForgeBlock_getLightOpacity.getClass();
            Class IBlockState = Utils.loadClassLW("net.minecraft.block.state.IBlockState");
            Class IBlockAccess = Utils.loadClassLW("net.minecraft.world.IBlockAccess");
            Class BlockPos = Utils.loadClassLW("net.minecraft.util.math.BlockPos");

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
