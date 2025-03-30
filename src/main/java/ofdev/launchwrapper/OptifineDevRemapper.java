package ofdev.launchwrapper;

import static ofdev.common.Utils.LOGGER;

import LZMA.LzmaInputStream;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import ofdev.common.Utils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// this class is a modified version of FMLDeobfuscatingRemapper
public class OptifineDevRemapper extends Remapper {

    private static final MethodHandle getPatchedResource;
    public static final OptifineDevRemapper NOTCH_MCP;
    static {
        try {
            Class<?> cpm;
            try {
                cpm = Class.forName("net.minecraftforge.fml.common.patcher.ClassPatchManager");
                LOGGER.info("Found ClassPatchManager in 1.8-1.12.2 package");
            } catch (ClassNotFoundException ex) {
                cpm = Class.forName("cpw.mods.fml.common.patcher.ClassPatchManager"); // 1.7.10
                LOGGER.info("Found ClassPatchManager in 1.7.10 package");
            }
            Object classPathManager = cpm.getField("INSTANCE").get(null);

            Method m = cpm.getMethod("getPatchedResource", String.class, String.class, LaunchClassLoader.class);
            getPatchedResource = MethodHandles.lookup().unreflect(m).bindTo(classPathManager);
        } catch (Throwable t) {
            throw new RuntimeException("Unable to fine ClassPatchManager.getPatchedResource method", t);
        }
        String notch2mcpProp = System.getProperty("net.minecraftforge.gradle.GradleStart.srg.notch-mcp");
        if (notch2mcpProp != null) {
            LOGGER.info("Found notch-mcp mappings file " + notch2mcpProp);
            NOTCH_MCP = new OptifineDevRemapper(notch2mcpProp);
        } else {
            String srg2mcp = System.getProperty("net.minecraftforge.gradle.GradleStart.srg.srg-mcp");
            if (srg2mcp == null)
                throw new IllegalStateException("Current version of ForgeGradle is not supported! Please report us!");
            LOGGER.info("Found srg-mcp mappings file " + srg2mcp);
            NOTCH_MCP = new OptifineDevRemapper(Utils.mcVersion(), srg2mcp);
        }
    }

    private Map<String, String> classNameMap, classNameMapInverse;

    private Map<String, Map<String, String>> rawFieldMaps;
    private Map<String, Map<String, String>> rawMethodMaps;

    private Map<String, Map<String, String>> fieldNameMaps;
    private Map<String, Map<String, String>> methodNameMaps;

    private LaunchClassLoader classLoader;

    private OptifineDevRemapper(String property) {
        classNameMap = new HashMap<>();
        classNameMapInverse = new HashMap<>();
        setup(Launch.classLoader, property);
    }

    private OptifineDevRemapper(String minecraftVersion, String srg2mcp) {
        classNameMap = new HashMap<>();
        classNameMapInverse = new HashMap<>();
        setupForFG3(Launch.classLoader, minecraftVersion, srg2mcp);
    }

    public void setup(LaunchClassLoader classLoader, String gradleStartProp) {
        this.classLoader = classLoader;
        try {
            List<String> srgList;

            srgList = Files.readAllLines(Paths.get(gradleStartProp), StandardCharsets.UTF_8);
            rawMethodMaps = new HashMap<>();
            rawFieldMaps = new HashMap<>();
            Map<String, String> classMap = new HashMap<>();
            Map<String, String> classMapInverse = new HashMap<>();
            parseSrg(srgList, rawMethodMaps, rawFieldMaps, classMap, classMapInverse, true);
            classNameMap = classMap;
            classNameMapInverse = classMapInverse;
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        methodNameMaps = new HashMap<>();
        fieldNameMaps = new HashMap<>();
    }

    private static List<String> readLines(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<String> result = new ArrayList<>();
            for (;;) {
                String line = reader.readLine();
                if (line == null)
                    break;
                result.add(line);
            }
            return result;
        }
    }

    // setup for ForgeGradle 3.x or later
    public void setupForFG3(LaunchClassLoader classLoader, String minecraftVersion, String srg2mcp) {
        this.classLoader = classLoader;
        try {
            LOGGER.info("Loading Notch2Srg data from forge jar!");
            // deobfuscation_data contains notch2srg mapping
            String dataName = "deobfuscation_data-" + minecraftVersion + ".lzma";
            List<String> notch2srgLines = readLines(new LzmaInputStream(
                    Launch.class.getClassLoader().getResourceAsStream(dataName)));
            LOGGER.info("Found " + notch2srgLines.size() + " lines of notch2srg data!");

            Map<String, Map<String, String>> notch2srgMethodMaps = new HashMap<>();
            Map<String, Map<String, String>> notch2srgFieldMaps = new HashMap<>();
            Map<String, String> notch2srgClassMap = new HashMap<>();
            Map<String, String> notch2srgClassMapInverse = new HashMap<>();
            parseSrg(notch2srgLines, notch2srgMethodMaps, notch2srgFieldMaps, notch2srgClassMap, notch2srgClassMapInverse, true);

            List<String> srg2mcpLines = Files.readAllLines(Paths.get(srg2mcp), StandardCharsets.UTF_8);
            Map<String, Map<String, String>> srg2mcpMethodMaps = new HashMap<>();
            Map<String, Map<String, String>> srg2mcpFieldMaps = new HashMap<>();
            Map<String, String> srg2mcpClassMap = new HashMap<>();
            Map<String, String> srg2mcpClassMapInverse = new HashMap<>();
            parseSrg(srg2mcpLines, srg2mcpMethodMaps, srg2mcpFieldMaps, srg2mcpClassMap, srg2mcpClassMapInverse, false);

            rawMethodMaps = joinMaps(notch2srgMethodMaps, srg2mcpMethodMaps, notch2srgClassMap);
            rawFieldMaps = joinMaps(notch2srgFieldMaps, srg2mcpFieldMaps, notch2srgClassMap);
            classNameMap = joinMap(notch2srgClassMap, srg2mcpClassMap);
            classNameMapInverse = joinMap(srg2mcpClassMapInverse, notch2srgClassMapInverse);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        methodNameMaps = new HashMap<>();
        fieldNameMaps = new HashMap<>();
    }

    private Map<String, Map<String, String>> joinMaps(
            Map<String, Map<String, String>> first,
            Map<String, Map<String, String>> second,
            Map<String, String> classNameMap
    ) {
        Map<String, Map<String, String>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> classToMapEntry : first.entrySet()) {
            String firstClass = classToMapEntry.getKey();
            String secondClass = classNameMap.get(firstClass);
            if (secondClass == null) continue;
            Map<String, String> secondMap = second.get(secondClass);
            if (secondMap == null) continue;
            Map<String, String> resultMap = new HashMap<>();
            result.put(firstClass, resultMap);
            for (Map.Entry<String, String> entry : classToMapEntry.getValue().entrySet()) {
                String firstKey = entry.getKey();
                String secondNewName = secondMap.get(entry.getValue());
                if (secondNewName == null) continue;
                resultMap.put(firstKey, secondNewName);
            }
        }
        return result;
    }

    private Map<String, String> joinMap(Map<String, String> first, Map<String, String> second) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : first.entrySet()) {
            String value = second.get(entry.getValue());
            if (value == null) continue;
            result.put(entry.getKey(), value);
        }
        return result;
    }

    // not static for parseField
    public void parseSrg(
            List<String> srgList,
            Map<String, Map<String, String>> rawMethodMaps, 
            Map<String, Map<String, String>> rawFieldMaps,
            Map<String, String> classMap,
            Map<String, String> classMapInverse,
            boolean withSignatureKey
    ) {
        for (String line : srgList) {
            String[] parts = line.split("[:\\s]+");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = parts[i].trim();
            }
            String typ = parts[0];
            if ("CL".equals(typ)) {
                parseClass(classMap, classMapInverse, parts);
            } else if ("MD".equals(typ)) {
                parseMethod(parts, rawMethodMaps, withSignatureKey);
            } else if ("FD".equals(typ)) {
                parseField(parts, rawFieldMaps, withSignatureKey);
            }
        }
    }

    // not static for getFieldType
    private void parseField(String[] parts, Map<String, Map<String, String>> rawFieldMaps, boolean withSignatureKey) {
        String oldSrg = parts[1];
        int lastOld = oldSrg.lastIndexOf('/');
        String cl = oldSrg.substring(0, lastOld);
        String oldName = oldSrg.substring(lastOld + 1);
        String newSrg = parts[2];
        int lastNew = newSrg.lastIndexOf('/');
        String newName = newSrg.substring(lastNew + 1);
        if (!rawFieldMaps.containsKey(cl)) {
            rawFieldMaps.put(cl, new HashMap<>());
        }
        if (withSignatureKey) {
            String fieldType = getFieldType(cl, oldName);
            // We might be in mcp named land, where in fact the name is "new"
            if (fieldType == null) {
                fieldType = getFieldType(cl, newName);
            }
            rawFieldMaps.get(cl).put(oldName + ":" + fieldType, newName);
            rawFieldMaps.get(cl).put(oldName + ":null", newName);
        } else {
            rawFieldMaps.get(cl).put(oldName, newName);
        }
    }

    /*
     * Cache the field descriptions for classes so we don't repeatedly reload the same data again and again
     */
    private final Map<String, Map<String, String>> fieldDescriptions = new HashMap<>();

    // Cache null values so we don't waste time trying to recompute classes with no field or method maps
    private final Set<String> negativeCacheMethods = new HashSet<>();
    private final Set<String> negativeCacheFields = new HashSet<>();

    private String getFieldType(String owner, String name) {
        if (fieldDescriptions.containsKey(owner)) {
            return fieldDescriptions.get(owner).get(name);
        }
        synchronized (fieldDescriptions) {
            try {
                byte[] classBytes = (byte[]) getPatchedResource.invoke(owner, map(owner).replace('/', '.'), classLoader);
                if (classBytes == null) {
                    return null;
                }
                ClassReader cr = new ClassReader(classBytes);
                ClassNode classNode = new ClassNode();
                cr.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                Map<String, String> resMap = new HashMap<>();
                for (FieldNode fieldNode : classNode.fields) {
                    resMap.put(fieldNode.name, fieldNode.desc);
                }
                fieldDescriptions.put(owner, resMap);
                return resMap.get(name);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
            return null;
        }
    }

    private static void parseClass(Map<String, String> classMap, Map<String, String> classMapInverse, String[] parts) {
        classMap.put(parts[1], parts[2]);
        classMapInverse.put(parts[2], parts[1]);
    }

    private static void parseMethod(String[] parts, Map<String, Map<String, String>> rawMethodMaps, boolean withSignatureKey) {
        String oldSrg = parts[1];
        int lastOld = oldSrg.lastIndexOf('/');
        String cl = oldSrg.substring(0, lastOld);
        String oldName = oldSrg.substring(lastOld + 1);
        String sig = parts[2];
        String newSrg = parts[3];
        int lastNew = newSrg.lastIndexOf('/');
        String newName = newSrg.substring(lastNew + 1);
        if (!rawMethodMaps.containsKey(cl)) {
            rawMethodMaps.put(cl, new HashMap<>());
        }
        if (withSignatureKey) {
            rawMethodMaps.get(cl).put(oldName + sig, newName);
        } else {
            rawMethodMaps.get(cl).put(oldName, newName);
        }
    }

    String mapMemberFieldName(String owner, String name, String desc) {
        String remappedName = mapFieldName(owner, name, desc, true);
        storeMemberFieldMapping(owner, name, desc, remappedName);
        return remappedName;
    }

    private void storeMemberFieldMapping(String owner, String name, String desc, String remappedName) {
        Map<String, String> fieldMap = getRawFieldMap(owner);

        String key = name + ":" + desc;
        String altKey = name + ":null";

        if (!fieldMap.containsKey(key)) {
            fieldMap.put(key, remappedName);
            fieldMap.put(altKey, remappedName);

            // Alternatively, maps could be made mutable and we could just set the relevant entry, saving
            // the need to regenerate the super map each time
            fieldNameMaps.remove(owner);
        }
    }

    @Override
    public String mapFieldName(String owner, String name, String desc) {
        return mapFieldName(owner, name, desc, false);
    }

    String mapFieldName(String owner, String name, String desc, boolean raw) {
        if (classNameMap == null || classNameMap.isEmpty()) {
            return name;
        }

        Map<String, String> fieldMap = getFieldMap(owner, raw);
        String ret = fieldMap != null && fieldMap.containsKey(name + ":" + desc) ? fieldMap.get(name + ":" + desc) :
                fieldMap != null && fieldMap.containsKey(name + ":null") ? fieldMap.get(name + ":null") : name;
        //System.out.println("Mapping field " + owner + "." + name + "(" + desc + ") raw=" + raw + " ---> " + ret);
        return ret;
    }

    @Override
    public String map(String typeName) {
        if (classNameMap == null || classNameMap.isEmpty()) {
            return typeName;
        }
        if (typeName.endsWith(";")) {
            // descriptor... somehow
            Type t = Type.getType(typeName);
            if (t.getSort() == Type.ARRAY) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < t.getDimensions(); i++) {
                    sb.append('[');
                }
                sb.append('L');
                return sb.append(map(t.getElementType().getInternalName())).append(';').toString();
            } else {
                return 'L' + map(t.getInternalName()) + ';';
            }
        }
        if (classNameMap.containsKey(typeName)) {
            //System.out.println("Mapping " + typeName + " to " + classNameMap.get(typeName));
            return classNameMap.get(typeName);
        }
        int dollarIdx = typeName.lastIndexOf('$');
        if (dollarIdx > -1) {
            String ret = map(typeName.substring(0, dollarIdx)) + "$" + typeName.substring(dollarIdx + 1);
            //System.out.println("Mapping " + typeName + " to " + ret);
            return ret;
        }
        //System.out.println("Mapping " + typeName + " to " + typeName);
        return typeName;
    }

    public String unmap(String typeName) {
        if (classNameMap == null || classNameMap.isEmpty()) {
            return typeName;
        }
        if (typeName.endsWith(";")) {
            // descriptor... somehow
            Type t = Type.getType(typeName);
            if (t.getSort() == Type.ARRAY) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < t.getDimensions(); i++) {
                    sb.append('[');
                }
                sb.append('L');
                return sb.append(unmap(t.getElementType().getInternalName())).append(';').toString();
            } else {
                return 'L' + unmap(t.getInternalName()) + ';';
            }
        }
        if (classNameMap.containsValue(typeName)) {
            return classNameMapInverse.get(typeName);
        }
        int dollarIdx = typeName.lastIndexOf('$');
        if (dollarIdx > -1) {
            return unmap(typeName.substring(0, dollarIdx)) + "$" + typeName.substring(dollarIdx + 1);
        }
        return typeName;
    }


    @Override
    public String mapMethodName(String owner, String name, String desc) {
        if (classNameMap == null || classNameMap.isEmpty()) {
            return name;
        }
        //System.out.println("Mapping method " + owner + "." + name + "(" + desc + ")");
        Map<String, String> methodMap = getMethodMap(owner);
        String methodDescriptor = name + desc;
        return methodMap != null && methodMap.containsKey(methodDescriptor) ? methodMap.get(methodDescriptor) : name;
    }

    @Override
    public String mapSignature(String signature, boolean typeSignature) {
        // JDT decorates some lambdas with this and SignatureReader chokes on it
        if (signature != null && signature.contains("!*")) {
            return null;
        }
        return super.mapSignature(signature, typeSignature);
    }

    private Map<String, String> getRawFieldMap(String className) {
        if (!rawFieldMaps.containsKey(className)) {
            rawFieldMaps.put(className, new HashMap<>());
        }
        return rawFieldMaps.get(className);
    }

    private Map<String, String> getFieldMap(String className, boolean raw) {
        if (raw) {
            return getRawFieldMap(className);
        }

        if (!fieldNameMaps.containsKey(className) && !negativeCacheFields.contains(className)) {
            findAndMergeSuperMaps(unmap(className));
            findAndMergeSuperMaps(map(className));
            if (!fieldNameMaps.containsKey(className)) {
                negativeCacheFields.add(className);
            }
        }
        return fieldNameMaps.get(className);
    }

    private Map<String, String> getMethodMap(String className) {
        if (!methodNameMaps.containsKey(className) && !negativeCacheMethods.contains(className)) {
            findAndMergeSuperMaps(unmap(className));
            findAndMergeSuperMaps(map(className));
            if (!methodNameMaps.containsKey(className)) {
                negativeCacheMethods.add(className);
            }

        }
        return methodNameMaps.get(className);
    }

    private void findAndMergeSuperMaps(String name) {
        try {
            String superName = null;
            String[] interfaces = new String[0];
            byte[] classBytes = (byte[]) getPatchedResource.invoke(name, map(name), classLoader);
            if (classBytes != null) {
                ClassReader cr = new ClassReader(classBytes);
                superName = cr.getSuperName();
                interfaces = cr.getInterfaces();
            }
            String notchName = OptifineDevRemapper.NOTCH_MCP.notchFromMcpOrDefault(name);
            String notchSuperName = OptifineDevRemapper.NOTCH_MCP.notchFromMcpOrDefault(superName);
            String[] notchInterfaces = Arrays.stream(interfaces).map(OptifineDevRemapper.NOTCH_MCP::notchFromMcpOrDefault).toArray(String[]::new);
            mergeSuperMaps(notchName, notchSuperName, notchInterfaces);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public void mergeSuperMaps(String name, String superName, String[] interfaces) {
        //        System.out.printf("Computing super maps for %s: %s %s\n", name, superName, Arrays.asList(interfaces));
        if (classNameMap == null || classNameMap.isEmpty()) {
            return;
        }
        // Skip Object
        if (superName == null || superName.isEmpty()) {
            return;
        }

        List<String> allParents = new ArrayList<>();
        allParents.add(superName);
        allParents.addAll(Arrays.asList(interfaces));
        // generate maps for all parent objects
        for (String parentThing : allParents) {
            if (!fieldNameMaps.containsKey(parentThing)) {
                findAndMergeSuperMaps(unmap(parentThing));
                findAndMergeSuperMaps(map(parentThing));
            }
        }
        Map<String, String> methodMap = new HashMap<>();
        Map<String, String> fieldMap = new HashMap<>();
        for (String parentThing : allParents) {
            if (methodNameMaps.containsKey(parentThing)) {
                methodMap.putAll(methodNameMaps.get(parentThing));
            }
            if (fieldNameMaps.containsKey(parentThing)) {
                fieldMap.putAll(fieldNameMaps.get(parentThing));
            }
        }
        if (rawMethodMaps.containsKey(name)) {
            methodMap.putAll(rawMethodMaps.get(name));
        }
        if (rawFieldMaps.containsKey(name)) {
            fieldMap.putAll(rawFieldMaps.get(name));
        }
        methodNameMaps.put(name, new HashMap<>(methodMap));
        fieldNameMaps.put(name, new HashMap<>(fieldMap));
        //        System.out.printf("Maps: %s %s\n", name, methodMap);
    }

    public String notchFromMcp(String className) {
        return classNameMapInverse.get(className);
    }

    public String notchFromMcpOrDefault(String className) {
        return classNameMapInverse.getOrDefault(className, className);
    }

    @SuppressWarnings("unused") public String getStaticFieldType(String oldType, String oldName, String newType, String newName) {
        String fType = getFieldType(newType, newName);
        if (oldType.equals(newType)) {
            return fType;
        }
        Map<String, String> newClassMap = fieldDescriptions.computeIfAbsent(newType, k -> new HashMap<>());
        newClassMap.put(newName, fType);
        return fType;
    }
}
