package com.github.obhen233.core.adapter;

import com.github.obhen233.util.ApiUrlUtils;

import java.util.*;

/**
 * Provider endpoint resolver for multi-provider API support.
 * Maps model names to their corresponding API endpoints using
 * provider-family prefix matching with longest-prefix-first strategy.
 * <p>
 * Any model variant from a registered provider family is automatically
 * supported — no need to list every variant explicitly.
 */
public class ProviderRegistry {

    private static final Map<String, String> PROVIDER_PREFIXES = new HashMap<>();
    private static final List<String> SORTED_PREFIXES = new ArrayList<>();
    private static final String DEFAULT_ENDPOINT = "/chat/completions";
    private static final String DEFAULT_OPENAI_URL = "/v1/chat/completions";

    static {
        // Register provider families by shortest unique prefix.
        // Matching uses longest-prefix-first, so more specific prefixes
        // (e.g. "gpt-4o") take priority over shorter ones (e.g. "gpt-4").

        // ==================== International ====================

        // ---- OpenAI ----
        register("gpt-4.1");
        register("gpt-4.5");
        register("gpt-4o");
        register("gpt-4-turbo");
        register("gpt-4");
        register("gpt-3.5-turbo");
        register("o4");
        register("o3");
        register("o1");
        register("gpt");              // catch-all: gpt-*

        // ---- Anthropic Claude ----
        register("claude-4", "/v1/messages");
        register("claude-3-5", "/v1/messages");
        register("claude-3", "/v1/messages");
        register("claude-2", "/v1/messages");
        register("claude-instant", "/v1/messages");
        register("claude", "/v1/messages");           // catch-all: claude-*

        // ---- Google Gemini ----
        register("gemini-2.5");
        register("gemini-2.0");
        register("gemini-1.5");
        register("gemini");           // catch-all: gemini-*

        // ---- xAI Grok ----
        register("grok-3");
        register("grok-2");
        register("grok");             // catch-all: grok-*

        // ---- Meta Llama ----
        register("llama-4");
        register("llama-3");
        register("llama-2");
        register("llama");            // catch-all: llama-*

        // ---- Mistral AI ----
        register("mistral-large");
        register("mistral-small");
        register("mistral-nemo");
        register("codestral");
        register("pixtral");
        register("mistral");          // catch-all: mistral-*

        // ---- DeepSeek ----
        register("deepseek-reasoner");
        register("deepseek-v3");
        register("deepseek-v2");
        register("deepseek-coder");
        register("deepseek-chat");
        register("deepseek");         // catch-all: deepseek-*

        // ---- Qwen (Alibaba) ----
        register("qwen3");
        register("qwen2.5");
        register("qwq");              // Qwen reasoning model
        register("qwen");             // catch-all: qwen-*

        // ---- Cohere ----
        register("command-r");
        register("command");
        register("cohere");           // catch-all: cohere-*

        // ---- Perplexity ----
        register("sonar-deep-research");
        register("sonar");            // catch-all: sonar-*

        // ---- Amazon Nova ----
        register("amazon-nova");      // catch-all: amazon-nova-*

        // ---- AI21 Labs ----
        register("jamba-1.5");
        register("jamba");
        register("jurassic");         // catch-all: jurassic-*

        // ---- Reka ----
        register("reka");             // catch-all: reka-*

        // ---- Other international ----
        register("mixtral");
        register("yi");
        register("snowflake");
        register("abab6");
        register("abab");
        register("voyage");

        // ==================== Chinese / Asian ====================

        // ---- Yi (01.AI) ----
        register("yi-lightning");
        register("yi-large");
        register("yi");               // catch-all: yi-*

        // ---- Zhipu AI (智谱 GLM) ----
        register("glm-4v");
        register("glm-4");
        register("glm-3");
        register("glm");              // catch-all: glm-*

        // ---- Moonshot / Kimi (月之暗面) ----
        register("kimi");             // catch-all: kimi-*
        register("moonshot");         // catch-all: moonshot-*

        // ---- MiniMax / Hailuo AI (稀宇科技) ----
        register("minimax-m1");
        register("minimax-t2");
        register("minimax-m2");
        register("minimax");          // catch-all: minimax-*

        // ---- ByteDance Doubao (字节跳动豆包) ----
        register("doubao-1.5");
        register("doubao");           // catch-all: doubao-*

        // ---- Baichuan AI (百川智能) ----
        register("baichuan4");
        register("baichuan3");
        register("baichuan2");
        register("baichuan");         // catch-all: baichuan-*

        // ---- StepFun (阶跃星辰) ----
        register("step-2");
        register("step-1");
        register("step");             // catch-all: step-*

        // ---- Tencent Hunyuan (腾讯混元) ----
        register("hunyuan-turbo");
        register("hunyuan");          // catch-all: hunyuan-*

        // ---- SenseTime (商汤科技) ----
        register("sensechat");
        register("sensenova");
        register("sense");            // catch-all: sense-*
    }

    private static void register(String prefix) {
        register(prefix, DEFAULT_ENDPOINT);
    }

    private static synchronized void register(String prefix, String endpoint) {
        PROVIDER_PREFIXES.put(prefix.toLowerCase(), endpoint);
        rebuildSortedPrefixes();
    }

    /** Rebuild the prefix list sorted by length descending (longest match first). */
    private static void rebuildSortedPrefixes() {
        SORTED_PREFIXES.clear();
        SORTED_PREFIXES.addAll(PROVIDER_PREFIXES.keySet());
        SORTED_PREFIXES.sort((a, b) -> Integer.compare(b.length(), a.length()));
    }

    /**
     * Resolves the API endpoint for a given model.
     *
     * @param model   The model name
     * @param baseUrl The base URL (e.g., https://api.openai.com)
     * @return The full URL to use for the API call
     */
    public static String resolveEndpoint(String model, String baseUrl) {
        return resolveEndpoint(model, baseUrl, false);
    }

    /**
     * Resolves the API endpoint for a given model, with optional strict mode.
     * <p>
     * The returned URL is built by joining the base URL with the provider's
     * endpoint path. If the base URL already ends with {@code /v1}, duplicated
     * version segments are avoided (e.g. {@code /coding/v1} + {@code /v1/chat/completions}
     * becomes {@code /coding/v1/chat/completions}).
     *
     * @param model   The model name
     * @param baseUrl The base URL
     * @param strict  If true, throws exception for unknown models
     * @return The full URL or throws exception
     * @throws IllegalArgumentException if model not recognized in strict mode
     */
    public static String resolveEndpoint(String model, String baseUrl, boolean strict) {
        if (model == null || model.isEmpty()) {
            return ApiUrlUtils.openaiChatUrl(baseUrl);
        }

        String modelLower = model.toLowerCase();

        // 1. Exact match first
        String endpoint = PROVIDER_PREFIXES.get(modelLower);
        if (endpoint != null) {
            return ApiUrlUtils.join(baseUrl, endpoint);
        }

        // 2. Longest-prefix-first matching — most specific family prefix wins
        for (String prefix : SORTED_PREFIXES) {
            if (modelLower.startsWith(prefix)) {
                return ApiUrlUtils.join(baseUrl, PROVIDER_PREFIXES.get(prefix));
            }
        }

        if (strict) {
            throw new IllegalArgumentException(
                "未找到模型 [" + model + "] 对应的API端点。请检查模型名称是否正确，或联系开发者添加该模型的支持。"
            );
        }

        return ApiUrlUtils.openaiChatUrl(baseUrl);
    }

    /**
     * Checks if a model is known/registered.
     */
    public static boolean isKnownModel(String model) {
        if (model == null || model.isEmpty()) {
            return false;
        }
        String modelLower = model.toLowerCase();
        if (PROVIDER_PREFIXES.containsKey(modelLower)) {
            return true;
        }
        for (String prefix : SORTED_PREFIXES) {
            if (modelLower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
