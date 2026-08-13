package com.github.obhen233.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-configuration to expose core module's i18n messages merged with IDE's messages.
 * This ensures core's messages (cli.*, etc.) are available to the IDE.
 */
@AutoConfiguration
public class CoreI18nAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(CoreI18nAutoConfiguration.class);
    /** locale -> (key -> message), base is under Locale root */
    private static final Map<Locale, Properties> localeMessages = new HashMap<>();
    /** Pattern to match escaped Unicode: backslash-u-XXXX */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    static {
        // Base messages (fallback for all locales)
        localeMessages.put(Locale.ROOT, new Properties());
        loadIntoLocale(Locale.ROOT, "messages");
        // Chinese
        localeMessages.put(Locale.CHINESE, new Properties());
        loadIntoLocale(Locale.CHINESE, "messages_zh");
        // English
        localeMessages.put(Locale.ENGLISH, new Properties());
        loadIntoLocale(Locale.ENGLISH, "messages_en");

        logger.info("CoreI18nAutoConfiguration loaded: ROOT={} messages, ZH={}, EN={}",
                localeMessages.get(Locale.ROOT).size(),
                localeMessages.get(Locale.CHINESE).size(),
                localeMessages.get(Locale.ENGLISH).size());
    }

    private static void loadIntoLocale(Locale locale, String basename) {
        Properties props = localeMessages.get(locale);
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = CoreI18nAutoConfiguration.class.getClassLoader();
            }
            String resourceName = basename + ".properties";
            Enumeration<URL> resources = loader.getResources(resourceName);
            logger.debug("Searching for {} resources with classloader: {}", resourceName, loader);
            int count = 0;
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                logger.debug("Loading {} from: {}", resourceName, url);
                try (InputStream is = url.openStream()) {
                    // Always use UTF-8 to read properties files, then parse manually.
                    // This handles both UTF-8 encoded files and backslash-u-XXXX escape sequences correctly.
                    Properties p = loadPropertiesUtf8(is);
                    count += p.size();
                    // First resource wins for this locale
                    for (String key : p.stringPropertyNames()) {
                        if (!props.containsKey(key)) {
                            props.put(key, p.getProperty(key));
                        }
                    }
                    logger.debug("Loaded {} properties from {}", p.size(), url);
                } catch (IOException e) {
                    logger.warn("IO error loading from {}: {}", url, e.getMessage());
                }
            }
            logger.debug("loadIntoLocale({}) loaded {} total properties from {} resources", locale, count, resourceName);
        } catch (IOException e) {
            logger.warn("Failed to find resources for {}: {}", basename, e.getMessage());
        }
    }

    /**
     * Load properties using UTF-8 encoding, handling backslash-u-XXXX escapes.
     */
    private static Properties loadPropertiesUtf8(InputStream is) throws IOException {
        Properties props = new Properties();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            // Trim and skip comments/blank lines
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            // Find the key=value separator
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx < 0) {
                eqIdx = trimmed.indexOf(':');
            }
            if (eqIdx < 0) {
                continue;
            }
            String key = trimmed.substring(0, eqIdx).trim();
            String value = trimmed.substring(eqIdx + 1);
            // Expand backslash-u-XXXX escapes to actual characters
            value = expandUnicodeEscapes(value);
            props.put(key, value);
        }
        reader.close();
        return props;
    }

    /**
     * Expand backslash-u-XXXX Unicode escape sequences to actual characters.
     * Only expands if the pattern is actually a valid Unicode escape.
     */
    private static String expandUnicodeEscapes(String s) {
        // Only process if contains backslash-u (escape sequence prefix)
        if (s.indexOf('\\') < 0) {
            return s;
        }
        Matcher m = UNICODE_ESCAPE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            try {
                int codePoint = Integer.parseInt(hex, 16);
                m.appendReplacement(sb, String.valueOf((char) codePoint));
            } catch (NumberFormatException e) {
                // Not a valid hex, keep as-is
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Get core's message if not found in IDE's messages.
     * Called by IDE's I18n class when primary MessageSource returns null.
     *
     * Fallback chain: locale-specific → ENGLISH (core's messages_en.properties) → defaultMessage.
     * Note: Locale.ROOT is NOT used as fallback because diatom-core does NOT provide a
     * base messages.properties (only messages_zh.properties and messages_en.properties),
     * so ROOT would only contain IDE's messages, not core's.
     */
    public static String getCoreMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        // Normalize SIMPLIFIED_CHINESE to CHINESE for message lookup
        // LocaleInterceptor uses SIMPLIFIED_CHINESE, but we only loaded CHINESE
        if (locale != null && locale.equals(Locale.SIMPLIFIED_CHINESE)) {
            locale = Locale.CHINESE;
        }

        // Try exact locale match first
        Properties props = localeMessages.get(locale);
        if (props == null || !props.containsKey(code)) {
            // Fall back to English (core's English messages from messages_en.properties)
            // diatom-core has messages_en.properties, NOT a base messages.properties
            props = localeMessages.get(Locale.ENGLISH);
        }
        if (props != null && props.containsKey(code)) {
            return formatMessage(props.getProperty(code), args);
        }
        return defaultMessage;
    }

    private static String formatMessage(String template, Object[] args) {
        if (template == null) return null;
        if (args == null || args.length == 0) {
            // 没有参数时仍然处理转义字符
            return template.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
        }
        String result = template;
        // 处理 {0}, {1}, {2}... 风格的占位符 (MessageFormat 风格)
        for (int i = 0; i < args.length; i++) {
            String placeholder = "{" + i + "}";
            int idx = result.indexOf(placeholder);
            if (idx >= 0) {
                result = result.substring(0, idx) + (args[i] != null ? args[i].toString() : "") + result.substring(idx + placeholder.length());
            }
        }
        // 处理 {n,number}, {n,date} 等复杂格式 - 简单替换为原始值
        // 处理转义字符: \n -> 实际换行, \t -> Tab, \r -> 回车
        result = result.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
        return result;
    }
}
