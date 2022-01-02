package ofdev.common;

import java.nio.file.Path;
import java.nio.file.Paths;

public class FG3 {
    public static Path findObfMcJar(String mcVersion, boolean isClient) {
        String requestedJar = System.getProperty("ofdev.mcjar");
        if (requestedJar != null) {
            return Paths.get(requestedJar);
        }

        // because MC_VERSION has invalid value in forge 1.12.2 2855, we can't use MC_VERSION generally
        //String mcVersion = System.getenv("MC_VERSION");
        String dist = isClient ? "client" : "server";
        return Utils.gradleHome().resolve("caches/forge_gradle/minecraft_repo/versions").resolve(mcVersion).resolve(dist + ".jar");
    }
}
