package com.github.obhen233.core.gateway.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gateway Agent 分析 capability.md 提取特征
 */
public class CapabilityAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(CapabilityAnalyzer.class);

    /**
     * 解析 Markdown 格式的能力自述文档
     */
    public CapabilityProfile parseMarkdown(String markdown, String workerId) {
        if (markdown == null || markdown.isEmpty()) return null;

        CapabilityProfile profile = new CapabilityProfile();
        profile.setWorkerId(workerId);
        profile.setRawMarkdown(markdown);

        // Parse model name (support Chinese and English labels)
        String model = extractLineAfter(markdown, "模型");
        if (model == null) model = extractLineAfter(markdown, "Model");
        if (model != null) profile.setModel(model);

        // Parse tier (support Chinese and English labels)
        String tier = extractLineAfter(markdown, "等级");
        if (tier == null) tier = extractLineAfter(markdown, "Tier");
        if (tier != null) profile.setTier(tier);

        // Parse strengths — support both Chinese and English section headers.
        // Handles both bullet-list items (- text) and plain text blocks.
        Pattern strengthPattern = Pattern.compile("## (?:Strengths|擅长领域)\\n([\\s\\S]*?)\\n##");
        Matcher m = strengthPattern.matcher(markdown);
        if (m.find()) {
            String section = m.group(1).trim();
            String[] lines = section.split("\\n");
            StringBuilder block = new StringBuilder();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    // Empty line: flush current text block and continue
                    if (block.length() > 0) {
                        profile.getStrengths().add(block.toString().trim());
                        block.setLength(0);
                    }
                } else if (trimmed.startsWith("- ")) {
                    // Flush previous block, then add this item
                    if (block.length() > 0) {
                        profile.getStrengths().add(block.toString().trim());
                        block.setLength(0);
                    }
                    profile.getStrengths().add(trimmed.substring(2).trim());
                } else {
                    // Continuation line: append to current block
                    if (block.length() > 0) block.append(" ");
                    block.append(trimmed);
                }
            }
            // Flush remaining block
            if (block.length() > 0) {
                profile.getStrengths().add(block.toString().trim());
            }
        }

        // Parse suitable task types with optional capability level markers
        // Format: - [x] task_type or - [x] task_type (required) / (preferred)
        Pattern suitablePattern = Pattern.compile("- \\[x\\] (.+)");
        m = suitablePattern.matcher(markdown);
        while (m.find()) {
            String raw = m.group(1).trim();
            // Extract capability level from trailing (required) or (preferred) marker
            if (raw.endsWith("(required)")) {
                String name = raw.substring(0, raw.length() - "(required)".length()).trim();
                profile.getSuitableTaskTypes().add(name);
                profile.getCapabilityLevels().put(name, CapabilityProfile.CapabilityLevel.REQUIRED);
            } else if (raw.endsWith("(preferred)")) {
                String name = raw.substring(0, raw.length() - "(preferred)".length()).trim();
                profile.getSuitableTaskTypes().add(name);
                profile.getCapabilityLevels().put(name, CapabilityProfile.CapabilityLevel.PREFERRED);
            } else {
                profile.getSuitableTaskTypes().add(raw);
                profile.getCapabilityLevels().put(raw, CapabilityProfile.CapabilityLevel.NORMAL);
            }
        }

        // Parse unsuitable task types
        Pattern unsuitablePattern = Pattern.compile("- \\[ \\] (.+)（不支持）");
        m = unsuitablePattern.matcher(markdown);
        while (m.find()) {
            profile.getUnsuitableTaskTypes().add(m.group(1).trim());
        }

        // --- Parse Boundaries section ---
        // Same structure as Strengths: bullet-list items
        Pattern boundaryPattern = Pattern.compile("## (?:Boundaries|能力边界)\\n([\\s\\S]*?)\\n##");
        m = boundaryPattern.matcher(markdown);
        if (m.find()) {
            String section = m.group(1).trim();
            String[] lines = section.split("\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- ")) {
                    profile.getBoundaries().add(trimmed.substring(2).trim());
                }
            }
        }

        // --- Parse Complex Task Handling section ---
        // Format: bullet list with "key: value" pairs
        Pattern complexPattern = Pattern.compile("## (?:Complex Task Handling|复杂任务处理能力)\\n([\\s\\S]*?)(?:\\n##|$)");
        m = complexPattern.matcher(markdown);
        if (m.find()) {
            String section = m.group(1);
            // Extract max steps
            String maxStepsStr = extractComplexValue(section, "建议最大步骤数|Recommended max steps|Max Steps|maxSteps");
            if (maxStepsStr != null) {
                try { profile.setMaxSteps(Integer.parseInt(maxStepsStr.replaceAll("[^0-9]", ""))); } catch (Exception ignored) {}
            }
            // Extract max tokens
            String maxTokensStr = extractComplexValue(section, "单任务建议 Token 预算|Per-task token budget|Max Tokens|maxTokens");
            if (maxTokensStr != null) {
                try { profile.setMaxTokens(Integer.parseInt(maxTokensStr.replaceAll("[^0-9]", ""))); } catch (Exception ignored) {}
            }
            // Extract max output tokens
            String maxOutputStr = extractComplexValue(section, "maxOutputTokens|Max Output Tokens|最大输出 Token");
            if (maxOutputStr != null) {
                try { profile.setMaxOutputTokens(Integer.parseInt(maxOutputStr.replaceAll("[^0-9]", ""))); } catch (Exception ignored) {}
            }
        }

        logger.debug("Parsed capability profile for worker: {} (strengths={})",
                workerId, profile.getStrengths().size());
        return profile;
    }

    private String extractLineAfter(String markdown, String key) {
        Pattern p = Pattern.compile("\\*\\*" + key + "\\*\\*:\\s*(.+)");
        Matcher m = p.matcher(markdown);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    /**
     * Extract value from a bullet-list section with "key: value" format.
     * Supports pipe-separated multiple key labels (first match wins).
     */
    private String extractComplexValue(String section, String keys) {
        Pattern p = Pattern.compile("-\\s*(" + keys + ")\\s*[:：]\\s*(.+)");
        Matcher m = p.matcher(section);
        if (m.find()) return m.group(2).trim();
        return null;
    }
}
