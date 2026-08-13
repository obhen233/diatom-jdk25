package com.github.obhen233.compiler.config;

import com.github.obhen233.compiler.i18n.LocaleInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置。
 * 注册语言拦截器等。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private LocaleInterceptor localeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/assets/**",
                        "/webjars/**",
                        "/*.html",
                        "/*.js",
                        "/*.css",
                        "/*.ttf",
                        "/*.woff",
                        "/*.woff2",
                        "/*.png",
                        "/*.svg",
                        "/java-lsp/**"
                );
    }
}
