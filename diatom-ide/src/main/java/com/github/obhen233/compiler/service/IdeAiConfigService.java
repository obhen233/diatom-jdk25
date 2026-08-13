package com.github.obhen233.compiler.service;

import com.github.obhen233.compiler.entity.IdeSetting;
import com.github.obhen233.compiler.event.AiConfigChangedEvent;
import com.github.obhen233.compiler.repository.IdeSettingRepository;
import com.github.obhen233.core.adapter.ModelAdapter;
import com.github.obhen233.core.http.AiHttpClient;
import com.github.obhen233.starter.AiConfigProvider;
import com.github.obhen233.starter.ide.IdeModeCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges SQLite-stored AI settings to diatom-core's AiHttpClient at runtime.
 *
 * IDE stores AI config (apiUrl, apiToken, model) in SQLite via IdeSettingRepository.
 * This service caches those values and syncs to AiHttpClient only when changed,
 * eliminating the need to call syncConfig() before every agent invocation.
 *
 * The config is:
 * - Loaded once at startup (@PostConstruct)
 * - Refreshed when AiConfigChangedEvent is published (event-driven)
 * - Periodically validated via scheduled check (fallback)
 */
@Service
public class IdeAiConfigService implements AiConfigProvider {

    private static final Logger logger = LoggerFactory.getLogger(IdeAiConfigService.class);

    // AI config keys
    private static final String KEY_API_URL = "aiApiUrl";
    private static final String KEY_API_TOKEN = "aiApiToken";
    private static final String KEY_MODEL = "aiModel";
    private static final String KEY_ENABLED = "aiEnabled";

    // Cached values (volatile for thread safety)
    private volatile String cachedApiUrl = "";
    private volatile String cachedApiToken = "";
    private volatile String cachedModel = "gpt-4";
    private volatile boolean cachedEnabled = false;
    private volatile boolean cacheValid = false;

    // Dirty flag indicates cache needs refresh
    private final AtomicBoolean dirty = new AtomicBoolean(true);

    // 缓存刷新锁，防止多个线程同时刷新
    private final Object refreshLock = new Object();

    @Autowired(required = false)
    private IdeSettingRepository settingRepo;

    @Autowired(required = false)
    private AiHttpClient aiHttpClient;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ObjectProvider<ModelAdapter> modelAdapterProvider;

    /**
     * Starter-provided mode capabilities. The IDE stays agnostic to the concrete
     * diatom mode; this bean only tells it whether manual AI URL/key/model config
     * is required for the running mode.
     */
    @Autowired(required = false)
    private IdeModeCapabilities modeCapabilities;

    /**
     * Initialize cache at startup.
     */
    @PostConstruct
    public void init() {
        if (settingRepo != null) {
            refreshCache();
        }
        // Publish initial sync event to ensure AiHttpClient is configured
        if (eventPublisher != null && aiHttpClient != null) {
            eventPublisher.publishEvent(new AiConfigChangedEvent(this));
        }
    }

    /**
     * Listen for AI config changes and invalidate cache.
     */
    @EventListener
    public void onAiConfigChanged(AiConfigChangedEvent event) {
        logger.debug("AI config change detected, invalidating cache");
        dirty.set(true);
    }

    /**
     * Scheduled periodic validation (every 5 minutes) as a fallback
     * in case events are missed (should rarely happen).
     */
    @Scheduled(fixedRate = 300000)
    public void periodicValidation() {
        if (dirty.get() || !cacheValid) {
            refreshCache();
        }
    }

    /**
     * Force refresh the cache from SQLite.
     * Call this only when you need guaranteed fresh data (e.g., before critical operations).
     */
    public void refreshCache() {
        if (settingRepo == null) {
            logger.warn("IdeSettingRepository not available, cannot refresh cache");
            return;
        }

        String apiUrl = settingRepo.findById(KEY_API_URL)
                .map(IdeSetting::getValue).orElse("");
        String apiToken = settingRepo.findById(KEY_API_TOKEN)
                .map(IdeSetting::getValue).orElse("");
        String model = settingRepo.findById(KEY_MODEL)
                .map(IdeSetting::getValue).orElse("gpt-4");
        boolean enabled = "true".equalsIgnoreCase(
                settingRepo.findById(KEY_ENABLED).map(IdeSetting::getValue).orElse("false"));

        // Update cached values
        this.cachedApiUrl = apiUrl;
        this.cachedApiToken = apiToken;
        this.cachedModel = model;
        this.cachedEnabled = enabled;
        this.cacheValid = true;
        this.dirty.set(false);

        logger.debug("AI config cache refreshed: url={}, model={}, enabled={}",
                maskToken(apiUrl), model, enabled);

        // Sync to AiHttpClient if available
        syncToHttpClient();
    }

    /**
     * Force refresh the cache from SQLite, then sync to AiHttpClient.
     * This is called before every critical operation (e.g., AI chat requests)
     * to ensure the latest configuration is always used.
     */
    public void syncConfig() {
        refreshCache();
    }

    private void syncToHttpClient() {
        if (aiHttpClient == null) {
            logger.warn("AiHttpClient not available, skipping config sync");
            return;
        }

        // Always sync: SharedAutoConfiguration creates AiHttpClient with an empty
        // key in IDE mode, and it is only populated here. Gateway routing LLM calls
        // (GatewayAgent) need this key, so the sync must run regardless of mode.
        if (!cachedApiUrl.isEmpty()) {
            aiHttpClient.setBaseUrl(cachedApiUrl);
        }
        if (!cachedApiToken.isEmpty()) {
            aiHttpClient.setApiKey(cachedApiToken);
        }
        aiHttpClient.setAuthStyle(detectAuthStyle(cachedModel));

        // Sync model to ModelAdapter so the API request body uses the latest model name.
        // Use ObjectProvider to safely resolve the bean without triggering circular dependency
        // (modelAdapter bean creation depends on IdeAiConfigService as AiConfigProvider).
        try {
            ModelAdapter adapter = modelAdapterProvider.getIfAvailable();
            if (adapter != null) {
                adapter.setModel(cachedModel);
                logger.info("ModelAdapter synced to model: {}", cachedModel);
            } else {
                logger.warn("ModelAdapter bean not yet available (startup in progress), "
                        + "model sync deferred — will retry on next config change or AI request");
            }
        } catch (Exception e) {
            logger.warn("ModelAdapter not yet available, deferring model sync: {} — "
                    + "will retry on next config change or AI request", e.getMessage());
        }

        logger.debug("AI config synced to HttpClient: url={}, model={}",
                maskToken(cachedApiUrl), cachedModel);
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 8) {
            return "***";
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    /**
     * Check if AI is enabled (from cache).
     */
    public boolean isAiEnabled() {
        ensureCacheValid();
        return cachedEnabled;
    }

    /**
     * Get the configured model name from cache.
     */
    public String getModel() {
        ensureCacheValid();
        return cachedModel;
    }

    /**
     * Get API URL from cache.
     */
    public String getApiUrl() {
        ensureCacheValid();
        return cachedApiUrl;
    }

    /**
     * Get API token from cache.
     */
    public String getApiToken() {
        ensureCacheValid();
        return cachedApiToken;
    }

    /**
     * Check if settings are properly configured.
     * When the starter says manual AI config is not required (e.g. api/adapter
     * modes route AI through a remote/external connection), AI is considered
     * configured. Otherwise requires both URL and token.
     */
    public boolean isConfigured() {
        if (modeCapabilities != null && !modeCapabilities.requiresManualAiConfig()) {
            return true;
        }
        ensureCacheValid();
        return !cachedApiUrl.isEmpty() && !cachedApiToken.isEmpty();
    }

    /**
     * Ensure cache is valid before reading. Uses double-checked locking
     * pattern: first check without lock, then acquire lock only if needed.
     * This minimizes lock contention while ensuring thread safety.
     */
    private void ensureCacheValid() {
        if (!cacheValid || dirty.get()) {
            synchronized (refreshLock) {
                // Double-check after acquiring lock
                if (!cacheValid || dirty.get()) {
                    refreshCache();
                }
            }
        }
    }

    private AiHttpClient.AuthStyle detectAuthStyle(String model) {
        if (model != null && model.toLowerCase().contains("claude")) {
            return AiHttpClient.AuthStyle.ANTHROPIC;
        }
        return AiHttpClient.AuthStyle.BEARER;
    }

    // ==================== AiConfigProvider Implementation ====================

    @Override
    public String getConfig(String key) {
        // 优先处理 workspacePath，它不在 SQLite 中但需要提供给 ReActAgent
        if ("workspacePath".equals(key)) {
            return com.github.obhen233.compiler.constant.Constants.workspacePath;
        }
        if (settingRepo == null) {
            return null;
        }
        return settingRepo.findById(key).map(IdeSetting::getValue).orElse(null);
    }

    @Override
    public java.util.Map<String, String> getAllConfigs() {
        java.util.Map<String, String> configs = new java.util.HashMap<>();
        if (settingRepo != null) {
            settingRepo.findAll().forEach(setting -> {
                configs.put(setting.getKey(), setting.getValue());
            });
        }
        // 添加 workspacePath 配置，确保 ReActAgent 使用正确的工作区路径
        if (com.github.obhen233.compiler.constant.Constants.workspacePath != null) {
            configs.put("workspacePath", com.github.obhen233.compiler.constant.Constants.workspacePath);
        }
        return configs;
    }

    @Override
    public String executeCoreCommand(String commandLine) {
        // Delegate to CoreCommandService if available
        if (coreCommandService != null) {
            return coreCommandService.executeCommand(commandLine);
        }
        return null;
    }

    @Override
    public String getCoreHelp(String lang) {
        // Delegate to CoreCommandService if available
        if (coreCommandService != null) {
            return coreCommandService.getCoreHelp(lang);
        }
        return null;
    }

    @Autowired(required = false)
    private CoreCommandService coreCommandService;
}
