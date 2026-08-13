package com.github.obhen233.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;

/**
 * Internationalization utility for user-facing messages
 */
public class I18n {
    private static final Logger logger = LoggerFactory.getLogger(I18n.class);
    private static final String BASE_NAME = "messages";
    private static final String DEFAULT_LANGUAGE = "en";

    private static String currentLanguage = DEFAULT_LANGUAGE;
    private static ResourceBundle bundle;

    private I18n() {}

    /**
     * Initialize I18n with specified language
     */
    public static synchronized void init(String language) {
        if (language == null || language.isEmpty()) {
            language = DEFAULT_LANGUAGE;
        }
        currentLanguage = language;
        // Use thread context class loader to properly load resources from JARs in classpath
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = I18n.class.getClassLoader();
        }
        final ClassLoader classLoader = cl;
        // Load properties directly — bypass ResourceBundle.getBundle() to avoid
        // complex caching and fallback issues across different classloader environments
        try {
            String resourceName = BASE_NAME + "_" + language + ".properties";
            final Properties merged = loadMergedProperties(classLoader, resourceName);
            if (merged != null) {
                // Wrap Properties as a read-only ResourceBundle (avoids store/load round-trip encoding issues)
                bundle = new ResourceBundle() {
                    @Override
                    protected Object handleGetObject(String key) {
                        return merged.getProperty(key);
                    }
                    @Override
                    public Enumeration<String> getKeys() {
                        return java.util.Collections.enumeration(merged.stringPropertyNames());
                    }
                };
                logger.info("I18n initialized with language: {}", language);
            } else {
                // Fallback: try English
                logger.warn("No messages found for language {}, falling back to English", language);
                resourceName = BASE_NAME + "_en.properties";
                final Properties mergedEn = loadMergedProperties(classLoader, resourceName);
                if (mergedEn != null) {
                    bundle = new ResourceBundle() {
                        @Override
                        protected Object handleGetObject(String key) {
                            return mergedEn.getProperty(key);
                        }
                        @Override
                        public Enumeration<String> getKeys() {
                            return java.util.Collections.enumeration(mergedEn.stringPropertyNames());
                        }
                    };
                }
                currentLanguage = DEFAULT_LANGUAGE;
            }
        } catch (java.io.IOException e) {
            logger.error("Failed to load messages for language {}: {}", language, e.getMessage());
        }
    }

    /**
     * Get current language
     */
    public static String getLanguage() {
        return currentLanguage;
    }

    /**
     * Get message by key
     */
    public static String get(String key) {
        if (bundle == null) {
            return key;
        }
        try {
            return bundle.getString(key);
        } catch (Exception e) {
            logger.debug("Missing i18n key: {}", key);
            return key;
        }
    }

    /**
     * Get message by key with parameters
     */
    public static String get(String key, Object... params) {
        String template = get(key);
        try {
            return new MessageFormat(template).format(params);
        } catch (Exception e) {
            return template;
        }
    }

    /**
     * Reload I18n with a new language at runtime.
     * Used by Spring Boot starter for dynamic language switching.
     * @param language the new language code (e.g., "en", "zh")
     */
    public static synchronized void reload(String language) {
        init(language);
        logger.info("I18n reloaded with language: {}", language);
    }

    /**
     * Check if key exists
     */
    public static boolean hasKey(String key) {
        if (bundle == null) return false;
        return bundle.containsKey(key);
    }

    /**
     * Format token count with k/m/g/t suffix for large numbers.
     * >=1,000 → *k, >=1,000,000 → *m, >=1,000,000,000 → *g, >=1,000,000,000,000 → *t
     */
    public static String formatTokenCount(long count) {
        if (count >= 1_000_000_000_000L) {
            return String.format("%.1ft", count / 1_000_000_000_000.0);
        } else if (count >= 1_000_000_000) {
            return String.format("%.1fg", count / 1_000_000_000.0);
        } else if (count >= 1_000_000) {
            return String.format("%.1fm", count / 1_000_000.0);
        } else if (count >= 1_000) {
            return String.format("%.1fk", count / 1_000.0);
        } else {
            return String.valueOf(count);
        }
    }

    /**
     * Load and merge properties from ALL matching resources on the classpath.
     * Uses first-wins strategy: once a key is loaded, subsequent files cannot overwrite it.
     * This ensures CLI's keys (e.g. token_usage_summary) are available even when
     * the IDE's messages_zh.properties is found first on the classpath.
     */
    private static Properties loadMergedProperties(ClassLoader classLoader, String resourceName) throws java.io.IOException {
        Enumeration<URL> urls = classLoader.getResources(resourceName);
        Properties merged = new Properties();
        boolean found = false;
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            found = true;
            try (java.io.InputStream is = url.openStream()) {
                Properties props = new Properties();
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                // First-wins merge: existing keys are NOT overwritten
                for (String key : props.stringPropertyNames()) {
                    if (!merged.containsKey(key)) {
                        merged.put(key, props.getProperty(key));
                    }
                }
            }
        }
        if (!found) return null;
        return merged;
    }

    /**
     * Resolve all {{i18nKey:param}} placeholders in a template string.
     * The result is prefixed with SUCCESS/ERROR/INFO markers for styled output.
     * Example: "SUCCESS {{cli.streaming.on}}" → "✓ Streaming mode: ON"
     * @param template string containing {{key:param}} placeholders
     * @return resolved string
     */
    public static String resolveTemplate(String template) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("{{", i);
            if (start == -1) {
                result.append(template.substring(i));
                break;
            }
            int end = template.indexOf("}}", start);
            if (end == -1) {
                result.append(template.substring(i));
                break;
            }
            result.append(template, i, start);
            String placeholder = template.substring(start + 2, end);
            String[] parts = placeholder.split(":", -1);
            String key = parts[0];
            String[] params = parts.length > 1 ? java.util.Arrays.copyOfRange(parts, 1, parts.length) : new String[0];
            result.append(get(key, (Object[]) params));
            i = end + 2;
        }
        return result.toString();
    }
}
