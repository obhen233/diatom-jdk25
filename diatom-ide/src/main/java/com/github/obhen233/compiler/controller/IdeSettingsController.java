package com.github.obhen233.compiler.controller;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.dto.settings.UpdateSettingsRequest;
import com.github.obhen233.compiler.entity.IdeSetting;
import com.github.obhen233.compiler.event.AiConfigChangedEvent;
import com.github.obhen233.compiler.repository.IdeSettingRepository;
import com.github.obhen233.starter.ide.IdeModeCapabilities;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IDE 设置接口：主题、JDK版本、环境路径等
 * 配置持久化到 SQLite，重启后自动恢复上次保存的设置。
 * 优先级：SQLite 已保存值 > application.properties 默认值 > 系统环境变量
 */
@CrossOrigin
@RestController
@RequestMapping("/ide")
@Tag(name = "IDE Settings / IDE设置", description = "IDE configuration management including theme, JDK version, AI settings / IDE配置管理，包括主题、JDK版本、AI设置等")
public class IdeSettingsController {

    private static final Logger log = LoggerFactory.getLogger(IdeSettingsController.class);

    @Resource
    private IdeSettingRepository settingRepo;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    // ---- properties 文件中的默认值 ----
    @Value("${ide.theme:dark}")
    private String defaultTheme;
    @Value("${ide.language:}")
    private String defaultLanguage;
    @Value("${ide.jdk.version:25}")
    private int defaultJdkVersion;
    @Value("${ide.java.home:}")
    private String configJavaHome;
    @Value("${ide.maven.home:}")
    private String configMavenHome;
    @Value("${ide.maven.user.settings:}")
    private String configMavenUserSettings;
    @Value("${ide.maven.local.repository:}")
    private String configMavenLocalRepo;
    @Value("${ide.gradle.user.home:}")
    private String configGradleUserHome;
    @Value("${ide.git.path:}")
    private String configGitPath;
    @Value("${ide.svn.path:}")
    private String configSvnPath;
    @Value("${ide.ai.api-url:}")
    private String configAiApiUrl;
    @Value("${ide.ai.api-token:}")
    private String configAiApiToken;
    @Value("${ide.ai.model:}")
    private String configAiModel;
    @Value("${ide.ai.enabled:false}")
    private String configAiEnabled;

    /**
     * Starter-provided mode capabilities. The IDE stays agnostic to the concrete
     * diatom mode and only asks {@link IdeModeCapabilities#requiresManualAiConfig()}
     * when deciding whether to show/maintain manual AI URL/key/model fields.
     */
    @Autowired(required = false)
    private IdeModeCapabilities modeCapabilities;

    // 所有配置 key 常量
    private static final String K_THEME = "theme";
    private static final String K_LANGUAGE = "language";
    private static final String K_JDK_VERSION = "jdkVersion";
    private static final String K_JAVA_HOME = "javaHome";
    private static final String K_MAVEN_HOME = "mavenHome";
    private static final String K_MAVEN_USER_SETTINGS = "mavenUserSettings";
    private static final String K_MAVEN_LOCAL_REPO = "mavenLocalRepository";
    private static final String K_GRADLE_USER_HOME = "gradleUserHome";
    private static final String K_GIT_PATH = "gitPath";
    private static final String K_SVN_PATH = "svnPath";
    private static final String K_AI_API_URL = "aiApiUrl";
    private static final String K_AI_API_TOKEN = "aiApiToken";
    private static final String K_AI_MODEL = "aiModel";
    private static final String K_AI_ENABLED = "aiEnabled";

    // 值常量
    private static final String K_THEME_DARK = "dark";
    private static final String K_THEME_LIGHT = "light";
    private static final String K_LANG_EN = "en";
    private static final String K_LANG_ZH = "zh";

    /**
     * 启动时：如果 SQLite 中没有某个 key，就用 properties 默认值 + 环境变量解析后写入
     */
    @PostConstruct
    public void init() {
        initIfAbsent(K_THEME, defaultTheme);
        initIfAbsent(K_LANGUAGE, detectLanguage());
        initIfAbsent(K_JDK_VERSION, String.valueOf(defaultJdkVersion));
        initIfAbsent(K_JAVA_HOME, resolveJavaHome(configJavaHome));
        initIfAbsent(K_MAVEN_HOME, resolveMavenHome(configMavenHome));
        initIfAbsent(K_MAVEN_USER_SETTINGS, blankToEmpty(configMavenUserSettings));
        initIfAbsent(K_MAVEN_LOCAL_REPO, blankToEmpty(configMavenLocalRepo));
        initIfAbsent(K_GRADLE_USER_HOME, resolveGradleUserHome(configGradleUserHome));
        initIfAbsent(K_GIT_PATH, resolveExePath(configGitPath, "git"));
        initIfAbsent(K_SVN_PATH, resolveExePath(configSvnPath, "svn"));
        initIfAbsent(K_AI_API_URL, blankToEmpty(configAiApiUrl));
        initIfAbsent(K_AI_API_TOKEN, blankToEmpty(configAiApiToken));
        initIfAbsent(K_AI_MODEL, blankToEmpty(configAiModel));
        initIfAbsent(K_AI_ENABLED, blankToEmpty(configAiEnabled));
        syncJdkVersion();

        // Notify IdeAiConfigService to refresh cache after AI defaults are populated
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new AiConfigChangedEvent(this));
        }
    }

    /** 将 SQLite 中保存的 JDK 版本同步到 Constants.jdkVersion（JdtCoreService 等组件读取） */
    private void syncJdkVersion() {
        Constants.jdkVersion = parseIntSafe(get(K_JDK_VERSION), defaultJdkVersion);
    }

    private void initIfAbsent(String key, String fallback) {
        String value = fallback != null ? fallback : "";
        IdeSetting existing = settingRepo.findById(key).orElse(null);
        if (existing == null) {
            settingRepo.save(new IdeSetting(key, value));
        } else {
            // Overwrite empty existing values with the fallback (important for AI keys
            // that may have been initialized as empty before the defaults were added)
            String existingVal = existing.getValue();
            if ((existingVal == null || existingVal.trim().isEmpty()) && !value.isEmpty()) {
                existing.setValue(value);
                settingRepo.save(existing);
            }
        }
    }

    // ==================== REST API ====================

    @GetMapping("/settings")
    @Operation(summary = "Get IDE settings / 获取IDE设置", description = "Returns all IDE settings including theme, JDK version, AI configuration / 返回所有IDE设置，包括主题、JDK版本、AI配置等")
    public ApiResponse<Map<String, Object>> getSettings() {
        try {
            Map<String, Object> m = new HashMap<>();
            m.put("theme", get(K_THEME));
            m.put("language", get(K_LANGUAGE));
            String jdkVer = get(K_JDK_VERSION);
            String javaHome = get(K_JAVA_HOME);
            String mavenHome = get(K_MAVEN_HOME);
            String mavenSettings = get(K_MAVEN_USER_SETTINGS);
            String mavenRepo = get(K_MAVEN_LOCAL_REPO);
            String gradleHome = get(K_GRADLE_USER_HOME);
            String gitPath = get(K_GIT_PATH);
            String svnPath = get(K_SVN_PATH);

            m.put("jdkVersion", parseIntSafe(jdkVer, 25));
            m.put("javaHome", javaHome);
            m.put("mavenHome", mavenHome);
            m.put("mavenUserSettings", mavenSettings);
            m.put("mavenLocalRepository", mavenRepo);
            m.put("gradleUserHome", gradleHome);
            m.put("gitPath", gitPath);
            m.put("svnPath", svnPath);
            m.put("aiApiUrl", get(K_AI_API_URL));
            m.put("aiApiToken", get(K_AI_API_TOKEN));
            m.put("aiModel", get(K_AI_MODEL));
            m.put("aiEnabled", "true".equalsIgnoreCase(get(K_AI_ENABLED)));
            m.put("aiConfigured", isAiConfigured());
            m.put("mode", currentMode());
            m.put("aiConfigRequired", requiresAiConfig());
            m.put("javaHomeConfigured", !isBlank(javaHome));
            m.put("mavenHomeConfigured", !isBlank(mavenHome));
            m.put("mavenUserSettingsConfigured", !isBlank(mavenSettings));
            m.put("mavenLocalRepositoryConfigured", !isBlank(mavenRepo));
            m.put("gradleUserHomeConfigured", !isBlank(gradleHome));
            m.put("gitPathConfigured", !isBlank(gitPath));
            m.put("svnPathConfigured", !isBlank(svnPath));
            return ApiResponse.ok(m);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // AI token 最大长度 2048
    private static final int MAX_AI_TOKEN_LENGTH = 2048;
    // AI token 允许的字符格式
    private static final String AI_TOKEN_PATTERN = "[A-Za-z0-9_\\-\\./:]+";

    @PutMapping("/settings")
    @Operation(summary = "Update IDE settings / 更新IDE设置", description = "Updates IDE settings. Changes are persisted to SQLite and take effect immediately. / 更新IDE设置。更改会持久化到SQLite并立即生效。")
    public ApiResponse<Map<String, Object>> updateSettings(@RequestBody UpdateSettingsRequest body) {
        try {
            // AI token 最大长度 2048
            // AI token 允许的字符格式
            String AI_TOKEN_PATTERN = "[A-Za-z0-9_\\-\\./:]+";

            if (body.theme() != null) {
                String t = body.theme();
                if (K_THEME_DARK.equals(t) || K_THEME_LIGHT.equals(t)) put(K_THEME, t);
            }
            if (body.language() != null) {
                String l = body.language();
                if (K_LANG_EN.equals(l) || K_LANG_ZH.equals(l)) put(K_LANGUAGE, l);
            }
            if (body.jdkVersion() != null) {
                int v = body.jdkVersion();
                if (v >= 5 && v <= 25) put(K_JDK_VERSION, String.valueOf(v));
            }
            syncJdkVersion();
            if (body.javaHome() != null) put(K_JAVA_HOME, body.javaHome());
            if (body.mavenHome() != null) put(K_MAVEN_HOME, body.mavenHome());
            if (body.mavenUserSettings() != null) put(K_MAVEN_USER_SETTINGS, body.mavenUserSettings());
            if (body.mavenLocalRepository() != null) put(K_MAVEN_LOCAL_REPO, body.mavenLocalRepository());
            if (body.gradleUserHome() != null) put(K_GRADLE_USER_HOME, body.gradleUserHome());
            if (body.gitPath() != null) put(K_GIT_PATH, body.gitPath());
            if (body.svnPath() != null) put(K_SVN_PATH, body.svnPath());

            // AI settings - these trigger config cache refresh
            boolean aiConfigChanged = false;
            // When the starter says manual AI config is NOT required (e.g. api/adapter
            // modes route AI through a remote/external connection), skip saving local
            // AI URL/Key/Model settings.
            if (requiresAiConfig()) {
                if (body.aiApiUrl() != null) {
                    put(K_AI_API_URL, body.aiApiUrl());
                    aiConfigChanged = true;
                }
                if (body.aiApiToken() != null) {
                    String token = body.aiApiToken();
                    // 验证 token 格式和长度
                    if (token.length() > MAX_AI_TOKEN_LENGTH) {
                        return ApiResponse.fail("AI token too long (max " + MAX_AI_TOKEN_LENGTH + " characters)");
                    }
                    if (!token.matches(AI_TOKEN_PATTERN)) {
                        return ApiResponse.fail("Invalid AI token format");
                    }
                    put(K_AI_API_TOKEN, token);
                    aiConfigChanged = true;
                }
                if (body.aiModel() != null) {
                    put(K_AI_MODEL, body.aiModel());
                    aiConfigChanged = true;
                }
            }
            if (body.aiEnabled() != null) {
                put(K_AI_ENABLED, String.valueOf(body.aiEnabled()));
                aiConfigChanged = true;
            }

            // Publish event if any AI config changed
            if (aiConfigChanged && eventPublisher != null) {
                eventPublisher.publishEvent(new AiConfigChangedEvent(this));
            }

            return getSettings();
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    // ==================== 供其他组件调用 ====================

    public int getCurrentJdkVersion() {
        return parseIntSafe(get(K_JDK_VERSION), 25);
    }

    public String getCurrentJavaHome() {
        return get(K_JAVA_HOME);
    }

    // ==================== SQLite 读写 ====================

    private String get(String key) {
        return settingRepo.findById(key).map(IdeSetting::getValue).orElse("");
    }

    private void put(String key, String value) {
        settingRepo.save(new IdeSetting(key, value != null ? value : ""));
    }

    // ==================== 环境变量解析 ====================

    private String resolveJavaHome(String configured) {
        if (!isBlank(configured)) return configured;
        String sysJavaHome = System.getProperty("java.home");
        if (!isBlank(sysJavaHome)) return sysJavaHome;
        String envJavaHome = System.getenv("JAVA_HOME");
        return envJavaHome != null ? envJavaHome : "";
    }

    private String resolveMavenHome(String configured) {
        if (!isBlank(configured)) return configured;
        String m2 = System.getenv("M2_HOME");
        if (!isBlank(m2)) return m2;
        String mh = System.getenv("MAVEN_HOME");
        return mh != null ? mh : "";
    }

    private String resolveGradleUserHome(String configured) {
        if (!isBlank(configured)) return configured;
        String gh = System.getenv("GRADLE_USER_HOME");
        return gh != null ? gh : "";
    }

    private String blankToEmpty(String s) {
        return isBlank(s) ? "" : s;
    }

    /** 解析可执行文件路径：配置值 > 系统 PATH 中查找 */
    private String resolveExePath(String configured, String exeName) {
        if (!isBlank(configured)) return configured;
        // 验证 exeName 格式，防止命令注入
        if (!exeName.matches("[a-zA-Z0-9_\\-]+")) {
            log.debug("Invalid exeName: {}", exeName);
            return "";
        }
        // 尝试从 PATH 中找
        try {
            Process p;
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                p = Runtime.getRuntime().exec(new String[]{"where", exeName});
            } else {
                p = Runtime.getRuntime().exec(new String[]{"which", exeName});
            }
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line = br.readLine();
            p.waitFor();
            if (line != null && !line.trim().isEmpty()) return line.trim();
        } catch (Exception e) {
            log.debug("Failed to find {} in PATH: {}", exeName, e.getMessage());
        }
        return "";
    }

    /** 检测系统默认语言 */
    private String detectLanguage() {
        if (!isBlank(defaultLanguage)) {
            return defaultLanguage;
        }
        // 检测系统语言
        String lang = System.getProperty("user.language", "");
        if ("zh".equalsIgnoreCase(lang)) {
            return "zh";
        }
        return "en";
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Check if AI is effectively configured for the current mode.
     * When the starter says manual AI config is not required, AI is considered
     * configured (handled through a remote/external connection).
     * Otherwise requires both URL and token.
     */
    private boolean isAiConfigured() {
        if (!requiresAiConfig()) {
            return true;
        }
        return !isBlank(get(K_AI_API_URL)) && !isBlank(get(K_AI_API_TOKEN));
    }

    /**
     * Whether the IDE needs to maintain manual AI URL/key/model config.
     * Delegates to the starter-provided {@link IdeModeCapabilities}; falls back
     * to {@code true} (show fields) if the bean is not available.
     */
    private boolean requiresAiConfig() {
        return modeCapabilities == null || modeCapabilities.requiresManualAiConfig();
    }

    /**
     * Current diatom mode string, for display only. The IDE must NOT interpret
     * specific mode values — capabilities come from {@link IdeModeCapabilities}.
     */
    private String currentMode() {
        return modeCapabilities != null ? modeCapabilities.getMode() : "standard";
    }

    private int parseIntSafe(String s, int fallback) {
        try { return Integer.parseInt(s); }
        catch (Exception e) { return fallback; }
    }
}
