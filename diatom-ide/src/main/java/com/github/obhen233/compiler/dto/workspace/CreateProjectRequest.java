package com.github.obhen233.compiler.dto.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Create project request / 创建项目请求
 */
@Schema(description = "Create project request / 创建项目请求")
public record CreateProjectRequest(
    @Schema(description = "Project name / 项目名称", example = "myproject", required = true) String name,
    @Schema(description = "JDK version / JDK版本", example = "8") Integer jdkVersion,
    @Schema(description = "Build tool: maven, gradle, none / 构建工具", example = "maven") String buildTool,
    @Schema(description = "Dependencies / 依赖 (Maven dependencies or Gradle dependencies)", example = "[\"junit:junit:4.12\"]") List<String> dependencies,
    @Schema(description = "Additional options / 附加选项") Map<String, Object> options
) {}
