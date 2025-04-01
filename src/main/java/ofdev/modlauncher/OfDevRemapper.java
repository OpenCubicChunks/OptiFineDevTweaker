package ofdev.modlauncher;

import cpw.mods.modlauncher.api.INameMappingService;
import org.objectweb.asm.commons.Remapper;

import java.util.function.BiFunction;

public class OfDevRemapper extends Remapper {

    // TODO: this is needed for 1.21.x, figure out how to handle this automatically
    public static final boolean SKIP_REMAP_HACKS = System.getProperty("ofdev.skipRemapHacks", "false").equalsIgnoreCase("true");

    private final BiFunction<INameMappingService.Domain, String, String> srg2mcp;

    OfDevRemapper(BiFunction<INameMappingService.Domain, String, String> srg2mcp) {
        this.srg2mcp = srg2mcp;
    }

    @Override public String mapInvokeDynamicMethodName(String name, String desc) {
        return srg2mcp.apply(INameMappingService.Domain.METHOD, name);
    }

    /*@Override*/ @SuppressWarnings("unused") public String mapRecordComponentName(String owner, String name, String descriptor) {
        return srg2mcp.apply(INameMappingService.Domain.METHOD, name);
    }

    @Override public String mapMethodName(final String owner, final String name, final String descriptor) {
        String newName = applyConflictResolutionHacks(owner, name);
        String method = srg2mcp.apply(INameMappingService.Domain.METHOD, newName);
        if (method.equals(newName)) {
            // record components are technically methods but mapped as fields
            method = srg2mcp.apply(INameMappingService.Domain.FIELD, newName);
        }
        return method;
    }

    private static String applyConflictResolutionHacks(String owner, String name) {
        if (SKIP_REMAP_HACKS) {
            return name;
        }
        if (owner.equals("net/minecraft/client/world/ClientWorld") && name.equals("onEntityRemoved")) {
            return "onEntityRemoved_OF";
        }
        if (owner.equals("net/minecraft/client/renderer/model/BakedQuad") && name.equals("getSprite")) {
            return "getSprite_OF";
        }
        if (owner.equals("net/minecraft/client/model/geom/ModelPart") && name.equals("getChild")) {
            return "getChild_OF";
        }
        // 1.20.1:
        if (owner.equals("net/minecraft/client/OptionInstance$Enum") && name.equals("codec")) {
            return "codec_OF";
        }
        if (owner.equals("net/minecraft/client/OptionInstance$AltEnum") && name.equals("valueSetter")) {
            return "valueSetter_OF";
        }
        if (owner.equals("net/minecraft/client/OptionInstance$AltEnum") && name.equals("codec")) {
            return "codec_OF";
        }
        if (owner.equals("net/minecraft/client/OptionInstance$LazyEnum") && name.equals("codec")) {
            return "codec_OF";
        }
        if (owner.equals("net/minecraft/client/resources/model/ModelBakery") && name.equals("loadBlockModel")) {
            return "loadBlockModel_OF";
        }
        return name;
    }

    @Override public String mapFieldName(final String owner, final String name, final String descriptor) {
        return srg2mcp.apply(INameMappingService.Domain.FIELD, name);
    }

    @Override public String map(final String internalName) {
        return internalName;
    }
}
