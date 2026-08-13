package com.github.obhen233.core.gateway.agent;

import com.github.obhen233.core.skill.Skill;
import com.github.obhen233.core.skill.SkillParser;
import com.github.obhen233.util.InstallPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gateway 系统提示管理器。
 * <p>
 * 管理 {installHome}/gateway-prompt.skill.md 文件，
 * 用户可自行编辑该文件自定义 Gateway 的行为指令。
 * 首次运行自动创建默认提示文件。
 */
public class GatewayPromptManager {

    private static final Logger logger = LoggerFactory.getLogger(GatewayPromptManager.class);
    private static final Path PROMPT_FILE = InstallPaths.getInstallHome().resolve("gateway-prompt.skill.md");

    /**
     * 默认 Gateway 提示词。
     * 描述 Gateway 的角色：分析用户请求、提取任务特征、判断是否走流水线。
     */
    private static final String DEFAULT_PROMPT = """
            You are a request analyzer for Diatom Gateway. Analyze the user request and output ONLY a JSON object (no other text):

            {
              "taskType": "refactoring|bug_fix|testing|documentation|code_review|architecture|devops|feature|general",
              "requiredCapabilities": ["描述性能力短语", ...],
              "preferredModelTraits": ["reasoning", "coding", "design", ...],
              "complexity": 1-10,
              "sensitivity": 1-10,
              "expectedTokens": <int>,
              "budgetPriority": "quality|speed|cost",
              "fallbackAllowed": true|false,
              "pipelineRecommended": true|false,
              "suggestedWorkerId": "workerId or null if undecided",
              "reasoning": "brief explanation of why this worker is chosen",
              "workspaceHint": "project workspace path (e.g., /mnt/nas/project-x) or null if not applicable",
              "syncStrategy": "full_sync|skip",
              "syncReasoning": "sync strategy reasoning"
            }

            Sync strategy rules:
            - skip: Worker workspace matches project path (shared NAS/OSS file system), or task is read-only/query
            - full_sync: Worker has no project files AND task requires file modifications
            Based on the worker context above, choose the best sync strategy. If the worker's workspace path matches the project and files are likely shared, use skip. If the worker is remote with no project files and the task needs code changes, use full_sync.
            ${workerSection}
            IMPORTANT for requiredCapabilities: Extract meaningful **descriptive phrases** from the user's request that describe what skills are needed. Examples:
              - If the user asks about math/equations → include "数学计算"
              - If the user asks to write code/programming → include "代码编程"
              - If the user asks about Java/Spring → include "Java"
              - If the user asks about Python/data → include "Python"
              - Use natural language phrases that describe the domain (e.g., "数学", "编程", "代码", "数据分析")

            pipelineRecommended=true: complex multi-step tasks needing analysis then implementation (architecture, multi-file features, design+code). pipelineRecommended=false: simple Q&A, single-file edits, translations, info lookup.

            Rules: JSON only. Be conservative with pipeline. complexity 1-3=simple 4-6=moderate 7-10=complex. budgetPriority: quality default, speed on urgent, cost on cheap. Lowercase enums.""";

    public GatewayPromptManager() {
        ensurePromptFileExists();
    }

    /**
     * 确保提示文件在磁盘上存在。首次运行时创建默认文件。
     */
    private void ensurePromptFileExists() {
        if (!Files.exists(PROMPT_FILE)) {
            try {
                Files.createDirectories(PROMPT_FILE.getParent());
                String defaultContent = "---\nname: gateway-prompt\ndescription: Gateway request analysis and routing instructions\nversion: 1.0.0\n---\n\n" + DEFAULT_PROMPT;
                Files.write(PROMPT_FILE, defaultContent.getBytes(StandardCharsets.UTF_8));
                logger.info("Created default gateway prompt at {}", PROMPT_FILE);
            } catch (IOException e) {
                logger.error("Failed to create default gateway prompt", e);
            }
        }
    }

    /**
     * 获取 Gateway 系统提示。
     * 优先从磁盘文件加载，用户可自定义；文件不存在时返回默认值。
     */
    public String getPrompt() {
        if (Files.exists(PROMPT_FILE)) {
            try {
                Skill skill = SkillParser.parse(PROMPT_FILE);
                return skill.getBody();
            } catch (Exception e) {
                logger.warn("Failed to parse gateway prompt file, using default", e);
            }
        }
        return DEFAULT_PROMPT;
    }

    /**
     * 获取 Gateway 系统提示，注入动态 worker 上下文。
     *
     * @param workerContext 可用 worker 的摘要信息，替换 prompt 中的 ${workerContext} 占位符
     */
    private static final String WORKER_SECTION_TEMPLATE = """
            {workerContext}

            Based on the available workers above, set suggestedWorkerId to the best matching worker ID.
            CRITICAL: Match the user's request against each worker's capabilities. For example:
              - User asks about math/equations → pick the worker with math-related capabilities
              - User asks about coding/programming → pick the worker with programming capabilities
              - User asks about Java, Spring, Python → pick the worker with those language capabilities

            Critical routing rules:
            1. Group affinity: If multiple workers are available, prefer workers in the same group.
            2. Capability match: Match requiredCapabilities against each worker's declared capabilities.
            3. Budget fit: Reject workers whose maxTokens is less than expectedTokens.
            4. Boundaries check: If a worker has "no internet access" and the task needs web access, do NOT recommend it.

            Set suggestedWorkerId to null ONLY IF:
              - You are unsure which worker is best
              - Multiple workers have equally matching capabilities
              - No worker's capabilities match the request""";

    public String getPrompt(String workerContext) {
        String prompt = getPrompt();
        if (workerContext != null && !workerContext.isEmpty()) {
            String section = WORKER_SECTION_TEMPLATE.replace("{workerContext}", workerContext);
            prompt = prompt.replace("${workerSection}", section);
        } else {
            prompt = prompt.replace("${workerSection}" + "\n" + "\n", "");
        }
        return prompt;
    }

    /**
     * 获取提示文件的路径（用于显示给用户）。
     */
    public static Path getPromptFilePath() {
        return PROMPT_FILE;
    }
}
