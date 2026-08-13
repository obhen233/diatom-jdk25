package com.github.obhen233.compiler.i18n;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

/**
 * 国际化配置。
 * 使用 Accept-Language header 来确定语言。
 * 前端需要在请求头中设置 Accept-Language（如 "zh-CN" 或 "en"）。
 */
@Configuration
public class LocaleConfig {

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setSupportedLocales(java.util.Arrays.asList(
            Locale.ENGLISH,
            Locale.CHINESE,
            Locale.SIMPLIFIED_CHINESE
        ));
        return resolver;
    }
}
