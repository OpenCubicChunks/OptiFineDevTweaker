package ofdev.modlauncher;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.INameMappingService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

public class OFDevRetransformer implements ITransformer<ClassNode> {

    private final Set<Target> targets;
    private final OfDevRemapper remapper;

    OFDevRetransformer(IEnvironment env) {
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
        String optifineFile = optifine.get("file");
        if (optifineFile.startsWith("/")) {
            optifineFile = optifineFile.substring(1);
        }
        Path gamedir = env.getProperty(IEnvironment.Keys.GAMEDIR.get()).orElseThrow(() -> new IllegalStateException("gamedir not found"));

        List<Target> newTargets = new ArrayList<>();
        try {
            Path ofPath = gamedir.resolve("mods").resolve(optifineFile).toRealPath();
            try (FileSystem fs = FileSystems.newFileSystem(ofPath, env.getClass().getClassLoader())) {
                for (Path root : fs.getRootDirectories()) {
                    try (Stream<Path> paths = Files.walk(root)) {
                        List<Path> files = paths.collect(Collectors.toList());
                        for (Path file : files) {
                            if (file.toString().endsWith(".class")) {
                                Path relative = root.relativize(file);
                                String name = relative.toString();
                                name = name.substring(0, name.length() - ".class".length());
                                if (!name.startsWith("optifine/")) {
                                    newTargets.add(Target.targetClass(name));
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return newTargets;
    }

    @Nonnull @Override public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        ClassNode output = new ClassNode();
        ClassRemapper classRemapper = new ClassRemapper(output, remapper);
        input.accept(classRemapper);
        return output;
    }

    @Nonnull @Override public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Nonnull @Override public Set<Target> targets() {
        return targets;
    }

    @Override public String[] labels() {
        return new String[]{"OptiFineDevRetransform"};
    }
}
