package com.github.obhen233.compiler.dto.settings;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Update IDE settings request / 更新IDE设置请求
 */
@Schema(description = "Update IDE settings request / 更新IDE设置请求")
public record UpdateSettingsRequest(
    @Schema(description = "Theme / 主题", example = "dark", allowableValues = {"dark", "light"}) String theme,
    @Schema(description = "Language / 语言", example = "en", allowableValues = {"en", "zh"}) String language,
    @Schema(description = "JDK version / JDK版本", example = "8") Integer jdkVersion,
    @Schema(description = "Java home path / Java主目录路径", example = "C:\\Program Files\\Java\\jdk1.8.0") String javaHome,
    @Schema(description = "Maven home path / Maven主目录路径", example = "C:\\apache-maven") String mavenHome,
    @Schema(description = "Maven user settings file / Maven用户配置文件", example = "C:\\Users\\me\\.m2\\settings.xml") String mavenUserSettings,
    @Schema(description = "Maven local repository / Maven本地仓库", example = "C:\\Users\\me\\.m2\\repository") String mavenLocalRepository,
    @Schema(description = "Gradle user home / Gradle用户目录", example = "C:\\Users\\me\\.gradle") String gradleUserHome,
    @Schema(description = "Git executable path / Git可执行文件路径", example = "C:\\Program Files\\Git\\bin\\git.exe") String gitPath,
    @Schema(description = "SVN executable path / SVN可执行文件路径", example = "C:\\Program Files\\SlikSvn\\bin\\svn.exe") String svnPath,
    @Schema(description = "AI API URL / AI API地址", example = "https://api.openai.com/v1") String aiApiUrl,
    @Schema(description = "AI API token / AI API令牌", example = "sk-...") String aiApiToken,
    @Schema(description = "AI model name / AI模型名称", example = "gpt-4") String aiModel,
    @Schema(description = "AI enabled / AI是否启用", example = "true") Boolean aiEnabled
) {}
