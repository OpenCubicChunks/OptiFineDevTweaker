package ofdev.modlauncher;

import static ofdev.common.Utils.LOGGER;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.TransformationServiceDecorator;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.INameMappingService;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import ofdev.common.Utils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class OFDevRetransformer implements ITransformer<ClassNode> {

    private final Set<Target> targets;
    private final OfDevRemapper remapper;

    OFDevRetransformer(IEnvironment env) {
        Utils.checkAndNotifyExtractedOptifineJar(getClass().getClassLoader());
        this.targets = new HashSet<>(findOptiFineClasses(env));
        Optional<BiFunction<INameMappingService.Domain, String, String>> srgtomcp = env.findNameMapping("srg");
        if (!srgtomcp.isPresent()) {
            throw new IllegalStateException("No srgtomcp mappings found! Are you in dev environment?");
        }
        this.remapper = new OfDevRemapper(srgtomcp.get());
    }

    private static Collection<? extends Target> findOptiFineClasses(IEnvironment env) {
        List<Map<String, String>> modlist = env.getProperty(IEnvironment.Keys.MODLIST.get())
                .orElseThrow(() -> new IllegalStateException("modlist not found"));
        Map<String, String> optifine = modlist.stream().filter(x -> x.get("name").equals("OptiFine")).findAny()
                .orElseThrow(() -> new IllegalStateException("OptiFine not found"));
        Map<String, String> fml = modlist.stream().filter(x -> x.get("name").equals("fml")).findAny().orElse(null);
        String optifineFile = optifine.get("file");
        String fmlFile = fml == null ? "" : fml.get("file");
        LOGGER.info("Got OptiFine file name \"{}\"", optifineFile);
        // workaround for https://github.com/McModLauncher/securejarhandler/issues/20
        // NOTE: also do this is fml filename matches optifine file name, in that case the jar appears to be modlauncher jar
        // TODO: report a bug to whatever
        if (optifineFile.isEmpty() || optifineFile.equals(fmlFile)) {
            LOGGER.error("OptiFine file not found through API! Trying ModLauncher internals...");
            try {
                Field transformationServicesHandlerField = Launcher.class.getDeclaredField("transformationServicesHandler");
                transformationServicesHandlerField.setAccessible(true);
                Object transformationServicesHandler = transformationServicesHandlerField.get(Launcher.INSTANCE);
                Class<?> TransformationServicesHandler = Class.forName("cpw.mods.modlauncher.TransformationServicesHandler");
                Field serviceLookupField = TransformationServicesHandler.getDeclaredField("serviceLookup");
                serviceLookupField.setAccessible(true);
                @SuppressWarnings("unchecked") Map<String, TransformationServiceDecorator> serviceLookup =
                        (Map<String, TransformationServiceDecorator>) serviceLookupField.get(transformationServicesHandler);
                TransformationServiceDecorator optiFine = serviceLookup.get("OptiFine");
                Field serviceField = TransformationServiceDecorator.class.getDeclaredField("service");
                serviceField.setAccessible(true);
                ITransformationService service = (ITransformationService) serviceField.get(optiFine);
                Class<?> clazz = service.getClass();
                @SuppressWarnings("JavaReflectionMemberAccess") Method getModule = Class.class.getMethod("getModule");
                Object module = getModule.invoke(clazz);
                Class<?> Module = Class.forName("java.lang.Module");
                Method getLayer = Module.getMethod("getLayer");
                Object layer = getLayer.invoke(module);
                Method configurationMethod = Class.forName("java.lang.ModuleLayer").getMethod("configuration");
                Object configuration = configurationMethod.invoke(layer);
                Method getName = Module.getMethod("getName");
                String moduleName = (String) getName.invoke(module);
                Method findModule = Class.forName("java.lang.module.Configuration").getMethod("findModule", String.class);
                @SuppressWarnings("unchecked") Optional<Object> optiModule = (Optional<Object>) findModule.invoke(configuration, moduleName);
                Method referenceMethod = Class.forName("java.lang.module.ResolvedModule").getMethod("reference");
                assert optiModule.isPresent();
                Object reference = referenceMethod.invoke(optiModule.get());
                Method locationMethod = Class.forName("java.lang.module.ModuleReference").getMethod("location");
                @SuppressWarnings("unchecked") Optional<URI> location = (Optional<URI>) locationMethod.invoke(reference);
                URI uri = location.orElseThrow(() -> new IllegalStateException("No module location!"));
                LOGGER.error("OptiFine URI " + uri);
                if (uri.getScheme().equals("jar")) {
                    // TODO: instead of this roundabout way of extracting the path out of URI and then creating
                    //       a zip filesystem out of that, maybe try to create a zipfs directly from URI?
                    // in 1.20.4+ this somehow changed, now the URI looks like this:
                    // jar:file:///home/bartosz/Desktop/dev/java/Minecraft/forge-1.20.4-49.2-mdk/run/mods/OptiFine_1.20.4_HD_U_I7.jar!/
                    // everything after jar: is the scheme specific part
                    String schemeSpecific = uri.getRawSchemeSpecificPart();
                    if (!schemeSpecific.endsWith("!/") && !schemeSpecific.endsWith("!\\")) {
                        throw new IllegalStateException("Unexpected URI format");
                    }
                    String fileUri = schemeSpecific.substring(0, schemeSpecific.length() - 2);
                    optifineFile = Paths.get(new URI(fileUri).getPath()).getFileName().toString();
                } else if (uri.getScheme().equals("union")) {
                    // Mod URI looks like this:
                    // union:/home/bartosz/Desktop/dev/java/Minecraft/OptiFineDev/tests/forgemdk-1.19.4-45.1.0/run/mods/OptiFine_1.19.4_HD_U_I4.jar%23180!/
                    // and URI path looks like this:
                    // /home/bartosz/Desktop/dev/java/Minecraft/OptiFineDev/tests/forgemdk-1.19.4-45.1.0/run/mods/OptiFine_1.19.4_HD_U_I4.jar#180!/
                    String path = uri.getPath();
                    optifineFile = path.substring(0, path.lastIndexOf('#'));
                } else {
                    throw new IllegalStateException("Unsupported URI scheme for " + uri);
                }
                // On Windows the path begins with a "/" which causes problems when using Paths.get(), so strip it off
                // this is an absolute path but we don't actually nneed it to work as we only get filename
                if (optifineFile.startsWith("/")) { // why was this needed?
                    optifineFile = optifineFile.substring(1);
                }
                optifineFile = Paths.get(optifineFile).getFileName().toString();
            } catch (ReflectiveOperationException | URISyntaxException ex) {
                throw new IllegalStateException(ex);
            }
        }
        if (optifineFile.startsWith("/")) {
            optifineFile = optifineFile.substring(1);
        }
        Path gamedir = env.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElseThrow(() -> new IllegalStateException("gamedir not found"));

        Set<String> newTargets = new HashSet<>();
        try {
            Path ofPath = gamedir.resolve("mods").resolve(optifineFile).toRealPath();
            System.out.println("OptiFine file: " + ofPath);
            try (FileSystem fs = FileSystems.newFileSystem(ofPath, env.getClass().getClassLoader())) {
                for (Path root : fs.getRootDirectories()) {
                    try (Stream<Path> paths = Files.walk(root)) {
                        List<Path> files = paths.collect(Collectors.toList());
                        for (Path file : files) {
                            if (file.toString().endsWith(".class")) {
                                Path relative = root.relativize(file);
                                String name = relative.toString();
                                name = name.substring(0, name.length() - ".class".length());
                                if (name.startsWith("srg/")) {
                                    name = name.substring("srg/".length());
                                }
                                // fails on 1.18/1.18.1 forge (at least 39.0.9 and older), because modlauncher still uses ASM7 API version
                                // and this class triggers finding common superclasses
                                // where one of the classes is a JDK class that uses sealed classes
                                // related to https://github.com/McModLauncher/modlauncher/issues/74
                                if (name.equals("net/optifine/reflect/Reflector")) {
                                    continue;
                                }
                                // optifine package is transformers etc...
                                // net/minecraftforge is forge dummy classes for loading without forge
                                // notch/ 1.18+ for notch-obfuscated code
                                if (!name.startsWith("notch/") && !name.startsWith("optifine/") && !name.startsWith("net/minecraftforge")) {
                                    newTargets.add(name);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return newTargets.stream().map(Target::targetClass).collect(Collectors.toList());
    }

    @Override public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        ClassNode output = new ClassNode();
        ClassRemapper classRemapper = new ClassRemapper(output, remapper);
        input.accept(classRemapper);
        try {
            ClassWriter cw = new ClassWriter(0); // don't compute frames/maxs, this code is only for decompiler and IDE
            output.accept(cw);
            Utils.dumpBytecode(OFDevTransformationService.CLASS_DUMP_LOCATION, output.name, cw.toByteArray());
        } catch (Throwable t) {
            LOGGER.catching(t); // in case there is anything broken about the code, it's better for it to fail in modlauncher than here
        }
        return output;
    }

    @Override public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override public Set<Target> targets() {
        return targets;
    }

    @Override public String[] labels() {
        return new String[]{"OptiFineDevRetransform"};
    }
}
