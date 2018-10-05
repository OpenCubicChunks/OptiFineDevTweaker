package ofdev;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.patcher.ClassPatchManager;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableBiMap.Builder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import javax.annotation.Nullable;

// this class is a modified version of FMLDeobfuscatingRemapper
public class OptifineDevRemapper extends Remapper {

    public static final OptifineDevRemapper NOTCH_MCP =
            new OptifineDevRemapper(System.getProperty("net.minecraftforge.gradle.GradleStart.srg.notch-mcp"));

    private BiMap<String, String> classNameBiMap;

    private Map<String, Map<String, String>> rawFieldMaps;
    private Map<String, Map<String, String>> rawMethodMaps;

    private Map<String, Map<String, String>> fieldNameMaps;
    private Map<String, Map<String, String>> methodNameMaps;

    private LaunchClassLoader classLoader;

    private OptifineDevRemapper(String property) {
        classNameBiMap = ImmutableBiMap.of();
        setup(Launch.classLoader, property);
    }

    public void setup(LaunchClassLoader classLoader, String gradleStartProp) {
        this.classLoader = classLoader;
        try {
            List<String> srgList;

            srgList = Files.readLines(new File(gradleStartProp), StandardCharsets.UTF_8);
            rawMethodMaps = Maps.newHashMap();
            rawFieldMaps = Maps.newHashMap();
            Builder<String, String> builder = ImmutableBiMap.builder();
            Splitter splitter = Splitter.on(CharMatcher.anyOf(": ")).omitEmptyStrings().trimResults();
            for (String line : srgList) {
                String[] parts = Iterables.toArray(splitter.split(line), String.class);
                String typ = parts[0];
                if ("CL".equals(typ)) {
                    parseClass(builder, parts);
                } else if ("MD".equals(typ)) {
                    parseMethod(parts);
                } else if ("FD".equals(typ)) {
                    parseField(parts);
                }
            }
            classNameBiMap = builder.build();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        methodNameMaps = Maps.newHashMapWithExpectedSize(rawMethodMaps.size());
        fieldNameMaps = Maps.newHashMapWithExpectedSize(rawFieldMaps.size());
    }

    public boolean isRemappedClass(String className) {
        return !map(className).equals(className);
    }

    private void parseField(String[] parts) {
        String oldSrg = parts[1];
        int lastOld = oldSrg.lastIndexOf('/');
        String cl = oldSrg.substring(0, lastOld);
        String oldName = oldSrg.substring(lastOld + 1);
        String newSrg = parts[2];
        int lastNew = newSrg.lastIndexOf('/');
        String newName = newSrg.substring(lastNew + 1);
        if (!rawFieldMaps.containsKey(cl)) {
            rawFieldMaps.put(cl, Maps.<String, String>newHashMap());
        }
        String fieldType = getFieldType(cl, oldName);
        // We might be in mcp named land, where in fact the name is "new"
        if (fieldType == null) {
            fieldType = getFieldType(cl, newName);
        }
        rawFieldMaps.get(cl).put(oldName + ":" + fieldType, newName);
        rawFieldMaps.get(cl).put(oldName + ":null", newName);
    }

    /*
     * Cache the field descriptions for classes so we don't repeatedly reload the same data again and again
     */
    private final Map<String, Map<String, String>> fieldDescriptions = Maps.newHashMap();

    // Cache null values so we don't waste time trying to recompute classes with no field or method maps
    private Set<String> negativeCacheMethods = Sets.newHashSet();
    private Set<String> negativeCacheFields = Sets.newHashSet();

    @Nullable
    private String getFieldType(String owner, String name) {
        if (fieldDescriptions.containsKey(owner)) {
            return fieldDescriptions.get(owner).get(name);
        }
        synchronized (fieldDescriptions) {
            try {
                byte[] classBytes = ClassPatchManager.INSTANCE.getPatchedResource(owner, map(owner).replace('/', '.'), classLoader);
                if (classBytes == null) {
                    return null;
                }
                ClassReader cr = new ClassReader(classBytes);
                ClassNode classNode = new ClassNode();
                cr.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                Map<String, String> resMap = Maps.newHashMap();
                for (FieldNode fieldNode : classNode.fields) {
                    resMap.put(fieldNode.name, fieldNode.desc);
                }
                fieldDescriptions.put(owner, resMap);
                return resMap.get(name);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    private void parseClass(Builder<String, String> builder, String[] parts) {
        builder.put(parts[1], parts[2]);
    }

    private void parseMethod(String[] parts) {
        String oldSrg = parts[1];
        int lastOld = oldSrg.lastIndexOf('/');
        String cl = oldSrg.substring(0, lastOld);
        String oldName = oldSrg.substring(lastOld + 1);
        String sig = parts[2];
        String newSrg = parts[3];
        int lastNew = newSrg.lastIndexOf('/');
        String newName = newSrg.substring(lastNew + 1);
        if (!rawMethodMaps.containsKey(cl)) {
            rawMethodMaps.put(cl, Maps.<String, String>newHashMap());
        }
        rawMethodMaps.get(cl).put(oldName + sig, newName);
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
    public String mapFieldName(String owner, String name, @Nullable String desc) {
        return mapFieldName(owner, name, desc, false);
    }

    String mapFieldName(String owner, String name, @Nullable String desc, boolean raw) {
        if (classNameBiMap == null || classNameBiMap.isEmpty()) {
            return name;
        }
        Map<String, String> fieldMap = getFieldMap(owner, raw);
        return fieldMap != null && fieldMap.containsKey(name + ":" + desc) ? fieldMap.get(name + ":" + desc) :
                fieldMap != null && fieldMap.containsKey(name + ":null") ? fieldMap.get(name + ":null") : name;
    }

    @Override
    public String map(String typeName) {
        if (classNameBiMap == null || classNameBiMap.isEmpty()) {
            return typeName;
        }
        if (classNameBiMap.containsKey(typeName)) {
            return classNameBiMap.get(typeName);
        }
        int dollarIdx = typeName.lastIndexOf('$');
        if (dollarIdx > -1) {
            return map(typeName.substring(0, dollarIdx)) + "$" + typeName.substring(dollarIdx + 1);
        }
        return typeName;
    }

    public String unmap(String typeName) {
        if (classNameBiMap == null || classNameBiMap.isEmpty()) {
            return typeName;
        }

        if (classNameBiMap.containsValue(typeName)) {
            return classNameBiMap.inverse().get(typeName);
        }
        int dollarIdx = typeName.lastIndexOf('$');
        if (dollarIdx > -1) {
            return unmap(typeName.substring(0, dollarIdx)) + "$" + typeName.substring(dollarIdx + 1);
        }
        return typeName;
    }


    @Override
    public String mapMethodName(String owner, String name, String desc) {
        if (classNameBiMap == null || classNameBiMap.isEmpty()) {
            return name;
        }
        Map<String, String> methodMap = getMethodMap(owner);
        String methodDescriptor = name + desc;
        return methodMap != null && methodMap.containsKey(methodDescriptor) ? methodMap.get(methodDescriptor) : name;
    }

    @Override
    @Nullable
    public String mapSignature(String signature, boolean typeSignature) {
        // JDT decorates some lambdas with this and SignatureReader chokes on it
        if (signature != null && signature.contains("!*")) {
            return null;
        }
        return super.mapSignature(signature, typeSignature);
    }

    private Map<String, String> getRawFieldMap(String className) {
        if (!rawFieldMaps.containsKey(className)) {
            rawFieldMaps.put(className, Maps.<String, String>newHashMap());
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
            byte[] classBytes = ClassPatchManager.INSTANCE.getPatchedResource(name, map(name), classLoader);
            if (classBytes != null) {
                ClassReader cr = new ClassReader(classBytes);
                superName = cr.getSuperName();
                interfaces = cr.getInterfaces();
            }
            String notchName = OptifineDevRemapper.NOTCH_MCP.notchFromMcpOrDefault(name);
            String notchSuperName = OptifineDevRemapper.NOTCH_MCP.notchFromMcpOrDefault(superName);
            String[] notchInterfaces = Arrays.asList(interfaces).stream().map(OptifineDevRemapper.NOTCH_MCP::notchFromMcpOrDefault).toArray(String[]::new);
            mergeSuperMaps(notchName, notchSuperName, notchInterfaces);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void mergeSuperMaps(String name, @Nullable String superName, String[] interfaces) {
        //        System.out.printf("Computing super maps for %s: %s %s\n", name, superName, Arrays.asList(interfaces));
        if (classNameBiMap == null || classNameBiMap.isEmpty()) {
            return;
        }
        // Skip Object
        if (Strings.isNullOrEmpty(superName)) {
            return;
        }

        List<String> allParents = ImmutableList.<String>builder().add(superName).addAll(Arrays.asList(interfaces)).build();
        // generate maps for all parent objects
        for (String parentThing : allParents) {
            if (!fieldNameMaps.containsKey(parentThing)) {
                findAndMergeSuperMaps(unmap(parentThing));
                findAndMergeSuperMaps(map(parentThing));
            }
        }
        Map<String, String> methodMap = Maps.newHashMap();
        Map<String, String> fieldMap = Maps.newHashMap();
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
        methodNameMaps.put(name, ImmutableMap.copyOf(methodMap));
        fieldNameMaps.put(name, ImmutableMap.copyOf(fieldMap));
        //        System.out.printf("Maps: %s %s\n", name, methodMap);
    }

    public Set<String> getObfedClasses() {
        return ImmutableSet.copyOf(classNameBiMap.keySet());
    }

    public String notchFromMcp(String className) {
        return classNameBiMap.inverse().get(className);
    }

    public String notchFromMcpOrDefault(String className) {
        return classNameBiMap.inverse().getOrDefault(className, className);
    }

    @Nullable
    public String getStaticFieldType(String oldType, String oldName, String newType, String newName) {
        String fType = getFieldType(newType, newName);
        if (oldType.equals(newType)) {
            return fType;
        }
        Map<String, String> newClassMap = fieldDescriptions.computeIfAbsent(newType, k -> Maps.newHashMap());
        newClassMap.put(newName, fType);
        return fType;
    }
}