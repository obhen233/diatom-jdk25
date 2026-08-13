package com.github.obhen233.core;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.entity.SystemConfigEntity;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.spi.ConfigProvider;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.spi.SystemConfigProvider;
import com.github.obhen233.util.ApiUrlUtils;
import com.github.obhen233.util.I18n;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;

/**
 * Shared initialization logic for both CLI (App.java) and Spring Boot (DiatomAutoConfiguration).
 *
 * Extracted to eliminate duplication between the two entry points.
 * All methods are static utility methods.
 */
public class CoreInitializer {

    private static final Logger logger = LoggerFactory.getLogger(CoreInitializer.class);

    /**
     * Initialize I18n with the configured language from AppConfig.
     */
    public static void initI18n(AppConfig config) {
        I18n.init(config.getLanguage());
        logger.info("I18n initialized with language: {}", config.getLanguage());
    }

    /**
     * Load SPI extensions and apply ConfigProvider extensions.
     * Calls SpiLoader.loadAll(), then iterates over all ConfigProvider
     * implementations to merge additional properties and notify onConfigLoaded.
     */
    public static void loadSpiExtensions(AppConfig config) {
        SpiLoader.loadAll();
        for (ConfigProvider cp : SpiLoader.getAll(ConfigProvider.class)) {
            Properties extra = cp.getAdditionalProperties();
            if (extra != null && !extra.isEmpty()) {
                extra.forEach((k, v) -> config.setProperty((String) k, (String) v));
            }
        }
        for (ConfigProvider cp : SpiLoader.getAll(ConfigProvider.class)) {
            cp.onConfigLoaded(config);
        }
    }

    /**
     * Register custom config items from SystemConfigProvider SPI implementations.
     * Inserts config definitions into the system_config table.
     */
    public static void registerSystemConfigExtensions(DatabaseManager dbManager) {
        if (dbManager == null) return;
        SessionFactory sf = dbManager.getSessionFactory();
        if (sf == null) return;
        for (SystemConfigProvider provider : SpiLoader.getAll(SystemConfigProvider.class)) {
            try {
                List<SystemConfigProvider.ConfigDefinition> configs = provider.getConfigDefinitions();
                if (configs == null || configs.isEmpty()) {
                    continue;
                }

                if (sf != null) {
                    try (Session session = sf.openSession()) {
                        session.beginTransaction();
                        long now = System.currentTimeMillis();
                        for (SystemConfigProvider.ConfigDefinition def : configs) {
                            if (def.key == null || def.key.isEmpty()) continue;
                            // Check if key already exists
                            Long count = session.createQuery(
                                    "SELECT COUNT(*) FROM SystemConfigEntity WHERE configKey = :key", Long.class)
                                    .setParameter("key", def.key)
                                    .uniqueResult();
                            if (count != null && count > 0) continue;

                            SystemConfigEntity entity = new SystemConfigEntity();
                            entity.setConfigKey(def.key);
                            entity.setConfigValue(def.defaultValue);
                            entity.setConfigType(def.type != null ? def.type : "string");
                            entity.setCategory(def.category != null ? def.category : "custom");
                            entity.setAllowedValues(def.allowedValues);
                            entity.setMinValue(def.minValue);
                            entity.setMaxValue(def.maxValue);
                            entity.setDefaultValue(def.defaultValue);
                            entity.setSource("custom");
                            entity.setLastModified(now);
                            entity.setCreatedAt(now);
                            session.persist(entity);
                        }
                        session.getTransaction().commit();
                        logger.debug("Registered {} config items from SystemConfigProvider: {}",
                            configs.size(), provider.getClass().getName());
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to register SystemConfigProvider {}: {}", provider.getClass().getName(), e.getMessage());
            }
        }
    }

    /**
     * Detect whether the Anthropic API format should be used.
     * Detection priority:
     * 1. api.format=anthropic → true
     * 2. api.format=openai → false
     * 3. api.format=auto → heuristic detection:
     *    - model name contains "claude" → true
     *    - endpoint contains "/v1/messages" → true
     *    - baseUrl contains "anthropic.com" → true
     *    - otherwise → false
     */
    public static boolean detectAnthropicFormat(AppConfig config) {
        String format = config.getApiFormat();
        String model = config.getModel();
        String endpoint = config.getEndpoint();
        String baseUrl = config.getBaseUrl();

        if ("anthropic".equals(format)) return true;
        if ("openai".equals(format)) return false;
        if ("responses".equals(format)) return false;

        // auto mode: heuristic detection
        if (model != null && model.toLowerCase().contains("claude")) return true;
        if (endpoint != null && endpoint.toLowerCase().contains("/v1/messages")) return true;
        if (baseUrl != null && baseUrl.toLowerCase().contains("anthropic.com")) return true;

        return false;
    }

    /**
     * Detect whether the OpenAI Responses API format should be used.
     * <p>
     * Unlike Anthropic detection, this is purely explicit-config driven — no
     * model-name / baseUrl heuristics:
     * <ol>
     *   <li>{@code api.format=anthropic|openai} → false</li>
     *   <li>{@code api.format=responses} → true</li>
     *   <li>otherwise ({@code auto}): explicit {@code api.endpoint} contains
     *       {@code /responses} → true</li>
     *   <li>otherwise → false</li>
     * </ol>
     */
    public static boolean detectResponsesFormat(AppConfig config) {
        String format = config.getApiFormat();
        if ("anthropic".equals(format) || "openai".equals(format)) return false;
        if ("responses".equals(format)) return true;
        String endpoint = config.getEndpoint();
        if (endpoint != null && endpoint.toLowerCase().contains("/responses")) return true;
        return false;
    }

    /**
     * Resolve the Responses API endpoint URL.
     * <ol>
     *   <li>explicit {@code api.endpoint} → {@code baseUrl + endpoint}</li>
     *   <li>otherwise → {@code ApiUrlUtils.openaiResponsesUrl(baseUrl)}</li>
     * </ol>
     */
    public static String resolveResponsesEndpoint(AppConfig config) {
        String endpoint = config.getEndpoint();
        if (endpoint != null && !endpoint.isEmpty()) {
            return ApiUrlUtils.join(config.getBaseUrl(), endpoint);
        }
        return ApiUrlUtils.openaiResponsesUrl(config.getBaseUrl());
    }

    /**
     * Detect auth style from format string and model name.
     */
    public static AiHttpClient.AuthStyle detectAuthStyle(String format, String model) {
        if ("anthropic".equalsIgnoreCase(format)) {
            return AiHttpClient.AuthStyle.ANTHROPIC;
        }
        if ("openai".equalsIgnoreCase(format)) {
            return AiHttpClient.AuthStyle.BEARER;
        }
        if ("responses".equalsIgnoreCase(format)) {
            return AiHttpClient.AuthStyle.BEARER;
        }
        // auto-detect from model name
        if (model != null && model.toLowerCase().contains("claude")) {
            return AiHttpClient.AuthStyle.ANTHROPIC;
        }
        return AiHttpClient.AuthStyle.BEARER;
    }

    /**
     * Create an AiHttpClient based on AppConfig settings.
     * Detects Anthropic vs OpenAI format automatically.
     */
    public static AiHttpClient createHttpClient(AppConfig config) {
        boolean isAnthropic = detectAnthropicFormat(config);
        if (isAnthropic) {
            return new AiHttpClient(config.getApiKey(), config.getBaseUrl(), AiHttpClient.AuthStyle.ANTHROPIC);
        }
        return new AiHttpClient(config.getApiKey(), config.getBaseUrl());
    }

    /**
     * Resolve Anthropic-format API endpoint URL.
     * Priority:
     * 1. api.endpoint explicitly configured → baseUrl + endpoint
     * 2. deepseek model → baseUrl + /anthropic/v1/messages (only if baseUrl doesn't already contain /anthropic)
     * 3. others → baseUrl + /v1/messages (or /messages when baseUrl already ends with /v1)
     */
    public static String resolveAnthropicEndpoint(AppConfig config) {
        String endpoint = config.getEndpoint();
        if (endpoint != null && !endpoint.isEmpty()) {
            return ApiUrlUtils.join(config.getBaseUrl(), endpoint);
        }
        String baseUrl = config.getBaseUrl();
        String model = config.getModel().toLowerCase();
        if (model.contains("deepseek")) {
            // Avoid duplicating /anthropic if baseUrl already contains it
            // e.g. baseUrl=https://api.deepseek.com/anthropic → join /v1/messages
            //      baseUrl=https://api.deepseek.com       → join /anthropic/v1/messages
            if (baseUrl.contains("/anthropic")) {
                return ApiUrlUtils.anthropicMessagesUrl(baseUrl);
            }
            return ApiUrlUtils.join(baseUrl, "/anthropic/v1/messages");
        }
        return ApiUrlUtils.anthropicMessagesUrl(baseUrl);
    }
}
