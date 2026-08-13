package com.github.obhen233.compiler.deploy;

import com.github.obhen233.compiler.deploy.DeployService.BufferedEvent;
import com.github.obhen233.compiler.dto.ApiResponse;
import com.github.obhen233.compiler.i18n.I18n;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin
@RestController
@RequestMapping("/workspace/deploy")
@Tag(name = "Deploy / 部署", description = "Deploy pipeline management with SSE reconnect / 部署管道管理，支持SSE重连")
public class DeployController {

    private static final Logger log = LoggerFactory.getLogger(DeployController.class);

    @Autowired
    private DeployService deployService;

    /**
     * Get all projects with running deploys (for on-mount status check after page refresh).
     */
    @GetMapping("/running")
    @Operation(summary = "Get running deploys / 获取运行中的部署", description = "Returns all projects with running deploys / 返回所有正在部署的项目")
    public ApiResponse<Map<String, Object>> getRunningDeploys() {
        List<Map<String, Object>> running = deployService.getRunningDeploys();
        Map<String, Object> result = new HashMap<>();
        result.put("running", running);
        return ApiResponse.ok(result);
    }

    /**
     * Get the running status for a specific project.
     */
    @GetMapping("/status")
    @Operation(summary = "Get deploy status / 获取部署状态", description = "Returns running state for a project / 返回项目的部署运行状态")
    public ApiResponse<Map<String, Object>> getStatus(@RequestParam String projectName) {
        boolean running = deployService.isRunning(projectName);
        Map<String, Object> result = new HashMap<>();
        result.put("running", running);
        result.put("projectName", projectName);
        return ApiResponse.ok(result);
    }

    /**
     * Reconnect to a running deploy session after browser close/refresh.
     * Creates a new SSE stream, replays buffered events, and swaps the callback to SSE.
     */
    @PostMapping("/reconnect")
    @Operation(summary = "Reconnect to deploy session / 重连部署会话", description = "Reconnect to a running deploy session after browser close/refresh. Replays buffered output then streams live events / 浏览器关闭/刷新后重连到运行中的部署会话。回放缓冲输出然后流式传输实时事件")
    public SseEmitter reconnectDeploy(@RequestParam String projectName) {
        SseEmitter emitter = new SseEmitter(600_000L);

        if (!deployService.isRunning(projectName)) {
            try {
                emitter.send(SseEmitter.event().name("error").data(I18n.get("deploy.notRunning")));
                emitter.send(SseEmitter.event().name("done").data("{\"exitCode\":-1}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        // Replay buffered events
        List<BufferedEvent> buffer = deployService.getBuffer(projectName);
        try {
            for (BufferedEvent be : buffer) {
                emitter.send(SseEmitter.event().name(be.event).data(be.data));
            }
        } catch (Exception e) {
            log.warn("Failed to replay buffer for project {}: {}", projectName, e.getMessage());
            try {
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        // Swap the callback to this new SSE emitter
        deployService.setCallback(projectName, new DeployService.DeployEventCallback() {
            @Override
            public void onEvent(String event, String data) {
                try {
                    emitter.send(SseEmitter.event().name(event).data(data));
                    if ("exit".equals(event)) {
                        try {
                            emitter.complete();
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ex) {
                    // Emitter may already be completed or client disconnected
                }
            }
        });

        // Keep the emitter alive while the deploy session is running
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (deployService.isRunning(projectName)) {
                    try {
                        emitter.send(SseEmitter.event().name("ping").data(""));
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception ignored) {
                        // emitter.send may fail if connection is closed
                        break;
                    }
                }
                try {
                    emitter.complete();
                } catch (Exception ignored) {}
            }
        }, "deploy-reconnect-" + projectName).start();

        return emitter;
    }

    /**
     * Stop a running deploy.
     */
    @PostMapping("/stop")
    @Operation(summary = "Stop deploy / 停止部署", description = "Stop a running deploy session / 停止正在运行的部署会话")
    public ApiResponse<String> stopDeploy(@RequestParam String projectName) {
        deployService.stopDeploy(projectName);
        return ApiResponse.ok(I18n.get("deploy.stopped"));
    }
}
