package ofdev.common;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

public class Utils {

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
    public static void dumpBytecode(Path loc, String className, byte[] code) throws IOException {
        String subPath = className.replaceAll("\\.", "/") + ".class";
        Path location = loc.resolve(subPath);
        mkdirs(location.getParent());
        Files.write(location, code, StandardOpenOption.CREATE);
    }

    private static void mkdirs(Path location) throws IOException {
        if (!Files.exists(location)) {
            mkdirs(location.getParent());
            Files.createDirectory(location);
        }
    }
}
