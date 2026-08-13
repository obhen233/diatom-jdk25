package com.github.obhen233.core.gateway.agent;

import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import com.github.obhen233.spi.LocalRequestRouter;
import com.github.obhen233.spi.RoutingFallbackHandler;
import com.github.obhen233.spi.RoutingResult;
import com.github.obhen233.spi.SpiLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Gateway 轻量 Agent
 * 使用 LLM 分析用户请求并提取任务特征向量用于路由决策。
 * <p>
 * 仅支持 LLM 模式，无规则回退。当 LLM 不可用时直接抛出异常，
 * 由上层（CLI / HTTP）统一返回提示信息。
 */
public class GatewayAgent {
    private static final Logger logger = LoggerFactory.getLogger(GatewayAgent.class);

    private final AiHttpClient httpClient;
    private final ModelAdapter adapter;
    private final String model;
    private final String baseUrl;
    private final GatewayPromptManager promptManager;
    private final WorkerRegistry registry;

    /** Default confidence threshold for local router results (0.0 - 1.0). */
    static final double LOCAL_ROUTER_THRESHOLD = 0.7;

    // Worker context cache
    private volatile String cachedWorkerContext;
    private volatile long lastWorkerContextBuild;
    private static final long WORKER_CONTEXT_CACHE_TTL_MS = 30000; // 30s

    public GatewayAgent(AiHttpClient httpClient, ModelAdapter adapter, String model, String baseUrl) {
        this(httpClient, adapter, model, baseUrl, new GatewayPromptManager(), null);
    }

    public GatewayAgent(AiHttpClient httpClient, ModelAdapter adapter, String model, String baseUrl,
                         GatewayPromptManager promptManager) {
        this(httpClient, adapter, model, baseUrl, promptManager, null);
    }

    public GatewayAgent(AiHttpClient httpClient, ModelAdapter adapter, String model, String baseUrl,
                         GatewayPromptManager promptManager, WorkerRegistry registry) {
        this.httpClient = httpClient;
        this.adapter = adapter;
        this.model = model;
        this.baseUrl = baseUrl;
        this.promptManager = promptManager;
        this.registry = registry;
    }

    public GatewayAgent(AiHttpClient httpClient, ModelAdapter adapter, String model, String baseUrl,
                         WorkerRegistry registry) {
        this(httpClient, adapter, model, baseUrl, new GatewayPromptManager(), registry);
    }

    /**
     * 分析用户请求，提取任务特征。
     * 优先尝试本地路由 SPI（无需 LLM 调用），若置信度不足则回退到 LLM。
     * LLM 失败时检查 SPI {@link RoutingFallbackHandler}，
     * 有自定义实现则委托，否则抛出异常。
     */
    public TaskRequirement analyzeRequest(String message) {
        // Try local routers first (pre-LLM, no cost)
        List<WorkerInfo> workers = registry != null ? registry.availableWorkers() : Collections.emptyList();
        List<LocalRequestRouter> localRouters = SpiLoader.getAll(LocalRequestRouter.class);
        if (localRouters != null && !localRouters.isEmpty()) {
            // Initialize routers with current workers and LLM access for dynamic category generation
            Function<String, String> llmFn = prompt -> {
                try {
                    List<ChatMessage> msgs = new ArrayList<>();
                    msgs.add(new ChatMessage("user", prompt));
                    String jsonBody = adapter.buildRequest(msgs, null, false);
                    String resp = httpClient.post(baseUrl, jsonBody);
                    ChatResponse chatResp = adapter.parseResponse(resp);
                    ChatMessage msg = chatResp.getMessage();
                    return msg != null ? msg.getContent() : "";
                } catch (Exception e) {
                    logger.warn("LLM category generation call failed: {}", e.getMessage());
                    return "";
                }
            };
            for (LocalRequestRouter router : localRouters) {
                try {
                    router.initialize(workers, llmFn);
                } catch (Exception e) {
                    logger.warn("Local router {} initialization failed: {}",
                            router.getClass().getName(), e.getMessage());
                }
            }
            for (LocalRequestRouter router : localRouters) {
                try {
                    RoutingResult result = router.route(message, workers);
                    if (result != null && result.getConfidence() >= LOCAL_ROUTER_THRESHOLD) {
                        logger.debug("Local router '{}' handled request with confidence {}",
                                result.getSource(), String.format("%.2f", result.getConfidence()));
                        return result.getRequirement();
                    }
                    if (result == null) {
                        logger.debug("Local router '{}' returned null (no match for message)",
                                router.getClass().getSimpleName());
                    } else {
                        logger.debug("Local router '{}' returned confidence {} (below threshold {})",
                                router.getClass().getSimpleName(),
                                String.format("%.2f", result.getConfidence()),
                                String.format("%.2f", LOCAL_ROUTER_THRESHOLD));
                    }
                } catch (Exception e) {
                    logger.warn("Local router {} failed: {}", router.getClass().getName(), e.getMessage());
                }
            }
        }
        if (localRouters != null && !localRouters.isEmpty()) {
            logger.debug("No local router matched the request ({} router(s) tried), falling back to LLM",
                    localRouters.size());
        }

        try {
            TaskRequirement llmResult = analyzeWithLLM(message);
            // Feed LLM classification back to local routers for self-learning
            if (localRouters != null) {
                for (LocalRequestRouter router : localRouters) {
                    try {
                        router.onClassified(message, llmResult.getTaskType());
                    } catch (Exception e) {
                        logger.warn("onClassified failed for {}: {}",
                                router.getClass().getName(), e.getMessage());
                    }
                }
            }
            return llmResult;
        } catch (Exception e) {
            // Check for custom SPI fallback handler
            RoutingFallbackHandler fallback = SpiLoader.getFirst(RoutingFallbackHandler.class, null);
            if (fallback != null) {
                TaskRequirement fallbackReq = fallback.handle(message, e, workers);
                if (fallbackReq != null) {
                    logger.debug("Using custom RoutingFallbackHandler: {}", fallback.getClass().getName());
                    return fallbackReq;
                }
            }
            throw new RuntimeException("Gateway routing model unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * LLM 模式：调用模型分析请求
     * 使用 GatewayPromptManager 提供的 system prompt + 用户原始输入
     */
    private TaskRequirement analyzeWithLLM(String message) throws Exception {
        String response = callLLM(message);
        return parseLLMResponse(response);
    }

    private String callLLM(String userMessage) {
        // Build worker context and inject into prompt
        String workerContext = buildWorkerContext();
        logger.debug("Worker context for LLM:\n{}", workerContext.isEmpty() ? "(empty - no workers)" : workerContext);
        String systemPrompt = promptManager.getPrompt(workerContext);
        logger.debug("System prompt (first 200 chars): {}", systemPrompt.substring(0, Math.min(200, systemPrompt.length())));

        try {
            // Build ChatMessage list and delegate to adapter for request format
            List<ChatMessage> chatMessages = new ArrayList<>();
            chatMessages.add(new ChatMessage("system", systemPrompt));
            chatMessages.add(new ChatMessage("user", userMessage));

            // Use adapter to build properly formatted request body (handles Anthropic, OpenAI, etc.)
            String jsonBody = adapter.buildRequest(chatMessages, null, false);
            logger.debug("Calling LLM: model={}, url={}", model, baseUrl);
            String response = httpClient.post(baseUrl, jsonBody);
            ChatResponse chatResponse = adapter.parseResponse(response);
            ChatMessage chatMessage = chatResponse.getMessage();
            return chatMessage != null ? chatMessage.getContent() : "";
        } catch (Exception e) {
            logger.warn("LLM call failed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private TaskRequirement parseLLMResponse(String response) {
        if (response == null) throw new RuntimeException("Empty LLM response");

        // 提取 JSON
        String json = response;
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = response.substring(start, end + 1);
        }

        TaskRequirement req = new TaskRequirement();

        req.setTaskType(extractJsonString(json, "taskType"));
        req.setComplexity(extractJsonInt(json, "complexity", 5));
        req.setSensitivity(extractJsonInt(json, "sensitivity", 3));
        req.setExpectedTokens(extractJsonInt(json, "expectedTokens", 4000));
        req.setBudgetPriority(extractJsonString(json, "budgetPriority"));
        req.setFallbackAllowed(!"false".equals(extractJsonString(json, "fallbackAllowed")));
        req.setPipelineRecommended("true".equals(extractJsonString(json, "pipelineRecommended")));

        // 解析数组字段
        req.setRequiredCapabilities(extractJsonArray(json, "requiredCapabilities"));
        req.setPreferredModelTraits(extractJsonArray(json, "preferredModelTraits"));

        // 解析 LLM 建议的 worker
        req.setSuggestedWorkerId(extractJsonString(json, "suggestedWorkerId"));
        req.setReasoning(extractJsonString(json, "reasoning"));

        // 解析 workspaceHint — deploy 任务需要的项目路径
        req.setWorkspaceHint(extractJsonString(json, "workspaceHint"));

        // 解析 syncStrategy — 项目文件同步策略
        req.setSyncStrategy(extractJsonString(json, "syncStrategy"));
        req.setSyncReasoning(extractJsonString(json, "syncReasoning"));

        logger.debug("LLM analysis complete: type={}, complexity={}, capabilities={}, suggestedWorker={}, syncStrategy={}",
                req.getTaskType(), req.getComplexity(), req.getRequiredCapabilities(),
                req.getSuggestedWorkerId(), req.getSyncStrategy());
        return req;
    }

    /**
     * 构建可用 worker 的摘要信息，注入到 LLM prompt 中帮助其选择合适的 worker。
     * 格式：
     * Available workers:
     * - worker01 (gpt-4, load 30%, group=math, tokens=128000): math, equations
     *   boundaries: 无互联网访问, 仅限工作区文件
     *   traits: reasoning, coding
     *   metrics: success=95%, latency=1200ms
     * - worker02 (gpt-4, load 10%): coding, python, java
     *   traits: coding
     */
    private String buildWorkerContext() {
        if (registry == null) return "";

        if (cachedWorkerContext != null && System.currentTimeMillis() - lastWorkerContextBuild < WORKER_CONTEXT_CACHE_TTL_MS) {
            return cachedWorkerContext;
        }

        List<WorkerInfo> workers = registry.availableWorkers();
        if (workers == null || workers.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("Available workers:\n");
        for (WorkerInfo w : workers) {
            // Main info line
            sb.append("- ").append(w.getWorkerId())
              .append(" (").append(w.getModel() != null ? w.getModel() : "unknown")
              .append(", load ").append(String.format("%.0f", w.getMetrics().getCurrentLoad() * 100))
              .append("%");
            // Append group info if available
            if (w.getGroup() != null && !w.getGroup().isEmpty()) {
                sb.append(", group=").append(w.getGroup());
            }
            // Append maxTokens if set
            if (w.getMaxTokens() > 0) {
                sb.append(", tokens=").append(w.getMaxTokens());
            }
            sb.append("): ");

            // Append capability names
            String caps = w.getCapabilities().keySet().stream()
                    .collect(Collectors.joining(", "));
            sb.append(caps.isEmpty() ? "general" : caps);

            // Sub-line: boundaries (up to 2)
            List<String> boundaries = w.getBoundaries();
            if (boundaries != null && !boundaries.isEmpty()) {
                int count = Math.min(boundaries.size(), 2);
                sb.append("\n  boundaries: ")
                  .append(boundaries.subList(0, count).stream().collect(Collectors.joining("; ")));
            }

            // Sub-line: traits
            List<String> traits = w.getTraits();
            if (traits != null && !traits.isEmpty()) {
                sb.append("\n  traits: ")
                  .append(String.join(", ", traits));
            }

            // Sub-line: metrics
            sb.append("\n  metrics: success=")
              .append(String.format("%.0f", w.getMetrics().getSuccessRate() * 100))
              .append("%, latency=")
              .append(String.format("%.0f", w.getMetrics().getAvgLatencyMs()))
              .append("ms");

            // Append workspace path if available
            String ws = w.getWorkspace();
            if (ws != null && !ws.isEmpty()) {
                sb.append(" [workspace=").append(ws).append("]");
            }
            // Show gateway profile for gateway-tier workers
            if ("gateway".equals(w.getTier())) {
                sb.append(" [GATEWAY]");
                String gp = w.getGatewayProfile();
                if (gp != null && !gp.isEmpty()) {
                    // Strip YAML frontmatter (between --- markers)
                    String body = gp;
                    if (body.startsWith("---")) {
                        int endIdx = body.indexOf("---", 3);
                        if (endIdx > 0) {
                            body = body.substring(endIdx + 3).trim();
                        }
                    }
                    if (!body.isEmpty()) {
                        sb.append("\n    ").append(body.replace("\n", "\n    "));
                    }
                }
            }
            sb.append("\n");
        }
        cachedWorkerContext = sb.toString();
        lastWorkerContextBuild = System.currentTimeMillis();
        return cachedWorkerContext;
    }

    // ========== JSON 解析工具 ==========

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    private int extractJsonInt(String json, String key, int defaultValue) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return defaultValue;
        start += search.length();
        // Skip spaces
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private List<String> extractJsonArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String search = "\"" + key + "\":[";
        int start = json.indexOf(search);
        if (start < 0) return result;
        start += search.length();
        int end = json.indexOf("]", start);
        if (end < 0) return result;
        String arrayContent = json.substring(start, end);
        Pattern p = Pattern.compile("\"([^\"]+)\"");
        Matcher m = p.matcher(arrayContent);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }
}
