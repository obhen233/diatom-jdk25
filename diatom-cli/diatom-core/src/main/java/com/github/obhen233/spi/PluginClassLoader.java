package com.github.obhen233.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Plugin class loader with per-JAR isolation for SPI discovery.
 *
 * <p><b>Two-layer architecture:</b></p>
 * <ul>
 *   <li><b>Shared</b> URLClassLoader (all JARs merged) — for TCCL setting and JDBC Driver registration.
 *       This ensures downstream code (JDBC DriverManager, frameworks) can find plugin classes via TCCL.</li>
 *   <li><b>Per-JAR</b> isolated URLClassLoaders — for SPI discovery. Each plugin JAR gets its own
 *       parent-last classloader so different dependency versions (e.g., gson-2.10 vs gson-2.11)
 *       can coexist without conflict.</li>
 * </ul>
 *
 * <p>Scan order (higher priority first):</p>
 * <ol>
 *   <li>{jarDir}/.diatom/plugins/ — instance-scoped plugins</li>
 *   <li>~/.diatom/plugins/ — global plugins shared across instances</li>
 * </ol>
 */
public class PluginClassLoader extends URLClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(PluginClassLoader.class);

    private static volatile PluginClassLoader instance;

    /** Per-JAR isolated plugin units */
    private final List<PluginUnit> pluginUnits = new ArrayList<>();

    /** Registered DelegatingDriver instances for cleanup */
    private final List<java.sql.Driver> registeredDrivers = new ArrayList<>();

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    // ========== Initialization ==========

    /**
     * Initialize: scan directories, create shared classloader + per-JAR isolated classloaders,
     * and set TCCL to the shared classloader.
     *
     * <p>Safe to call multiple times — returns the existing instance.</p>
     */
    public static synchronized PluginClassLoader init(Path... pluginDirs) {
        if (instance != null) return instance;

        // 1. Collect all JARs
        List<File> jarFiles = new ArrayList<>();
        for (Path dir : pluginDirs) {
            scanDir(dir, jarFiles);
        }

        if (jarFiles.isEmpty()) {
            logger.debug("PluginClassLoader: no plugin JARs found");
            instance = new PluginClassLoader(new URL[0], PluginClassLoader.class.getClassLoader());
            return instance;
        }

        // 2. Build shared URLClassLoader (all JARs merged) for TCCL + JDBC
        List<URL> jarUrls = new ArrayList<>();
        for (File jar : jarFiles) {
            try {
                jarUrls.add(jar.toURI().toURL());
            } catch (MalformedURLException e) {
                logger.warn("Invalid JAR path: {}", jar.getAbsolutePath());
            }
        }

        logger.info("PluginClassLoader: found {} plugin JAR(s)", jarFiles.size());
        ClassLoader parent = PluginClassLoader.class.getClassLoader();
        instance = new PluginClassLoader(jarUrls.toArray(new URL[0]), parent);

        // 3. Create per-JAR isolated classloaders and pre-read SPI declarations
        for (File jar : jarFiles) {
            try {
                URL jarUrl = jar.toURI().toURL();
                Map<String, List<String>> spiDeclarations = readAllSpiDeclarations(jar);
                if (!spiDeclarations.isEmpty()) {
                    URLClassLoader isolatedCl = createIsolatedClassLoader(jarUrl, parent);
                    PluginUnit unit = new PluginUnit(jar.getName(), isolatedCl, spiDeclarations);
                    instance.pluginUnits.add(unit);
                    logger.debug("Isolated plugin: {} ({} SPI types)", jar.getName(), spiDeclarations.size());
                } else {
                    logger.trace("Skipping JAR with no SPI declarations: {}", jar.getName());
                }
            } catch (Exception e) {
                logger.warn("Failed to initialize plugin {}: {}", jar.getName(), e.getMessage());
            }
        }

        // 4. Set TCCL to the shared classloader
        Thread.currentThread().setContextClassLoader(instance);
        return instance;
    }

    /**
     * Create a parent-last URLClassLoader for a single plugin JAR.
     * The plugin classloader tries its own JAR first, then falls back to the parent.
     */
    static URLClassLoader createIsolatedClassLoader(URL jarUrl, ClassLoader parent) {
        return new URLClassLoader(new URL[]{jarUrl}, parent) {
            @Override
            public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("java.") || name.startsWith("javax.")
                        || name.startsWith("sun.") || name.startsWith("org.w3c")
                        || name.startsWith("org.xml")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        try {
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            c = super.loadClass(name, false);
                        }
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
        };
    }

    // ========== SPI discovery with per-JAR isolation ==========

    /**
     * Load and instantiate all implementations of the given SPI type
     * across all plugin JARs, each loaded from its isolated classloader.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> loadAll(Class<T> spiType) {
        List<T> result = new ArrayList<>();
        String spiName = spiType.getName();
        for (PluginUnit unit : pluginUnits) {
            List<String> implNames = unit.getImplNames(spiName);
            if (implNames.isEmpty()) continue;
            for (String implName : implNames) {
                try {
                    Class<?> clazz = Class.forName(implName, false, unit.classLoader);
                    result.add((T) clazz.newInstance());
                    logger.debug("Loaded SPI from {}: {} -> {}", unit.name, spiName, implName);
                } catch (Exception e) {
                    logger.warn("Failed to instantiate {} from {}: {}", implName, unit.name, e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * Load implementations that match a specific type value.
     * Uses reflection to call the given getter method (e.g., "getAgentType")
     * and compares the result with the expected typeValue.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> loadByType(Class<T> spiType, String typeValue, String typeMethod) {
        List<T> result = new ArrayList<>();
        String spiName = spiType.getName();
        for (PluginUnit unit : pluginUnits) {
            List<String> implNames = unit.getImplNames(spiName);
            if (implNames.isEmpty()) continue;
            for (String implName : implNames) {
                try {
                    Class<?> clazz = Class.forName(implName, false, unit.classLoader);
                    Object instance = clazz.newInstance();
                    Method m = clazz.getMethod(typeMethod);
                    String val = (String) m.invoke(instance);
                    if (typeValue == null || typeValue.equals(val)) {
                        result.add((T) instance);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load {} from {}: {}", implName, unit.name, e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * Load the first implementation of the given SPI type.
     * Returns null if no implementation is found.
     */
    @SuppressWarnings("unchecked")
    public <T> T loadFirst(Class<T> spiType) {
        String spiName = spiType.getName();
        for (PluginUnit unit : pluginUnits) {
            List<String> implNames = unit.getImplNames(spiName);
            if (implNames.isEmpty()) continue;
            for (String implName : implNames) {
                try {
                    Class<?> clazz = Class.forName(implName, false, unit.classLoader);
                    return (T) clazz.newInstance();
                } catch (Exception e) {
                    logger.warn("Failed to instantiate {} from {}: {}", implName, unit.name, e.getMessage());
                }
            }
        }
        return null;
    }

    // ========== JDBC Driver registration ==========

    /**
     * Register JDBC drivers found in plugin JARs.
     * Uses the shared classloader (this) so the driver class is loadable via TCCL.
     */
    public void registerJdbcDrivers() {
        if (!hasPlugins()) return;

        int count = 0;
        for (URL jarUrl : getURLs()) {
            try {
                File jar = new File(jarUrl.toURI());
                Map<String, List<String>> spiDeclarations = readSpiDeclarations(jar, "java.sql.Driver");
                List<String> driverClasses = spiDeclarations.get("java.sql.Driver");
                if (driverClasses == null || driverClasses.isEmpty()) continue;

                for (String driverClass : driverClasses) {
                    try {
                        Class<?> clazz = Class.forName(driverClass, true, this);
                        if (!java.sql.Driver.class.isAssignableFrom(clazz)) {
                            logger.warn("Class {} does not implement java.sql.Driver, skipping", driverClass);
                            continue;
                        }
                        java.sql.Driver pluginDriver = (java.sql.Driver) clazz.newInstance();
                        DelegatingDriver delegating = new DelegatingDriver(pluginDriver);
                        DriverManager.registerDriver(delegating);
                        registeredDrivers.add(delegating);
                        count++;
                        logger.info("Registered JDBC driver from plugin: {}", driverClass);
                    } catch (Exception e) {
                        logger.warn("Failed to register JDBC driver {}: {}", driverClass, e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to scan JAR for JDBC drivers: {}", e.getMessage());
            }
        }
        if (count > 0) {
            logger.info("Registered {} JDBC driver(s) from plugins", count);
        }
    }

    /**
     * Deregister all DelegatingDrivers previously registered by this loader.
     */
    public void deregisterJdbcDrivers() {
        for (java.sql.Driver driver : registeredDrivers) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException e) {
                logger.warn("Failed to deregister JDBC driver: {}", e.getMessage());
            }
        }
        registeredDrivers.clear();
    }

    // ========== Accessors ==========

    public static PluginClassLoader getInstance() {
        return instance;
    }

    public boolean hasPlugins() {
        return !pluginUnits.isEmpty();
    }

    public List<PluginUnit> getPluginUnits() {
        return pluginUnits;
    }

    // ========== Directory scanning ==========

    private static void scanDir(Path dir, List<File> result) {
        if (!Files.isDirectory(dir)) return;
        File[] jars = dir.toFile().listFiles((d, name) -> name.endsWith(".jar"));
        if (jars == null) return;
        Collections.addAll(result, jars);
    }

    // ========== SPI declaration reading ==========

    /** Read all META-INF/services/ entries from a JAR. */
    static Map<String, List<String>> readAllSpiDeclarations(File jar) throws IOException {
        Map<String, List<String>> result = new HashMap<>();
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                String prefix = "META-INF/services/";
                if (name.startsWith(prefix) && !entry.isDirectory()) {
                    String spiType = name.substring(prefix.length());
                    List<String> impls = readServiceImplFile(jf, entry);
                    if (!impls.isEmpty()) {
                        result.put(spiType, impls);
                    }
                }
            }
        }
        return result;
    }

    /** Read META-INF/services/ entries for a specific SPI type. */
    static Map<String, List<String>> readSpiDeclarations(File jar, String spiType) throws IOException {
        Map<String, List<String>> result = new HashMap<>();
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                String prefix = "META-INF/services/";
                if (name.startsWith(prefix) && !entry.isDirectory()) {
                    String typeName = name.substring(prefix.length());
                    if (spiType.equals(typeName)) {
                        List<String> impls = readServiceImplFile(jf, entry);
                        if (!impls.isEmpty()) {
                            result.put(typeName, impls);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static List<String> readServiceImplFile(JarFile jf, JarEntry entry) throws IOException {
        List<String> impls = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(jf.getInputStream(entry), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    impls.add(line);
                }
            }
        }
        return impls;
    }

    // ========== Default plugin directories ==========

    public static List<Path> getDefaultPluginDirs(Path jarDir) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(jarDir.resolve(".diatom").resolve("plugins"));
        dirs.add(java.nio.file.Paths.get(System.getProperty("user.home", "~"), ".diatom", "plugins"));
        return dirs;
    }

    // ========== PluginUnit (per-JAR metadata) ==========

    /**
     * Metadata for a single plugin JAR with its isolated classloader.
     */
    public static class PluginUnit {
        private final String name;
        private final URLClassLoader classLoader;
        private final Map<String, List<String>> spiDeclarations;

        PluginUnit(String name, URLClassLoader classLoader, Map<String, List<String>> spiDeclarations) {
            this.name = name;
            this.classLoader = classLoader;
            this.spiDeclarations = spiDeclarations;
        }

        public String getName() { return name; }
        public URLClassLoader getClassLoader() { return classLoader; }

        public List<String> getImplNames(String spiType) {
            List<String> result = spiDeclarations.get(spiType);
            return result != null ? result : Collections.<String>emptyList();
        }

        public Set<String> getDeclaredSpiTypes() {
            return spiDeclarations.keySet();
        }
    }

    @Override
    public String toString() {
        return "PluginClassLoader{" + pluginUnits.size() + " plugin(s)}";
    }
}
