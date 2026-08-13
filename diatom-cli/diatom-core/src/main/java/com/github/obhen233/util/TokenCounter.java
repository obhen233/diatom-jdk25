package com.github.obhen233.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Precise token counting using jtokkit library.
 * Supports CL100K_BASE encoding used by GPT-4, GPT-3.5-turbo, and Claude models.
 */
public class TokenCounter {
    private static final Logger logger = LoggerFactory.getLogger(TokenCounter.class);

    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private static final Encoding DEFAULT_ENCODING = registry.getEncoding(EncodingType.CL100K_BASE);

    // Cache encoding instances per model
    private static final Map<String, Encoding> encodingCache = new ConcurrentHashMap<>();

    // Model name to encoding type mapping
    private static final Map<String, EncodingType> MODEL_ENCODING_MAP = new ConcurrentHashMap<>();

    static {
        // GPT-4 and GPT-3.5-turbo use CL100K_BASE
        MODEL_ENCODING_MAP.put("gpt-4", EncodingType.CL100K_BASE);
        MODEL_ENCODING_MAP.put("gpt-4-turbo", EncodingType.CL100K_BASE);
        MODEL_ENCODING_MAP.put("gpt-4o", EncodingType.CL100K_BASE);
        MODEL_ENCODING_MAP.put("gpt-3.5-turbo", EncodingType.CL100K_BASE);
        // Claude models - use CL100K_BASE as approximation (close enough)
        MODEL_ENCODING_MAP.put("claude-3-opus", EncodingType.CL100K_BASE);
        MODEL_ENCODING_MAP.put("claude-3-sonnet", EncodingType.CL100K_BASE);
        MODEL_ENCODING_MAP.put("claude-3-haiku", EncodingType.CL100K_BASE);
        MODEL_ENCODING_MAP.put("claude-3-5-sonnet", EncodingType.CL100K_BASE);
        MODEL_ENCODING_MAP.put("claude-sonnet-4-20250514", EncodingType.CL100K_BASE);
        MODEL_ENCODING_MAP.put("claude-opus-4-20250514", EncodingType.CL100K_BASE);
    }

    private Encoding encoding;

    public TokenCounter() {
        this("default");
    }

    public TokenCounter(String modelName) {
        this.encoding = getEncodingForModel(modelName);
    }

    /**
     * Get the appropriate encoding for a model name
     */
    private Encoding getEncodingForModel(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return DEFAULT_ENCODING;
        }

        // Normalize model name
        String normalizedName = modelName.toLowerCase();

        // Check cache first
        if (encodingCache.containsKey(normalizedName)) {
            return encodingCache.get(normalizedName);
        }

        // Find matching encoding type
        EncodingType encodingType = null;
        for (Map.Entry<String, EncodingType> entry : MODEL_ENCODING_MAP.entrySet()) {
            if (normalizedName.contains(entry.getKey().toLowerCase())) {
                encodingType = entry.getValue();
                break;
            }
        }

        Encoding encoding = encodingType != null
                ? registry.getEncoding(encodingType)
                : DEFAULT_ENCODING;

        encodingCache.put(normalizedName, encoding);
        return encoding;
    }

    /**
     * Count tokens in a text string
     */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return encoding.countTokens(text);
        } catch (Exception e) {
            logger.warn("Token counting failed, using fallback estimation: {}", e.getMessage());
            return estimateFallback(text);
        }
    }

    /**
     * Count tokens for a list of chat messages
     * This accounts for message formatting overhead
     */
    public int countMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int totalTokens = 0;
        for (ChatMessage message : messages) {
            totalTokens += countMessage(message);
        }

        // Add tokens for message formatting overhead (approximate)
        totalTokens += 3 * messages.size(); // Every message follows <im_start>{role/name}\n{content}<im_end>\n

        return totalTokens;
    }

    /**
     * Count tokens for a single chat message
     */
    public int countMessage(ChatMessage message) {
        if (message == null) {
            return 0;
        }

        int tokens = 0;

        // Role tokens
        String role = message.getRole();
        if (role != null) {
            tokens += count(role);
            tokens += 1; // for the role separator
        }

        // Content tokens
        String content = message.getContent();
        if (content != null) {
            tokens += count(content);
        }

        // Tool calls tokens
        List<ToolCall> toolCalls = message.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            for (ToolCall tc : toolCalls) {
                if (tc.getId() != null) {
                    tokens += count(tc.getId());
                }
                if (tc.getFunction() != null) {
                    if (tc.getFunction().getName() != null) {
                        tokens += count(tc.getFunction().getName());
                    }
                    if (tc.getFunction().getArguments() != null) {
                        tokens += count(tc.getFunction().getArguments());
                    }
                }
            }
        }

        // Tool call ID tokens
        String toolCallId = message.getToolCallId();
        if (toolCallId != null) {
            tokens += count(toolCallId);
        }

        return tokens;
    }

    /**
     * Fallback estimation when token counting fails
     * Uses simple heuristics: Chinese chars = 2 tokens, English = ~4 chars per token
     */
    private int estimateFallback(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                count += 2;
            } else if (Character.isWhitespace(c) || Character.isLetterOrDigit(c)) {
                count += 0.25;
            }
        }
        return (int) Math.ceil(count);
    }

    /**
     * Get the current encoding name
     */
    public String getEncodingName() {
        return encoding.getName();
    }
}
