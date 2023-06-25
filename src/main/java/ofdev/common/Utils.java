package ofdev.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Utils {
    public static final Logger LOGGER = LogManager.getLogger("OptiFineDevTweaker");

    public static String mcVersion() {
        // environment variable from new FG?
        String envVersion = System.getenv("MC_VERSION");
        if (envVersion != null) {
            LOGGER.info("Found Minecraft version {} from environment variable MC_VERSION", envVersion);
            return envVersion;
        }
        Throwable ex1;
        // try 1.13+ FMLLoader
        try {
            Class<?> FMLLoader = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
            Class<?> VersionInfo = Class.forName("net.minecraftforge.fml.loading.VersionInfo");
            Object versionInfo = FMLLoader.getMethod("versionInfo").invoke(null);
            String mcVersion = (String) VersionInfo.getMethod("mcVersion").invoke(versionInfo);
            LOGGER.info("Found Minecraft version {} from 1.13+ FMLLoader VersionInfo", mcVersion);
            return mcVersion;
        } catch (ReflectiveOperationException e) {
            ex1 = e;
        }
        Throwable ex2;
        // 1.8 - 1.12.2 Loader MC_VERSION - this FML class so can be loaded early
        try {
            String mcVersion = getFieldValue(Class.forName("net.minecraftforge.fml.common.Loader"), null, "MC_VERSION");
            LOGGER.info("Found Minecraft version {} from 1.8-1.12.2 FML Loader.MC_VERSION", mcVersion);
            return mcVersion;
        } catch (ClassNotFoundException e) {
            ex2 = e;
        }
        Throwable ex3;
        // 1.7.10 - different FML package
        try {
            String mcVersion = getFieldValue(Class.forName("cpw.mods.fml.common.Loader"), null, "MC_VERSION");
            LOGGER.info("Found Minecraft version {} from 1.7.10 FML Loader.MC_VERSION", mcVersion);
            return mcVersion;
        } catch (ClassNotFoundException e) {
            ex3 = e;
        }
        RuntimeException error = new IllegalStateException("Could not find Minecraft version!");
        error.addSuppressed(ex1);
        error.addSuppressed(ex2);
        error.addSuppressed(ex3);
        throw error;
    }

    public static Path findMinecraftJar() {
        String requestedJar = System.getProperty("ofdev.mcjar");
        if (requestedJar != null) {
            Path path = Paths.get(requestedJar);
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("Provided Minecraft jar path " + requestedJar + " doesn't exist!");
            }
            Path absolutePath = path.toAbsolutePath();
            LOGGER.info("Found Minecraft jar {} from ofdev.mcjar property", absolutePath);
            return absolutePath;
        }
        Path gradleHome = Utils.gradleHome();
        if (!Files.exists(gradleHome)) {
            throw new IllegalStateException("Gradle home doesn't exist at " + gradleHome.toAbsolutePath());
        }
        String mcVersion = mcVersion();

        // FG1 - FG2
        Path oldFgPath = gradleHome.resolve("caches/minecraft/net/minecraft/minecraft")
                        .resolve(mcVersion).resolve("minecraft-" + mcVersion + ".jar").toAbsolutePath();
        if (Files.exists(oldFgPath)) {
            return oldFgPath;
        }
        // RetroFuturaGradle https://github.com/GTNewHorizons/RetroFuturaGradle
        //caches/retro_futura_gradle/mc-vanilla/1.12.2/
        Path rfgPath = gradleHome.resolve("caches/retro_futura_gradle/mc-vanilla")
                .resolve(mcVersion).resolve(mcVersion + ".jar").toAbsolutePath();
        if (Files.exists(rfgPath)) {
            return rfgPath;
        }
        // We don't support running server with OptiFine
        // FG3 - FG5
        Path newFgPath = Utils.gradleHome().resolve("caches/forge_gradle/minecraft_repo/versions")
                .resolve(mcVersion).resolve("client.jar").toAbsolutePath();
        if (Files.exists(newFgPath)) {
            return newFgPath;
        }
        throw new IllegalStateException("Could not fine Minecraft jar file. Try specifying Minecraft jar location with -Dofdev.mcjar=path\n\t"
                + "Attempted locations:\n\t" + oldFgPath + "\n\t" + newFgPath);
    }

    public static Path gradleHome() {
        String gradleHome = System.getenv("GRADLE_USER_HOME");
        if (gradleHome == null || gradleHome.isEmpty()){
            gradleHome = System.getProperty("user.home") + "/.gradle";
        }
        return Paths.get(gradleHome);
    }

    public static void rm(Path path) throws IOException {
        if (Files.exists(path)) {
            if (!Files.isDirectory(path)) {
                Files.delete(path);
            } else {
                Files.walkFileTree(path, new FileVisitor<Path>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.TERMINATE;
                    }

                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }

    private static void mkdirs(Path location) throws IOException {
        if (!Files.exists(location)) {
            mkdirs(location.getParent());
            Files.createDirectory(location);
        }
    }

    public static void dumpBytecode(Path loc, String className, byte[] code) throws IOException {
        String subPath = className.replaceAll("\\.", "/") + ".class";
        Path location = loc.resolve(subPath);
        mkdirs(location.getParent());
        Files.write(location, code, StandardOpenOption.CREATE);
    }

    // reflection

    @SuppressWarnings("unchecked") public static <T, C> T getFieldValue(Class<C> clazz, C obj, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(obj);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked") public static <T, C> T invokeMethod(Class<? extends C> clazz, C obj, String methodName, Object... args) {
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

    @SuppressWarnings("unchecked") public static <T> T construct(Class<T> clazz, Object... args) {
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

}
