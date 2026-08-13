package com.github.obhen233.core.gateway.profile;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * CapabilityAnalyzer 解析测试
 * 测试 boundaries、complex task handling、capability level 标记的解析
 */
public class CapabilityAnalyzerTest {

    private final CapabilityAnalyzer analyzer = new CapabilityAnalyzer();

    // ========== English markdown ==========

    @Test
    public void testParseEnglish_fullProfile() {
        String md = "# Worker Capability\n" +
                "\n" +
                "## Basic Info\n" +
                "- **Worker ID**: worker-web-001\n" +
                "- **Model**: claude-sonnet-4-6\n" +
                "- **Tier**: standard\n" +
                "\n" +
                "## Strengths\n" +
                "- Full-stack web development (React, Spring Boot)\n" +
                "- RESTful API design\n" +
                "\n" +
                "## Boundaries\n" +
                "- No internet access (cannot fetch external APIs)\n" +
                "- File operations limited to workspace directory\n" +
                "- Cannot deploy to production without confirmation\n" +
                "\n" +
                "## Suitable Task Types\n" +
                "- [x] feature_development\n" +
                "- [x] bug_fix\n" +
                "- [ ] web_scraping (unsupported)\n" +
                "\n" +
                "## Complex Task Handling\n" +
                "- Supports complex tasks: yes\n" +
                "- Recommended max steps: 50\n" +
                "- Per-task token budget: 128000\n" +
                "- Max Output Tokens: 16384\n";

        CapabilityProfile profile = analyzer.parseMarkdown(md, "worker-web-001");

        assertNotNull(profile);
        assertEquals("worker-web-001", profile.getWorkerId());
        assertEquals("claude-sonnet-4-6", profile.getModel());
        assertEquals("standard", profile.getTier());

        // Boundaries
        assertEquals(3, profile.getBoundaries().size());
        assertTrue(profile.getBoundaries().get(0).contains("No internet access"));
        assertTrue(profile.getBoundaries().get(1).contains("File operations limited"));

        // Complex Task Handling
        assertEquals(50, profile.getMaxSteps());
        assertEquals(128000, profile.getMaxTokens());
        assertEquals(16384, profile.getMaxOutputTokens());

        // Suitable task types
        assertTrue(profile.getSuitableTaskTypes().contains("feature_development"));
        assertTrue(profile.getSuitableTaskTypes().contains("bug_fix"));
    }

    @Test
    public void testParseEnglish_withCapabilityLevels() {
        String md = "# Worker Capability\n" +
                "\n" +
                "## Basic Info\n" +
                "- **Worker ID**: worker-test\n" +
                "- **Model**: gpt-4\n" +
                "- **Tier**: standard\n" +
                "\n" +
                "## Strengths\n" +
                "- General coding\n" +
                "\n" +
                "## Boundaries\n" +
                "- No internet\n" +
                "\n" +
                "## Suitable Task Types\n" +
                "- [x] feature_development (required)\n" +
                "- [x] bug_fix (preferred)\n" +
                "- [x] code_review\n" +
                "- [ ] web_scraping (unsupported)\n" +
                "\n" +
                "## Complex Task Handling\n" +
                "- Supports complex tasks: yes\n" +
                "- Recommended max steps: 30\n" +
                "- Per-task token budget: 64000\n";

        CapabilityProfile profile = analyzer.parseMarkdown(md, "worker-test");

        assertNotNull(profile);

        // Capability levels
        assertEquals(3, profile.getCapabilityLevels().size());
        assertEquals(CapabilityProfile.CapabilityLevel.REQUIRED,
                profile.getCapabilityLevels().get("feature_development"));
        assertEquals(CapabilityProfile.CapabilityLevel.PREFERRED,
                profile.getCapabilityLevels().get("bug_fix"));
        assertEquals(CapabilityProfile.CapabilityLevel.NORMAL,
                profile.getCapabilityLevels().get("code_review"));

        // Suitable task types (without markers)
        assertTrue(profile.getSuitableTaskTypes().contains("feature_development"));
        assertTrue(profile.getSuitableTaskTypes().contains("bug_fix"));
        assertTrue(profile.getSuitableTaskTypes().contains("code_review"));

        // Complex Task Handling
        assertEquals(30, profile.getMaxSteps());
        assertEquals(64000, profile.getMaxTokens());
        assertEquals(0, profile.getMaxOutputTokens()); // Not in the markdown
    }

    // ========== Chinese markdown ==========

    @Test
    public void testParseChinese_fullProfile() {
        String md = "# Worker 能力自述\n" +
                "\n" +
                "## 基本信息\n" +
                "- **Worker ID**: worker-data-002\n" +
                "- **模型**: claude-opus-4-6\n" +
                "- **等级**: premium\n" +
                "\n" +
                "## 擅长领域\n" +
                "- 数据分析与统计建模\n" +
                "- 机器学习流水线开发\n" +
                "\n" +
                "## 能力边界\n" +
                "- 无互联网访问能力\n" +
                "- 文件操作仅限于工作区目录\n" +
                "- 无法训练大型模型\n" +
                "\n" +
                "## 适合处理的任务类型\n" +
                "- [x] data_analysis\n" +
                "- [x] data_pipeline\n" +
                "- [ ] web_scraping（不支持）\n" +
                "\n" +
                "## 复杂任务处理能力\n" +
                "- 可处理复杂任务: 是\n" +
                "- 建议最大步骤数: 50\n" +
                "- 单任务建议 Token 预算: 128000\n";

        CapabilityProfile profile = analyzer.parseMarkdown(md, "worker-data-002");

        assertNotNull(profile);
        assertEquals("worker-data-002", profile.getWorkerId());
        assertEquals("claude-opus-4-6", profile.getModel());
        assertEquals("premium", profile.getTier());

        // Boundaries
        assertEquals(3, profile.getBoundaries().size());
        assertTrue(profile.getBoundaries().get(0).contains("无互联网访问"));

        // Complex Task Handling
        assertEquals(50, profile.getMaxSteps());
        assertEquals(128000, profile.getMaxTokens());

        // Suitable task types
        assertTrue(profile.getSuitableTaskTypes().contains("data_analysis"));
        assertTrue(profile.getSuitableTaskTypes().contains("data_pipeline"));

        // Unsuitable task types
        assertTrue(profile.getUnsuitableTaskTypes().contains("web_scraping"));
    }

    @Test
    public void testParseChinese_withCapabilityLevels() {
        String md = "# Worker 能力自述\n" +
                "\n" +
                "## 基本信息\n" +
                "- **Worker ID**: worker-ops-003\n" +
                "- **模型**: claude-sonnet-4-6\n" +
                "- **等级**: standard\n" +
                "\n" +
                "## 擅长领域\n" +
                "- 基础设施即代码\n" +
                "\n" +
                "## 能力边界\n" +
                "- 无互联网访问\n" +
                "\n" +
                "## 适合处理的任务类型\n" +
                "- [x] infrastructure_setup (required)\n" +
                "- [x] deployment (required)\n" +
                "- [x] monitoring_config (preferred)\n" +
                "- [x] automation\n" +
                "\n" +
                "## 复杂任务处理能力\n" +
                "- 可处理复杂任务: 是\n" +
                "- 建议最大步骤数: 50\n" +
                "- 单任务建议 Token 预算: 128000\n" +
                "- 最大输出 Token: 32768\n";

        CapabilityProfile profile = analyzer.parseMarkdown(md, "worker-ops-003");

        assertNotNull(profile);

        // Capability levels
        assertEquals(4, profile.getCapabilityLevels().size());
        assertEquals(CapabilityProfile.CapabilityLevel.REQUIRED,
                profile.getCapabilityLevels().get("infrastructure_setup"));
        assertEquals(CapabilityProfile.CapabilityLevel.REQUIRED,
                profile.getCapabilityLevels().get("deployment"));
        assertEquals(CapabilityProfile.CapabilityLevel.PREFERRED,
                profile.getCapabilityLevels().get("monitoring_config"));
        assertEquals(CapabilityProfile.CapabilityLevel.NORMAL,
                profile.getCapabilityLevels().get("automation"));

        assertEquals(50, profile.getMaxSteps());
        assertEquals(128000, profile.getMaxTokens());
        assertEquals(32768, profile.getMaxOutputTokens());
    }

    // ========== Edge cases ==========

    @Test
    public void testParse_nullMarkdown() {
        CapabilityProfile profile = analyzer.parseMarkdown(null, "worker-001");
        assertNull(profile);
    }

    @Test
    public void testParse_emptyMarkdown() {
        CapabilityProfile profile = analyzer.parseMarkdown("", "worker-001");
        assertNull(profile);
    }

    @Test
    public void testParse_noBoundariesSection() {
        String md = "# Worker Capability\n" +
                "\n" +
                "## Basic Info\n" +
                "- **Worker ID**: worker-test\n" +
                "- **Model**: gpt-4\n" +
                "- **Tier**: standard\n" +
                "\n" +
                "## Strengths\n" +
                "- Coding\n" +
                "\n" +
                "## Suitable Task Types\n" +
                "- [x] coding\n" +
                "\n" +
                "## Complex Task Handling\n" +
                "- Recommended max steps: 20\n" +
                "- Per-task token budget: 32000\n";

        CapabilityProfile profile = analyzer.parseMarkdown(md, "worker-test");

        assertNotNull(profile);
        assertTrue(profile.getBoundaries().isEmpty());
        assertEquals(20, profile.getMaxSteps());
        assertEquals(32000, profile.getMaxTokens());
    }

    @Test
    public void testParse_noComplexTaskSection() {
        String md = "# Worker Capability\n" +
                "\n" +
                "## Basic Info\n" +
                "- **Worker ID**: worker-test\n" +
                "- **Model**: gpt-4\n" +
                "- **Tier**: standard\n" +
                "\n" +
                "## Strengths\n" +
                "- Coding\n" +
                "\n" +
                "## Boundaries\n" +
                "- No internet\n" +
                "\n" +
                "## Suitable Task Types\n" +
                "- [x] coding\n";

        CapabilityProfile profile = analyzer.parseMarkdown(md, "worker-test");

        assertNotNull(profile);
        assertEquals(1, profile.getBoundaries().size());
        assertEquals(0, profile.getMaxSteps());
        assertEquals(0, profile.getMaxTokens());
    }

    @Test
    public void testParse_mixedEnglishChineseHeaders() {
        // Cross-language: English boundaries header + Chinese complex task header
        String md = "# Worker\n" +
                "\n" +
                "## Basic Info\n" +
                "- **Worker ID**: worker-mix\n" +
                "- **Model**: gpt-4\n" +
                "- **Tier**: standard\n" +
                "\n" +
                "## Strengths\n" +
                "- General\n" +
                "\n" +
                "## Boundaries\n" +
                "- No internet access\n" +
                "\n" +
                "## Suitable Task Types\n" +
                "- [x] coding\n" +
                "\n" +
                "## 复杂任务处理能力\n" +
                "- 建议最大步骤数: 30\n" +
                "- 单任务建议 Token 预算: 64000\n";

        CapabilityProfile profile = analyzer.parseMarkdown(md, "worker-mix");

        assertNotNull(profile);
        assertEquals(1, profile.getBoundaries().size());
        assertEquals(30, profile.getMaxSteps());
        assertEquals(64000, profile.getMaxTokens());
    }

    @Test
    public void testParse_unsuitableTaskTypes() {
        String md = "# Worker Capability\n" +
                "\n" +
                "## Basic Info\n" +
                "- **Worker ID**: worker-test\n" +
                "- **Model**: gpt-4\n" +
                "- **Tier**: standard\n" +
                "\n" +
                "## Strengths\n" +
                "- Coding\n" +
                "\n" +
                "## Suitable Task Types\n" +
                "- [x] coding\n" +
                "- [ ] web_scraping（不支持）\n" +
                "- [ ] image_analysis（不支持）\n";

        CapabilityProfile profile = analyzer.parseMarkdown(md, "worker-test");

        assertNotNull(profile);
        assertEquals(2, profile.getUnsuitableTaskTypes().size());
        assertTrue(profile.getUnsuitableTaskTypes().contains("web_scraping"));
        assertTrue(profile.getUnsuitableTaskTypes().contains("image_analysis"));
    }

    @Test
    public void testParse_rawMarkdownStored() {
        String md = "# Worker Capability\n\n## Basic Info\n- **Worker ID**: w1\n- **Model**: gpt-4\n- **Tier**: standard\n";

        CapabilityProfile profile = analyzer.parseMarkdown(md, "w1");
        assertNotNull(profile);
        assertNotNull(profile.getRawMarkdown());
        assertTrue(profile.getRawMarkdown().contains("Worker Capability"));
    }
}
