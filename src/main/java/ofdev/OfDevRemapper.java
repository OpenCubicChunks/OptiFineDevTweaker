package ofdev;

import cpw.mods.modlauncher.api.INameMappingService;
import org.objectweb.asm.commons.Remapper;

import java.util.function.BiFunction;

public class OfDevRemapper extends Remapper {

    private final BiFunction<INameMappingService.Domain, String, String> srg2mcp;

    OfDevRemapper(BiFunction<INameMappingService.Domain, String, String> srg2mcp) {
        this.srg2mcp = srg2mcp;
    }

    @Override public String mapMethodName(final String owner, final String name, final String descriptor) {
        return srg2mcp.apply(INameMappingService.Domain.METHOD, name);
    }

    @Override public String mapFieldName(final String owner, final String name, final String descriptor) {
        return srg2mcp.apply(INameMappingService.Domain.FIELD, name);
    }

    @Override public String map(final String internalName) {
        return internalName;
    }
}
