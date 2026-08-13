package com.github.obhen233.compiler.decompile;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Service
public class DecompileService {

    private static final Logger log = LoggerFactory.getLogger(DecompileService.class);

    /**
     * List all .class entries in a jar file
     */
    public List<String> listClasses(String jarPath) throws IOException {
        List<String> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(new File(jarPath))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("module-info") && !name.contains("package-info")) {
                    // Convert path to class name: com/example/Foo.class -> com.example.Foo
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    classes.add(className);
                }
            }
        }
        Collections.sort(classes);
        return classes;
    }

    /**
     * Decompile a single class from a jar file
     *
     * @param jarPath   absolute path to the jar file
     * @param className fully qualified class name (e.g. "java.util.List")
     * @return decompiled Java source code
     */
    public String decompileClass(String jarPath, String className) throws Exception {
        final StringBuilder result = new StringBuilder();
        final StringBuilder errors = new StringBuilder();

        OutputSinkFactory mySink = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
                return Arrays.asList(SinkClass.STRING, SinkClass.DECOMPILED, SinkClass.EXCEPTION_MESSAGE);
            }

            @Override
            public <T> Sink<T> getSink(final SinkType sinkType, final SinkClass sinkClass) {
                return new Sink<T>() {
                    @Override
                    public void write(T sinkable) {
                        if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED) {
                            result.append((String) sinkable);
                        } else if (sinkType == SinkType.EXCEPTION) {
                            errors.append(sinkable.toString()).append("\n");
                        }
                    }
                };
            }
        };

        Map<String, String> options = new HashMap<>();
        options.put("comments", "false");
        options.put("showversion", "false");
        options.put("hideutf", "true");
        options.put("forcetopsort", "true");

        CfrDriver driver = new CfrDriver.Builder()
                .withOptions(options)
                .withOutputSink(mySink)
                .build();

        driver.analyse(Arrays.asList(jarPath));

        // If CFR returned nothing, try with explicit class filter
        if (result.length() == 0) {
            errors.setLength(0);
            result.setLength(0);
            Map<String, String> options2 = new HashMap<>();
            options2.put("comments", "false");
            options2.put("showversion", "false");
            options2.put("hideutf", "true");
            options2.put("forcetopsort", "true");
            options2.put("extraclasspath", jarPath);

            OutputSinkFactory sink2 = new OutputSinkFactory() {
                @Override
                public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
                    return Arrays.asList(SinkClass.STRING, SinkClass.DECOMPILED, SinkClass.EXCEPTION_MESSAGE);
                }

                @Override
                public <T> Sink<T> getSink(final SinkType sinkType, final SinkClass sinkClass) {
                    return new Sink<T>() {
                        @Override
                        public void write(T sinkable) {
                            if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED) {
                                result.append((String) sinkable);
                            }
                        }
                    };
                }
            };

            CfrDriver driver2 = new CfrDriver.Builder()
                    .withOptions(options2)
                    .withOutputSink(sink2)
                    .build();
            driver2.analyse(Arrays.asList(className));
        }

        if (result.length() == 0 && errors.length() > 0) {
            throw new RuntimeException("Decompilation failed: " + errors.toString().trim());
        }

        return result.toString();
    }

    /**
     * Decompile all classes in a jar into a single concatenated source
     */
    public String decompileJar(String jarPath) throws Exception {
        List<String> classes = listClasses(jarPath);
        StringBuilder allSource = new StringBuilder();
        for (String cls : classes) {
            try {
                String source = decompileClass(jarPath, cls);
                if (source != null && !source.trim().isEmpty()) {
                    allSource.append("// === ").append(cls).append(" ===\n\n");
                    allSource.append(source).append("\n\n");
                }
            } catch (Exception e) {
                log.warn("Failed to decompile {}: {}", cls, e.getMessage());
            }
        }
        return allSource.toString();
    }
}
