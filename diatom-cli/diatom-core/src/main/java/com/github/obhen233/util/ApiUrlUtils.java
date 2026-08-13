package com.github.obhen233.util;

/**
 * Utility for building API endpoint URLs without duplicating path segments.
 * <p>
 * Providers such as Kimi Code API accept different base URL conventions:
 * <ul>
 *   <li>OpenAI-compatible: {@code https://api.kimi.com/coding/v1} + {@code /chat/completions}</li>
 *   <li>Anthropic-compatible: {@code https://api.kimi.com/coding/} + {@code /v1/messages}</li>
 * </ul>
 * This helper detects whether the base URL already contains the leading API version path
 * and avoids generating paths like {@code /v1/v1/chat/completions}.
 */
public class ApiUrlUtils {

    private ApiUrlUtils() {
    }

    /**
     * Join a base URL with a relative path, normalizing slashes and avoiding duplicated
     * version prefixes.
     *
     * @param baseUrl the base URL, may end with or without a trailing slash,
     *                and may already contain {@code /v1}
     * @param path    the relative path, must start with {@code /}
     * @return the combined URL
     */
    public static String join(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return path;
        }
        if (path == null || path.isEmpty()) {
            return baseUrl;
        }

        String normalizedBase = baseUrl.replaceAll("/+$", "");
        String normalizedPath = path;
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        // Avoid duplicating a trailing /v1 in base with a leading /v1 in path.
        // e.g. base=https://api.kimi.com/coding/v1, path=/v1/chat/completions
        //      -> https://api.kimi.com/coding/v1/chat/completions
        if (normalizedPath.startsWith("v1/") && normalizedBase.endsWith("/v1")) {
            normalizedPath = normalizedPath.substring("v1/".length());
        }

        return normalizedBase + "/" + normalizedPath;
    }

    /**
     * Build an OpenAI-compatible chat completions URL.
     * <p>
     * If {@code baseUrl} already ends with {@code /v1}, returns {@code baseUrl/chat/completions}.
     * Otherwise returns {@code baseUrl/v1/chat/completions}.
     *
     * @param baseUrl the provider base URL
     * @return full chat completions URL
     */
    public static String openaiChatUrl(String baseUrl) {
        if (baseUrl != null && baseUrl.replaceAll("/+$", "").endsWith("/v1")) {
            return join(baseUrl, "/chat/completions");
        }
        return join(baseUrl, "/v1/chat/completions");
    }

    /**
     * Build an Anthropic-compatible messages URL.
     * <p>
     * If {@code baseUrl} already ends with {@code /v1}, returns {@code baseUrl/messages}.
     * Otherwise returns {@code baseUrl/v1/messages}.
     *
     * @param baseUrl the provider base URL
     * @return full messages URL
     */
    public static String anthropicMessagesUrl(String baseUrl) {
        if (baseUrl != null && baseUrl.replaceAll("/+$", "").endsWith("/v1")) {
            return join(baseUrl, "/messages");
        }
        return join(baseUrl, "/v1/messages");
    }

    /**
     * Build an OpenAI Responses API URL.
     * <p>
     * If {@code baseUrl} already ends with {@code /v1}, returns {@code baseUrl/responses}.
     * Otherwise returns {@code baseUrl/v1/responses}.
     *
     * @param baseUrl the provider base URL
     * @return full Responses API URL
     */
    public static String openaiResponsesUrl(String baseUrl) {
        if (baseUrl != null && baseUrl.replaceAll("/+$", "").endsWith("/v1")) {
            return join(baseUrl, "/responses");
        }
        return join(baseUrl, "/v1/responses");
    }

    /**
     * Convert a Responses API URL to the corresponding chat completions URL.
     * <p>
     * If {@code responsesUrl} ends with {@code /responses}, the suffix is replaced
     * with {@code /chat/completions}. Otherwise the URL is returned unchanged.
     *
     * @param responsesUrl the Responses API URL
     * @return the equivalent chat completions URL, or the input unchanged
     */
    public static String responsesToChatUrl(String responsesUrl) {
        if (responsesUrl == null || responsesUrl.isEmpty()) {
            return responsesUrl;
        }
        String normalized = responsesUrl.replaceAll("/+$", "");
        if (normalized.endsWith("/responses")) {
            String base = normalized.substring(0, normalized.length() - "/responses".length());
            return join(base, "/chat/completions");
        }
        return responsesUrl;
    }
}
