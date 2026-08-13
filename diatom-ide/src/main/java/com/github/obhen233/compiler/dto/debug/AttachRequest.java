package com.github.obhen233.compiler.dto.debug;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Attach to running JVM process request / 附加到运行中的JVM进程请求
 */
@Schema(description = "Attach to running JVM process request / 附加到运行中的JVM进程请求")
public record AttachRequest(
    @Schema(description = "Debug port / 调试端口", example = "5005", required = true) String port,
    @Schema(description = "Classpath / 类路径", example = "/path/to/project/target/classes") String classpath
) {}
