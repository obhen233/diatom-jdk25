package com.github.obhen233.compiler.i18n;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Java 端国际化工具。
 * 使用 Spring MessageSource 获取翻译文本。
 *
 * 用法:
 * <pre>
 * // 简单文本
 * String msg = I18n.get("terminal.cmdNotAllowed", "git");
 *
 * // 带参数
 * String msg = I18n.get("terminal.cdNotAllowed", "..");
 *
 * // 直接获取中文（忽略语言设置）
 * String msg = I18n.getZh("terminal.cdNotAllowed");
 * </pre>
 */
@Component
public class I18n {

    private static MessageSource messageSource;

    @Autowired
    public void setMessageSource(MessageSource messageSource) {
        I18n.messageSource = messageSource;
    }

    /**
     * 获取当前语言环境的翻译文本。
     * @param key 消息 key
     * @return 翻译后的文本，如果找不到则返回 key 本身
     */
    public static String get(String key) {
        return get(key, (Object[]) null);
    }

    /**
     * 获取当前语言环境的翻译文本（带参数）。
     * @param key 消息 key
     * @param params 参数
     * @return 翻译后的文本
     */
    public static String get(String key, Object... params) {
        if (messageSource == null) {
            return key;
        }
        Locale locale = LocaleContextHolder.getLocale();
        if (locale == null) {
            locale = Locale.ENGLISH;
        }
        String source = messageSource.getMessage(key, params, null, locale);
        if(source.equals(key)) {
            String core = com.github.obhen233.starter.CoreI18nAutoConfiguration.getCoreMessage(key, params, null, locale);
            return core != null ? core : key;
        }else{
            return source != null ? source : key;
        }

    }

    /**
     * 获取中文翻译（忽略当前语言设置）。
     * @param key 消息 key
     * @return 中文文本
     */
    public static String getZh(String key) {
        return getZh(key, (Object[]) null);
    }

    /**
     * 获取中文翻译（带参数）。
     * @param key 消息 key
     * @param params 参数
     * @return 中文文本
     */
    public static String getZh(String key, Object... params) {
        if (messageSource == null) {
            return key;
        }
        try {
            return messageSource.getMessage(key, params, null, Locale.CHINESE);
        } catch (Exception e) {
            return key;
        }
    }

    /**
     * 获取英文翻译（忽略当前语言设置）。
     * @param key 消息 key
     * @return 英文文本
     */
    public static String getEn(String key) {
        return getEn(key, (Object[]) null);
    }

    /**
     * 获取英文翻译（带参数）。
     * @param key 消息 key
     * @param params 参数
     * @return 英文文本
     */
    public static String getEn(String key, Object... params) {
        if (messageSource == null) {
            return key;
        }
        try {
            return messageSource.getMessage(key, params, null, Locale.ENGLISH);
        } catch (Exception e) {
            return key;
        }
    }

    /**
     * 获取当前语言设置。
     * @return 语言代码，如 "en", "zh"
     */
    public static String getCurrentLang() {
        Locale locale = LocaleContextHolder.getLocale();
        if (locale == null) {
            return "en";
        }
        String lang = locale.getLanguage();
        return "zh".equals(lang) ? "zh" : "en";
    }

    /**
     * 检查是否是中文环境。
     * @return true 如果当前语言是中文
     */
    public static boolean isChinese() {
        return "zh".equals(getCurrentLang());
    }

    /**
     * 解析模板字符串中的所有 {{i18nKey:param1:param2}} 占位符并替换为翻译文本。
     * 示例: "{{config.list.header:5}}" → "系统配置（共 5 项）"
     *       "{{config.empty}}" → "空"
     * @param template 包含占位符的模板字符串
     * @return 解析后的字符串，未找到的占位符保持原样
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
