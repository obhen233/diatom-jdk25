package com.github.obhen233.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class SkillParser {
    private static final Logger logger = LoggerFactory.getLogger(SkillParser.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String FRONTMATTER_SEPARATOR = "---";

    public static Skill parse(Path filePath) throws IOException {
        StringBuilder frontmatterBuilder = new StringBuilder();
        StringBuilder bodyBuilder = new StringBuilder();
        boolean inFrontmatter = false;
        int separatorCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().equals(FRONTMATTER_SEPARATOR)) {
                    separatorCount++;
                    if (separatorCount == 1) {
                        inFrontmatter = true;
                        continue;
                    } else if (separatorCount == 2) {
                        inFrontmatter = false;
                        continue;
                    }
                }
                if (inFrontmatter) {
                    frontmatterBuilder.append(line).append("\n");
                } else {
                    bodyBuilder.append(line).append("\n");
                }
            }
        } catch (CharacterCodingException e) {
            logger.warn("UTF-8 decode error for {}, falling back to ISO-8859-1: {}", filePath, e.getMessage());
            frontmatterBuilder.setLength(0);
            bodyBuilder.setLength(0);
            separatorCount = 0;
            inFrontmatter = false;
            try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.ISO_8859_1)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().equals(FRONTMATTER_SEPARATOR)) {
                        separatorCount++;
                        if (separatorCount == 1) {
                            inFrontmatter = true;
                            continue;
                        } else if (separatorCount == 2) {
                            inFrontmatter = false;
                            continue;
                        }
                    }
                    if (inFrontmatter) {
                        frontmatterBuilder.append(line).append("\n");
                    } else {
                        bodyBuilder.append(line).append("\n");
                    }
                }
            }
        }

        Skill skill = new Skill();
        skill.setFilePath(filePath);

        if (frontmatterBuilder.length() > 0) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = YAML_MAPPER.readValue(frontmatterBuilder.toString(), Map.class);
                skill.setName((String) metadata.get("name"));
                skill.setDescription((String) metadata.get("description"));
                skill.setVersion((String) metadata.get("version"));
                skill.setAllowedTools((String) metadata.get("allowed-tools"));
                skill.setEnabled((Boolean) metadata.getOrDefault("enabled", true));
                skill.setPriority((Integer) metadata.getOrDefault("priority", 0));
                skill.setTriggers((java.util.List<String>) metadata.get("triggers"));
                skill.setProfile((String) metadata.get("profile"));
                skill.setKind((String) metadata.getOrDefault("kind", "user"));
                skill.setVariables((Map<String, Object>) metadata.get("variables"));
            } catch (Exception e) {
                logger.warn("Failed to parse YAML frontmatter for {}: {}", filePath, e.getMessage());
            }
        }

        skill.setBody(bodyBuilder.toString().trim());
        return skill;
    }
}
