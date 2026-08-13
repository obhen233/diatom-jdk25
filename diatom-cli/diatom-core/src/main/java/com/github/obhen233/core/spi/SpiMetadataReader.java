package com.github.obhen233.core.spi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.github.obhen233.util.JsonUtils;

/**
 * Read and parse SPI metadata from diatom-core JAR.
 * The diatom-spi.json file is embedded in the diatom-core JAR at META-INF/diatom-spi.json
 * and contains information about all SPI interfaces and their methods.
 */
public class SpiMetadataReader {

    private static final Logger logger = LoggerFactory.getLogger(SpiMetadataReader.class);
    private static final String SPI_METADATA_PATH = "META-INF/diatom-spi.json";

    private final SpiMetadata metadata;

    public SpiMetadataReader(SpiMetadata metadata) {
        this.metadata = metadata;
    }

    /**
     * Load SPI metadata from a diatom-core JAR file.
     */
    public static SpiMetadataReader loadFromJar(Path coreJarPath) throws IOException {
        if (!Files.exists(coreJarPath)) {
            throw new FileNotFoundException("Core JAR not found: " + coreJarPath);
        }

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(coreJarPath.toFile())) {
            java.util.jar.JarEntry entry = jar.getJarEntry(SPI_METADATA_PATH);
            if (entry == null) {
                throw new IOException("SPI metadata not found in core JAR: " + SPI_METADATA_PATH);
            }

            try (InputStream in = jar.getInputStream(entry)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    bos.write(buffer, 0, len);
                }
                String json = bos.toString(StandardCharsets.UTF_8.name());
                SpiMetadata metadata = parseMetadata(json);
                return new SpiMetadataReader(metadata);
            }
        }
    }

    private static SpiMetadata parseMetadata(String json) throws IOException {
        ObjectMapper mapper = JsonUtils.getMapper();
        return mapper.readValue(json, SpiMetadata.class);
    }

    public SpiMetadata getMetadata() {
        return metadata;
    }

    public String getCoreVersion() {
        return metadata.coreVersion;
    }

    /**
     * Get all SPI interface names.
     */
    public List<String> getSpiInterfaceNames() {
        List<String> names = new ArrayList<>();
        if (metadata.interfaces != null) {
            for (SpiInterface iface : metadata.interfaces) {
                names.add(iface.name);
            }
        }
        return names;
    }

    /**
     * Get SPI interface info by name.
     */
    public SpiInterface getSpiInterface(String interfaceName) {
        if (metadata.interfaces != null) {
            for (SpiInterface iface : metadata.interfaces) {
                if (iface.name.equals(interfaceName)) {
                    return iface;
                }
            }
        }
        return null;
    }

    /**
     * Compare SPI metadata between two versions and return changes.
     */
    public static SpiDiff compare(SpiMetadataReader oldReader, SpiMetadataReader newReader) {
        SpiDiff diff = new SpiDiff();
        diff.oldVersion = oldReader.getCoreVersion();
        diff.newVersion = newReader.getCoreVersion();

        Map<String, SpiInterface> oldInterfaces = new HashMap<>();
        Map<String, SpiInterface> newInterfaces = new HashMap<>();

        if (oldReader.metadata.interfaces != null) {
            for (SpiInterface iface : oldReader.metadata.interfaces) {
                oldInterfaces.put(iface.name, iface);
            }
        }
        if (newReader.metadata.interfaces != null) {
            for (SpiInterface iface : newReader.metadata.interfaces) {
                newInterfaces.put(iface.name, iface);
            }
        }

        // Find removed interfaces
        for (String name : oldInterfaces.keySet()) {
            if (!newInterfaces.containsKey(name)) {
                diff.removedInterfaces.add(name);
            }
        }

        // Find added interfaces
        for (String name : newInterfaces.keySet()) {
            if (!oldInterfaces.containsKey(name)) {
                diff.addedInterfaces.add(name);
                // Also track methods of new interfaces as added
                SpiInterface newIface = newInterfaces.get(name);
                if (newIface != null && newIface.methods != null) {
                    for (SpiMethod method : newIface.methods) {
                        diff.addedMethods.add(name + "." + method.name);
                    }
                }
            } else {
                // Compare methods
                SpiInterface oldIface = oldInterfaces.get(name);
                SpiInterface newIface = newInterfaces.get(name);
                compareMethods(oldIface, newIface, diff);
            }
        }

        return diff;
    }

    private static void compareMethods(SpiInterface oldIface, SpiInterface newIface, SpiDiff diff) {
        Map<String, SpiMethod> oldMethods = new HashMap<>();
        Map<String, SpiMethod> newMethods = new HashMap<>();

        if (oldIface.methods != null) {
            for (SpiMethod method : oldIface.methods) {
                oldMethods.put(method.name + ":" + method.parameters.size(), method);
            }
        }
        if (newIface.methods != null) {
            for (SpiMethod method : newIface.methods) {
                newMethods.put(method.name + ":" + method.parameters.size(), method);
            }
        }

        String ifaceName = oldIface.name;

        for (String key : oldMethods.keySet()) {
            if (!newMethods.containsKey(key)) {
                diff.removedMethods.add(ifaceName + "." + oldMethods.get(key).name);
            }
        }

        for (String key : newMethods.keySet()) {
            if (!oldMethods.containsKey(key)) {
                diff.addedMethods.add(ifaceName + "." + newMethods.get(key).name);
            }
        }
    }

    /**
     * SPI metadata POJO
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpiMetadata {
        public String coreVersion;
        public String generatedAt;
        public List<SpiInterface> interfaces;
        public Map<String, Object> customToolApi;
    }

    /**
     * SPI interface POJO
     */
    public static class SpiInterface {
        public String name;
        public String simpleName;
        public String description;
        public List<SpiMethod> methods;
    }

    /**
     * SPI method POJO
     */
    public static class SpiMethod {
        public String name;
        public String returnType;
        public String returnTypeSimple;
        public List<SpiParameter> parameters;
        public List<String> exceptions;
    }

    /**
     * SPI parameter POJO
     */
    public static class SpiParameter {
        public String type;
        public String simpleType;
    }

    /**
     * SPI diff result
     */
    public static class SpiDiff {
        public String oldVersion;
        public String newVersion;
        public List<String> addedInterfaces = new ArrayList<>();
        public List<String> removedInterfaces = new ArrayList<>();
        public List<String> addedMethods = new ArrayList<>();
        public List<String> removedMethods = new ArrayList<>();

        public boolean hasChanges() {
            return !addedInterfaces.isEmpty() || !removedInterfaces.isEmpty()
                    || !addedMethods.isEmpty() || !removedMethods.isEmpty();
        }

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("## SPI Compatibility Report\n\n");
            sb.append("**Old Version**: ").append(oldVersion).append("\n");
            sb.append("**New Version**: ").append(newVersion).append("\n\n");

            if (!hasChanges()) {
                sb.append("No SPI changes detected.\n");
                return sb.toString();
            }

            if (!addedInterfaces.isEmpty()) {
                sb.append("### Added Interfaces\n\n");
                for (String name : addedInterfaces) {
                    sb.append("- `").append(name).append("`\n");
                }
                sb.append("\n");
            }

            if (!removedInterfaces.isEmpty()) {
                sb.append("### Removed Interfaces\n\n");
                for (String name : removedInterfaces) {
                    sb.append("- `").append(name).append("`\n");
                }
                sb.append("\n");
            }

            if (!addedMethods.isEmpty()) {
                sb.append("### Added Methods\n\n");
                for (String name : addedMethods) {
                    sb.append("- `").append(name).append("`\n");
                }
                sb.append("\n");
            }

            if (!removedMethods.isEmpty()) {
                sb.append("### Removed Methods\n\n");
                for (String name : removedMethods) {
                    sb.append("- `").append(name).append("`\n");
                }
                sb.append("\n");
            }

            return sb.toString();
        }
    }
}
