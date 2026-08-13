package com.github.obhen233.core.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import com.github.obhen233.util.JsonUtils;

/**
 * Generate SPI metadata JSON for diatom-core.
 * Scans the com.github.obhen233.spi package and generates a JSON file
 * describing all SPI interfaces and their methods.
 */
public class SpiMetadataGenerator {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SpiMetadataGenerator <output-dir> [version]");
            System.exit(1);
        }

        Path outputDir = Paths.get(args[0]);
        Files.createDirectories(outputDir);

        Path outputFile = outputDir.resolve("diatom-spi.json");
        String version = args.length > 1 ? args[1] : "1.0.0";
        String json = generateSpiMetadata(version);
        Files.write(outputFile, json.getBytes(StandardCharsets.UTF_8));

        System.out.println("Generated SPI metadata: " + outputFile);
    }

    public static String generateSpiMetadata(String version) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("coreVersion", version);
        metadata.put("generatedAt", java.time.Instant.now().toString());

        // SPI interfaces
        List<Map<String, Object>> interfaces = new ArrayList<>();

        // Scan SPI package
        String spiPackage = "com.github.obhen233.spi";
        ClassLoader loader = SpiMetadataGenerator.class.getClassLoader();

        // Dynamically scan SPI package for all public interfaces
        // This eliminates the need to hardcode interface names when new SPI types are added
        Set<Class<?>> spiInterfaceClasses = discoverSpiInterfaces(spiPackage, loader);
        for (Class<?> iface : spiInterfaceClasses) {
            if (iface.isInterface() && Modifier.isPublic(iface.getModifiers())) {
                Map<String, Object> ifaceInfo = new LinkedHashMap<>();
                ifaceInfo.put("name", iface.getName());
                ifaceInfo.put("simpleName", iface.getSimpleName());

                // Get Javadoc description if available
                ifaceInfo.put("description", getInterfaceDescription(iface));

                // Methods
                List<Map<String, Object>> methods = new ArrayList<>();
                for (Method method : iface.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())) {
                        Map<String, Object> methodInfo = new LinkedHashMap<>();
                        methodInfo.put("name", method.getName());
                        methodInfo.put("returnType", method.getReturnType().getName());
                        methodInfo.put("returnTypeSimple", method.getReturnType().getSimpleName());

                        // Parameters
                        List<Map<String, String>> params = new ArrayList<>();
                        for (Class<?> paramType : method.getParameterTypes()) {
                            Map<String, String> param = new LinkedHashMap<>();
                            param.put("type", paramType.getName());
                            param.put("simpleType", paramType.getSimpleName());
                            params.add(param);
                        }
                        methodInfo.put("parameters", params);

                        // Exceptions
                        List<String> exceptions = new ArrayList<>();
                        for (Class<?> ex : method.getExceptionTypes()) {
                            exceptions.add(ex.getName());
                        }
                        methodInfo.put("exceptions", exceptions);

                        methods.add(methodInfo);
                    }
                }
                ifaceInfo.put("methods", methods);

                interfaces.add(ifaceInfo);
            }
        }

        metadata.put("interfaces", interfaces);
        metadata.put("customToolApi", buildCustomToolApiMetadata());

        // Service implementations (from META-INF/services)
        Map<String, Object> services = new LinkedHashMap<>();
        metadata.put("services", services);

        // Format output
        ObjectMapper mapper = JsonUtils.getMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper.writeValueAsString(metadata);
    }

    private static String getVersion() {
        Properties props = new Properties();
        try (InputStream in = SpiMetadataGenerator.class.getClassLoader()
                .getResourceAsStream("version.properties")) {
            if (in != null) {
                props.load(in);
                return props.getProperty("version", "1.0.0");
            }
        } catch (Exception e) {
            // Ignore
        }
        return "1.0.0";
    }

    private static Map<String, Object> buildCustomToolApiMetadata() {
        Map<String, Object> api = new LinkedHashMap<>();

        Map<String, Object> registrar = new LinkedHashMap<>();
        registrar.put("interface", "com.github.obhen233.spi.ToolRegistrar");
        registrar.put("recipe", Arrays.asList(
                "Implement com.github.obhen233.spi.ToolRegistrar in the custom module.",
                "Implement registerTools(ToolRegistry registry).",
                "Inside registerTools, call registry.scanObject(new YourTools())."
        ));
        api.put("toolRegistrar", registrar);

        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("class", "com.github.obhen233.core.tool.ToolRegistry");
        registry.put("methods", Collections.singletonList("void scanObject(Object obj)"));
        registry.put("note", "scanObject registers methods annotated with @ToolMethod on the supplied object.");
        api.put("toolRegistry", registry);

        Map<String, Object> annotation = new LinkedHashMap<>();
        annotation.put("class", "com.github.obhen233.core.tool.annotation.ToolMethod");
        annotation.put("fields", Arrays.asList(
                "name",
                "description",
                "parametersSchema",
                "readOnly",
                "checkWorkspaceBoundary",
                "requiresConfirmation",
                "riskLevel",
                "confirmationTemplate",
                "riskDescriptionTemplate"
        ));
        api.put("toolMethodAnnotation", annotation);

        api.put("java8MinimalExample",
                "package com.github.obhen233.custom;\n\n" +
                "import com.github.obhen233.core.tool.ToolRegistry;\n" +
                "import com.github.obhen233.core.tool.annotation.ToolMethod;\n" +
                "import com.github.obhen233.spi.ToolRegistrar;\n\n" +
                "public class ExampleToolRegistrar implements ToolRegistrar {\n" +
                "    @Override\n" +
                "    public void registerTools(ToolRegistry registry) {\n" +
                "        registry.scanObject(new ExampleTools());\n" +
                "    }\n\n" +
                "    public static class ExampleTools {\n" +
                "        @ToolMethod(\n" +
                "                name = \"example_tool\",\n" +
                "                description = \"Example custom tool\",\n" +
                "                parametersSchema = \"{\\\"type\\\":\\\"object\\\",\" +\n" +
                "                        \"\\\"properties\\\":{\\\"message\\\":{\\\"type\\\":\\\"string\\\"}}}\",\n" +
                "                readOnly = true)\n" +
                "        public String exampleTool(String argsJson) {\n" +
                "            return \"ok\";\n" +
                "        }\n" +
                "    }\n" +
                "}\n");

        api.put("java8Notes", Arrays.asList(
                "Use Java 8 syntax only.",
                "Do not use text blocks, Map.of, List.of, var, records, or switch expressions.",
                "Write JSON schemas as escaped Java string concatenation."
        ));
        return api;
    }

    private static String getInterfaceDescription(Class<?> iface) {
        // Simple description based on interface name
        String name = iface.getSimpleName();
        if (name.endsWith("Registrar")) {
            return "SPI interface for registering " + name.replace("Registrar", "").toLowerCase() + " components";
        } else if (name.endsWith("Policy")) {
            return "SPI interface for " + name.replace("Policy", "").toLowerCase() + " policy";
        } else if (name.endsWith("Hook")) {
            return "SPI interface for " + name.replace("Hook", "").toLowerCase() + " lifecycle hooks";
        } else if (name.endsWith("Provider")) {
            return "SPI interface for providing " + name.replace("Provider", "").toLowerCase() + " components";
        } else if (name.endsWith("Customizer")) {
            return "SPI interface for customizing " + name.replace("Customizer", "").toLowerCase();
        } else if (name.endsWith("Factory")) {
            return "SPI interface for creating " + name.replace("Factory", "").toLowerCase() + " instances";
        } else if (name.endsWith("Cache")) {
            return "SPI interface for caching";
        }
        return "SPI interface";
    }

    /**
     * Dynamically discover all public SPI interfaces in the given package.
     * Uses ServiceLoader to find SPI interfaces via META-INF/services files,
     * then falls back to package scanning for any interface in the spi package.
     */
    private static Set<Class<?>> discoverSpiInterfaces(String spiPackage, ClassLoader loader) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();

        // Method 1: Use ServiceLoader to discover SPI interfaces from META-INF/services
        // This finds interfaces that have at least one implementation registered
        try {
            File spiDir = new File(loader.getResource(spiPackage.replace('.', '/')).getFile());
            if (spiDir.exists() && spiDir.isDirectory()) {
                File[] files = spiDir.listFiles((dir, name) -> name.endsWith(".class"));
                if (files != null) {
                    for (File file : files) {
                        String className = spiPackage + "." + file.getName().replace(".class", "");
                        try {
                            Class<?> clazz = Class.forName(className, false, loader);
                            if (clazz.isInterface() && Modifier.isPublic(clazz.getModifiers())) {
                                interfaces.add(clazz);
                            }
                        } catch (Exception e) {
                            // Skip classes that can't be loaded
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Fall back to ServiceLoader approach
        }

        // Method 2: Scan classpath for all files in the SPI package
        // This ensures interfaces without implementations are also discovered
        try {
            String spiPath = spiPackage.replace('.', '/');
            Enumeration<URL> resources = loader.getResources(spiPath);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if ("file".equals(resource.getProtocol())) {
                    File spiDir = new File(resource.getFile());
                    if (spiDir.exists() && spiDir.isDirectory()) {
                        scanForInterfaces(spiDir, spiPackage, loader, interfaces);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore scanning errors
        }

        return interfaces;
    }

    /**
     * Recursively scan a directory for interface files.
     */
    private static void scanForInterfaces(File dir, String packageName, ClassLoader loader, Set<Class<?>> interfaces) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanForInterfaces(file, packageName + "." + file.getName(), loader, interfaces);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                try {
                    Class<?> clazz = Class.forName(className, false, loader);
                    if (clazz.isInterface() && Modifier.isPublic(clazz.getModifiers())) {
                        interfaces.add(clazz);
                    }
                } catch (Exception e) {
                    // Skip classes that can't be loaded
                }
            }
        }
    }
}
