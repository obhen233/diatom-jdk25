package com.github.obhen233.compiler.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * OpenAPI / Swagger configuration.
 *
 * Swagger UI available at: /swagger-ui.html
 * OpenAPI JSON at: /v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Diatom IDE API")
                        .description("""
                                Online Java IDE - Backend API Documentation

                                ## Overview / 概述
                                This API provides all IDE functionality including:
                                - Project management (create, compile, run, debug)
                                - File editing and navigation
                                - Terminal execution (shell commands)
                                - AI assistant with streaming responses
                                - Version control (Git/SVN)

                                ## Authentication / 认证
                                All endpoints require authentication via `X-Auth-Token` header or `_token` query parameter.

                                ## WebSocket / WebSocket终端
                                Terminal and AI chat also support WebSocket connection at `/terminal-ws`.
                                See WebSocket protocol documentation below.

                                ## SSE / 服务端推送
                                AI chat supports SSE (Server-Sent Events) at `/workspace/ai/chat`.

                                ---

                                ## WebSocket Protocol / WebSocket协议

                                **Endpoint:** `/terminal-ws`
                                **Authentication:** `X-Auth-Token` query parameter

                                ---

                                ### Client -> Server Messages / 客户端→服务端消息

                                | Type | Description / 描述 | Parameters / 参数 |
                                |------|----------------------|-------------------|
                                | `exec` | Execute terminal command / 执行终端命令 | `command`, `projectName`, `cwd` |
                                | `ai` | Send AI chat request / 发送AI聊天请求 | `prompt`, `projectName`, `sessionId` |
                                | `confirm` | AI tool confirmation / AI工具确认 | `sessionId`, `decision` (y/a/n) |
                                | `reset` | Reset AI session / 重置AI会话 | `sessionId` |
                                | `cancel` | Cancel AI task / 取消AI任务 | `sessionId` |
                                | `query_active_ai` | Query active AI task / 查询活跃AI任务 | (none) |
                                | `deploy_detect` | Check deploy.yaml exists / 检查deploy.yaml是否存在 | `projectName` |
                                | `deploy` | Execute deploy pipeline / 执行部署流水线 | `projectName` |

                                ---

                                ### Server -> Client Messages / 服务端→客户端消息

                                | Type | Description / 描述 | Parameters / 参数 |
                                |------|----------------------|-------------------|
                                | `stdout` | Terminal output / 终端输出 | `data` |
                                | `exit` | Terminal command exit / 终端命令退出 | `code`, `cwd` |
                                | `error` | Error message / 错误消息 | `message` |
                                | `think` | AI streaming token / AI流式令牌 | `text`, `sessionId` |
                                | `progress` | AI tool execution progress / AI工具执行进度 | `tool`, `target`, `status`, `sessionId` |
                                | `confirm` | AI tool confirmation request / AI工具确认请求 | `action`, `tool`, `sessionId`, etc. |
                                | `done` | Response complete / 响应完成 | `content`, `sessionId` |
                                | `cancelled` | AI task cancelled / AI任务已取消 | `sessionId` |
                                | `active_ai` | Active AI task info / 活跃AI任务信息 | `hasActive`, `sessionId`, `projectName` |
                                | `deploy_detect_result` | Deploy yaml check result / 部署配置检查结果 | `hasDeploy`, `projectName` |
                                | `scp_progress` | SCP file upload progress / SCP 文件上传进度 | `stepName`, `current`, `total`, `speedBps` |

                                ---

                                ## SSE Protocol (AI Chat) / SSE协议 (AI聊天)

                                **Endpoint:** `POST /workspace/ai/chat`
                                **Headers:**
                                - `Content-Type: application/json`
                                - `X-Auth-Token: <token>`

                                **Request Body:**
                                ```json
                                {
                                  "prompt": "your question",
                                  "projectName": "myproject",
                                  "sessionId": "optional-session-id"
                                }
                                ```

                                **Response:** `text/event-stream`

                                | Event | Description / 描述 |
                                |-------|----------------------|
                                | `think` | Streaming AI response tokens / 流式AI响应令牌 |
                                | `progress` | Model generating/completed status / 模型生成中/完成状态 |
                                | `confirm` | Tool execution confirmation / 工具执行确认 |
                                | `done` | Response complete / 响应完成 |
                                | `error` | Error occurred / 发生错误 |
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Diatom IDE")
                                .url("https://github.com/obhen233/diatom-ide"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(Collections.singletonList(
                        new Server().url("/").description("Current server / 当前服务器")
                ))
                .tags(Arrays.asList(
                        new Tag().name("WebSocket Protocol").description("Terminal and AI chat via WebSocket / 通过WebSocket的终端和AI聊天"),
                        new Tag().name("SSE Protocol").description("Server-Sent Events for AI streaming / AI流式服务端推送事件")
                ));
    }
}
