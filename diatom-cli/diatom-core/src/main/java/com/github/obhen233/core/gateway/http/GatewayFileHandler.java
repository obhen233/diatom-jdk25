package com.github.obhen233.core.gateway.http;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.gateway.sync.FileDiffResult;
import com.github.obhen233.core.gateway.sync.ProjectSyncService;
import com.github.obhen233.core.config.ConfigManager;
import com.github.obhen233.core.spi.http.ServerRequest;
import com.github.obhen233.core.spi.http.ServerResponse;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.obhen233.core.gateway.http.GatewayHttpUtil.*;

/**
 * Handles file serving, project sync, workspace, and config sync endpoints.
 */
class GatewayFileHandler {

    private final GatewayHttpServer server;

    GatewayFileHandler(GatewayHttpServer server) {
        this.server = server;
    }

    void registerRoutes() {
        server.getServerSpi().addHandler("GET", "/gateway/v1/config/sync", this::handleConfigSync);
        LoggerFactory.getLogger(GatewayFileHandler.class).info("Config sync endpoint enabled");

        server.getServerSpi().addHandler("GET", "/gateway/v1/workspace", this::handleWorkspace);
        LoggerFactory.getLogger(GatewayFileHandler.class).info("Workspace endpoint enabled");

        server.getServerSpi().addHandler("POST", "/gateway/v1/file/list", this::handleFileList);
        server.getServerSpi().addHandler("POST", "/gateway/v1/file/batch", this::handleFileBatch);
        LoggerFactory.getLogger(GatewayFileHandler.class).info("File serving endpoints enabled at /gateway/v1/file/list and /gateway/v1/file/batch");

        server.getServerSpi().addHandler("POST", "/gateway/v1/project/push", this::handleProjectPush);
        LoggerFactory.getLogger(GatewayFileHandler.class).info("Project push endpoint enabled at /gateway/v1/project/push");

        server.getServerSpi().addHandler("POST", "/gateway/v1/sandbox/setup", this::handleSandboxSetup);
        LoggerFactory.getLogger(GatewayFileHandler.class).info("Sandbox setup endpoint (pull mode) enabled at /gateway/v1/sandbox/setup");
    }

    private ConfigManager getConfigManager() { return server.getConfigManager(); }
    private ProjectSyncService getProjectSyncService() { return server.getProjectSyncService(); }

    /**
     * GET /gateway/v1/config/sync
     */
    private void handleConfigSync(ServerRequest request, ServerResponse response) throws IOException {
        if (request instanceof JdkServerRequest) {
            if (!server.getAdmissionControl().authenticateRequest(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        Map<String, String> effectiveConfig = getConfigManager() != null
                ? getConfigManager().getAllEffective() : new HashMap<>();

        StringBuilder sb = new StringBuilder("{\"configs\":[");
        boolean first = true;
        for (Map.Entry<String, String> entry : effectiveConfig.entrySet()) {
            if (!first) sb.append(",");
            sb.append("{\"key\":\"").append(safe(entry.getKey()))
              .append("\",\"value\":\"").append(escapeJson(entry.getValue())).append("\"}");
            first = false;
        }
        sb.append("]}");
        sendJson(response, 200, sb.toString());
        LoggerFactory.getLogger(GatewayFileHandler.class).debug("Config sync returned {} config entries", effectiveConfig.size());
    }

    /**
     * GET /gateway/v1/workspace
     */
    private void handleWorkspace(ServerRequest request, ServerResponse response) throws IOException {
        if (request instanceof JdkServerRequest) {
            if (!server.getAdmissionControl().authenticateRequest(((JdkServerRequest) request).getExchange())) {
                return;
            }
        }

        String workspaceDir = resolveWorkspaceDir();
        String json = "{\"workspaceDir\":\"" + escapeJson(workspaceDir) + "\"}";
        sendJson(response, 200, json);
    }

    /**
     * POST /gateway/v1/file/list
     */
    private void handleFileList(ServerRequest request, ServerResponse response) throws IOException {
        String workspaceDir = resolveWorkspaceDir();
        Path projectRoot = Paths.get(workspaceDir);
        if (!Files.exists(projectRoot)) {
            sendError(response, 404, "Workspace directory not found");
            return;
        }

        String body = readBody(request);
        String pattern = extractJsonValue(body, "pattern");

        List<String> files = new ArrayList<>();
        Files.walk(projectRoot)
                .filter(Files::isRegularFile)
                .filter(p -> !isIgnoredPath(p, projectRoot))
                .forEach(p -> {
                    String relativePath = projectRoot.relativize(p).toString().replace('\\', '/');
                    if (pattern != null && !pattern.isEmpty()) {
                        if (!matchesGlob(relativePath, pattern)) return;
                    }
                    files.add(relativePath);
                });

        StringBuilder json = new StringBuilder();
        json.append("{\"files\":[");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escapeJson(files.get(i))).append("\"");
        }
        json.append("],\"total\":").append(files.size()).append("}");
        sendJson(response, 200, json.toString());
    }

    /**
     * POST /gateway/v1/file/batch
     */
    private void handleFileBatch(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        String filesJson = extractJsonValue(body, "files");
        String compressStr = extractJsonValue(body, "compress");
        boolean useCompression = "true".equalsIgnoreCase(compressStr);

        String workspaceDir = resolveWorkspaceDir();
        Path projectRoot = Paths.get(workspaceDir);

        List<String> requestedFiles = parseJsonStringArray(filesJson);

        StringBuilder contentJson = new StringBuilder();
        contentJson.append("{\"files\":{");
        boolean first = true;
        int foundCount = 0;
        int totalCount = requestedFiles.size();

        for (String filePath : requestedFiles) {
            if (filePath == null || filePath.isEmpty()) continue;
            Path targetFile = projectRoot.resolve(filePath.replace('/', java.io.File.separatorChar));
            if (Files.exists(targetFile) && Files.isRegularFile(targetFile)) {
                if (!first) contentJson.append(",");
                byte[] fileContent = Files.readAllBytes(targetFile);
                String base64Content = Base64.getEncoder().encodeToString(fileContent);
                contentJson.append("\"").append(escapeJson(filePath)).append("\":\"")
                        .append(base64Content).append("\"");
                first = false;
                foundCount++;
            }
        }
        contentJson.append("},\"found\":").append(foundCount);
        contentJson.append(",\"total\":").append(totalCount).append("}");

        String responseJson = contentJson.toString();

        if (useCompression) {
            byte[] compressed = gzipCompress(responseJson.getBytes(StandardCharsets.UTF_8));
            response.setHeader("Content-Type", "application/json");
            response.setHeader("Content-Encoding", "gzip");
            response.setStatus(200);
            OutputStream os = response.getOutputStream();
            os.write(compressed);
            os.close();
        } else {
            sendJson(response, 200, responseJson);
        }
    }

    /**
     * POST /gateway/v1/project/push
     */
    private void handleProjectPush(ServerRequest request, ServerResponse response) throws IOException {
        String body = readBody(request);
        String diffsSection = extractFullJsonValue(body, "fileDiffs");
        if (diffsSection != null && !"null".equals(diffsSection) && !"[]".equals(diffsSection)) {
            List<FileDiffResult> diffs = server.getAdmissionControl().parseFileDiffs(diffsSection);
            try {
                getProjectSyncService().applyDiffs(diffs, Paths.get(resolveWorkspaceDir()));
                LoggerFactory.getLogger(GatewayFileHandler.class).info("Applied {} file diffs from project push", diffs.size());
            } catch (IOException e) {
                LoggerFactory.getLogger(GatewayFileHandler.class).warn("Failed to apply file diffs: {}", e.getMessage());
            }
        }
        sendJson(response, 200, Collections.singletonMap("status", "ok"));
    }

    /**
     * POST /gateway/v1/sandbox/setup
     */
    private void handleSandboxSetup(ServerRequest request, ServerResponse response) throws IOException {
        String workspacePath = request.getHeader("X-Workspace-Path");
        if (workspacePath == null || workspacePath.isEmpty()) {
            sendError(response, 400, "Missing X-Workspace-Path header");
            return;
        }

        try {
            byte[] projectZip = getProjectSyncService().packProject(Paths.get(resolveWorkspaceDir()));

            response.setHeader("Content-Type", "application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"project.zip\"");
            response.setStatus(200);
            OutputStream os = response.getOutputStream();
            os.write(projectZip);
            os.close();

            LoggerFactory.getLogger(GatewayFileHandler.class).info("Sandbox setup (pull mode): sent {} bytes zip for workspace {}",
                    projectZip.length, workspacePath);
        } catch (Exception e) {
            LoggerFactory.getLogger(GatewayFileHandler.class).error("Failed to handle sandbox setup (pull mode): {}", e.getMessage());
            sendError(response, 500, "Sandbox setup failed: " + e.getMessage());
        }
    }
}
