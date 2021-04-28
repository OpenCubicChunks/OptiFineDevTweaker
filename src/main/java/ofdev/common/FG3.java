package ofdev.common;

import java.nio.file.Path;
import java.nio.file.Paths;

public class FG3 {
    public static Path findObfMcJar(boolean isClient) {
        String requestedJar = System.getProperty("ofdev.mcjar");
        if (requestedJar != null) {
            return Paths.get(requestedJar);
        }

        String mcVersion = System.getenv("MC_VERSION");
        String dist = isClient ? "client" : "server";

        return Paths.get(System.getProperty("user.home"),
                ".gradle/caches/forge_gradle/minecraft_repo/versions",
                mcVersion,
                dist + ".jar"
        );
    }
}
