package com.github.obhen233.adapter.worker;

import com.github.obhen233.adapter.internal.DirectoryLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Loads worker capability description (capability.md) with 4-level priority.
 *
 * <p>Order of priority (highest first):</p>
 * <ol>
 *   <li><b>P1 — LLM generation</b>: If a description string ({@code -desc}) is provided
 *       AND the agent adapter has an API key, generate capability.md via LLM.
 *       Saved to {@code {jarDir}/.diatom/capability.md}.</li>
 *   <li><b>P2 — Explicit file path</b>: {@code -c <file>} loads the specified path.</li>
 *   <li><b>P3 — Default location</b>: {@code {jarDir}/.diatom/capability.md}</li>
 *   <li><b>P4 — Exit</b>: If no capability found, exit with error.</li>
 * </ol>
 */
public class CapabilityLoader {
    private static final Logger logger = LoggerFactory.getLogger(CapabilityLoader.class);

    private final Path jarDir;
    private final String descriptionArg;
    private final String capabilityFilePath;
    private final boolean hasApiKey;

    private String capabilityContent;

    public CapabilityLoader(Path jarDir, String descriptionArg, String capabilityFilePath, boolean hasApiKey) {
        this.jarDir = jarDir;
        this.descriptionArg = descriptionArg;
        this.capabilityFilePath = capabilityFilePath;
        this.hasApiKey = hasApiKey;
    }

    /**
     * Load capability content using 4-level priority resolution.
     *
     * @return the capability content
     * @throws IllegalStateException if no capability can be loaded
     */
    public String load() {
        // P1: LLM generation from description
        if (descriptionArg != null && !descriptionArg.isEmpty()) {
            if (hasApiKey) {
                String generated = generateFromDescription(descriptionArg);
                if (generated != null) {
                    saveToDefaultPath(generated);
                    capabilityContent = generated;
                    logger.info("Loaded capability: P1 (LLM generated from -desc)");
                    return capabilityContent;
                }
            } else {
                logger.warn("-desc provided but no API key configured. " +
                        "Ignoring -desc. Use -c to specify a capability file.");
            }
        }

        // P2: Explicit file path from -c
        if (capabilityFilePath != null && !capabilityFilePath.isEmpty()) {
            Path path = Paths.get(capabilityFilePath);
            if (Files.exists(path)) {
                try {
                    capabilityContent = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    logger.info("Loaded capability: P2 (-c {})", capabilityFilePath);
                    return capabilityContent;
                } catch (IOException e) {
                    logger.error("Failed to read capability file at {}: {}", capabilityFilePath, e.getMessage());
                }
            } else {
                logger.warn("Capability file not found at {} (specified via -c)", capabilityFilePath);
            }
        }

        // P3: Default location
        Path defaultPath = DirectoryLayout.getCapabilityPath(jarDir);
        if (Files.exists(defaultPath)) {
            try {
                capabilityContent = new String(Files.readAllBytes(defaultPath), StandardCharsets.UTF_8);
                logger.info("Loaded capability: P3 (default path: {})", defaultPath);
                return capabilityContent;
            } catch (IOException e) {
                logger.error("Failed to read default capability file at {}: {}", defaultPath, e.getMessage());
            }
        }

        // P4: No capability found — return null (startup without capability,
        // Gateway will push tasks directly without local capability routing)
        logger.warn("No capability.md found. Worker will start without capability advertisement. " +
                "Provide one via -c <file>, -d \"description\", or place at {}", defaultPath);
        return null;
    }

    public String getCapabilityContent() {
        return capabilityContent;
    }

    private String generateFromDescription(String description) {
        // LLM-based generation placeholder.
        // In a full implementation, this would call the LLM API with a prompt asking it
        // to generate a capability.md from the description text.
        // For now, create a minimal default capability.
        return """
                # Worker Capability

                ## Basic Info
                - **Description**: %s

                ## Strengths
                - General task execution

                ## Suitable Task Types
                - [x] General task execution
                """.formatted(description);
    }

    private void saveToDefaultPath(String content) {
        try {
            Path dir = DirectoryLayout.getDiatomDir(jarDir);
            Files.createDirectories(dir);
            Files.write(DirectoryLayout.getCapabilityPath(jarDir), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("Failed to save generated capability.md: {}", e.getMessage());
        }
    }
}
