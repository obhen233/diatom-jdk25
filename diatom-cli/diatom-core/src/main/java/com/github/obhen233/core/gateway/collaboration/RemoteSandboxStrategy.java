package com.github.obhen233.core.gateway.collaboration;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.sync.FileDiffResult;
import com.github.obhen233.core.gateway.sync.ProjectSyncService;
import com.github.obhen233.spi.IsolationContext;
import com.github.obhen233.spi.ResourceContentionProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程沙箱隔离策略。
 * <p>
 * 为跨机器 Worker 创建远程沙箱环境。
 * Gateway 将项目文件打包为 zip，推送到远程 Worker，
 * Worker 在本地解压到沙箱目录执行，执行完成后只返回 fileDiffs。
 * <p>
 * 支持增量推送：首次全量，后续只推变更的文件。
 * 沙箱路径按 workerId 持久化（不随任务结束删除），跨轮复用。
 */
public class RemoteSandboxStrategy implements ResourceContentionProvider {

    private static final Logger logger = LoggerFactory.getLogger(RemoteSandboxStrategy.class);

    private final ProjectSyncService projectSyncService;
    private final Path sandboxBaseDir;

    /**
     * 远程 Worker 的 host 列表（用于判断是否跨机器）。
     */
    private static final Set<String> LOCAL_HOSTS = new HashSet<>(Arrays.asList(
            "localhost", "127.0.0.1", "::1", "0.0.0.0"
    ));

    /**
     * workerId → 上次推送时的 MD5 快照。
     * 用于判断哪些文件发生了变更，实现增量推送。
     */
    private final ConcurrentHashMap<String, Map<String, String>> lastPushedSnapshots = new ConcurrentHashMap<>();

    public RemoteSandboxStrategy(ProjectSyncService projectSyncService, Path sandboxBaseDir) {
        this.projectSyncService = projectSyncService;
        this.sandboxBaseDir = sandboxBaseDir;
    }

    @Override
    public IsolationContext prepareEnvironment(String taskId,
                                                List<WorkerCoordinator.SubTask> subTasks,
                                                List<WorkerInfo> assignedWorkers,
                                                Path projectRoot) {
        IsolationContext context = new IsolationContext("remote-sandbox");

        // 检测是否有远程 Worker
        boolean hasRemoteWorker = false;
        for (WorkerInfo worker : assignedWorkers) {
            if (!isLocalHost(worker.getHost())) {
                hasRemoteWorker = true;
                break;
            }
        }

        if (!hasRemoteWorker) {
            logger.info("All workers are local, skipping remote sandbox setup for task {}", taskId);
            context.setAttribute("allLocal", true);
            return context;
        }

        try {
            // 为每个 worker 准备远程沙箱
            for (int i = 0; i < subTasks.size(); i++) {
                WorkerCoordinator.SubTask subTask = subTasks.get(i);
                WorkerInfo worker = assignedWorkers.get(i);

                String remoteWorkspacePath = worker.getWorkspace();
                if (remoteWorkspacePath == null || remoteWorkspacePath.isEmpty()) {
                    remoteWorkspacePath = System.getProperty("java.io.tmpdir");
                }

                // 沙箱路径按 workerId 持久化（不再包含 taskId）
                String sandboxDir = remoteWorkspacePath.replace('\\', '/')
                        + "/.diatom/sandbox/" + worker.getWorkerId();
                String sandboxPath = sandboxDir.replace('/', java.io.File.separatorChar);

                try {
                    // 检查是否有上次推送的快照，决定全量/增量推送
                    Map<String, String> lastSnapshot = lastPushedSnapshots.get(worker.getWorkerId());
                    if (lastSnapshot == null) {
                        // 首次推送：全量
                        doFullPush(worker, sandboxPath, taskId, subTask.getSubTaskId(), projectRoot);
                        logger.info("Full push completed for worker {} ({} bytes)",
                                worker.getWorkerId(), projectRoot);
                    } else {
                        // 后续推送：尝试增量
                        List<FileDiffResult> diffs = projectSyncService.collectDiffs(projectRoot, lastSnapshot);
                        if (diffs.isEmpty()) {
                            logger.info("No changes detected for worker {}, skipping push", worker.getWorkerId());
                        } else {
                            doIncrementalPush(worker, sandboxPath, taskId, subTask.getSubTaskId(),
                                    projectRoot, diffs);
                            logger.info("Incremental push completed for worker {}: {} changes",
                                    worker.getWorkerId(), diffs.size());
                        }
                    }

                    // 推送成功后更新快照
                    Map<String, String> newSnapshot = projectSyncService.snapshotProject(projectRoot);
                    lastPushedSnapshots.put(worker.getWorkerId(), newSnapshot);

                    context.addSandboxPath(subTask.getSubTaskId(), Paths.get(sandboxPath));
                    logger.info("Remote sandbox prepared for sub-task {} at {} on worker {}",
                            subTask.getSubTaskId(), sandboxPath, worker.getWorkerId());
                } catch (Exception e) {
                    logger.warn("Push failed for worker {}, falling back to pull mode: {}",
                            worker.getWorkerId(), e.getMessage());
                    fallbackToPullMode(context, subTask, worker, taskId, sandboxPath);
                }
            }

            context.setAttribute("assignedWorkers", assignedWorkers);
        } catch (Exception e) {
            logger.error("Failed to prepare remote sandbox environment: {}", e.getMessage());
        }

        return context;
    }

    /**
     * 全量推送：打包整个项目并发送。
     */
    private void doFullPush(WorkerInfo worker, String sandboxPath, String taskId,
                             String subTaskId, Path projectRoot) throws IOException {
        byte[] projectZip = projectSyncService.packProject(projectRoot);
        logger.info("Packed project for full push: {} bytes from {}", projectZip.length, projectRoot);
        pushProjectToWorker(worker, sandboxPath, taskId, subTaskId, projectZip, "full");
    }

    /**
     * 增量推送：只打包变更的文件 + manifest。
     */
    private void doIncrementalPush(WorkerInfo worker, String sandboxPath, String taskId,
                                    String subTaskId, Path projectRoot,
                                    List<FileDiffResult> diffs) throws IOException {
        byte[] incrementalZip = projectSyncService.packChangedFiles(projectRoot, diffs);
        logger.info("Packed changed files for incremental push: {} bytes, {} diffs",
                incrementalZip.length, diffs.size());
        pushProjectToWorker(worker, sandboxPath, taskId, subTaskId, incrementalZip, "incremental");
    }

    /**
     * Push 失败时降级为 Pull 模式：创建沙箱路径但由 Worker 主动拉取项目 zip。
     */
    private void fallbackToPullMode(IsolationContext context, WorkerCoordinator.SubTask subTask,
                                     WorkerInfo worker, String taskId, String sandboxPath) {
        context.addSandboxPath(subTask.getSubTaskId(), Paths.get(sandboxPath));
        context.setAttribute("pullMode_" + subTask.getSubTaskId(), true);
        logger.info("Fallback to pull mode for sub-task {} on worker {}", subTask.getSubTaskId(), worker.getWorkerId());
    }

    /**
     * 将项目 zip（全量或增量）推送到 Worker 的 sandbox/setup 端点。
     *
     * @param pushType "full" 或 "incremental"
     */
    private void pushProjectToWorker(WorkerInfo worker, String sandboxPath,
                                      String taskId, String subTaskId,
                                      byte[] projectZip, String pushType) throws IOException {
        String targetUrl = worker.getBaseUrl() + "/worker/v1/sandbox/setup";
        URL url = new URL(targetUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("X-Task-Id", taskId);
        conn.setRequestProperty("X-Sub-Task-Id", subTaskId);
        conn.setRequestProperty("X-Workspace-Path", sandboxPath);
        conn.setRequestProperty("X-Push-Type", pushType);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(projectZip);
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String errorBody;
            try (InputStream is = conn.getErrorStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                errorBody = baos.toString("UTF-8");
            }
            conn.disconnect();
            throw new IOException("Worker returned HTTP " + code + ": " + errorBody);
        }
        conn.disconnect();
    }

    @Override
    public Map<String, List<FileDiff>> collectChanges(String taskId,
                                                        List<WorkerCoordinator.SubTask> subTasks,
                                                        IsolationContext context) {
        // 远程沙箱的 diff 已通过 chat response 带回，此处不需要收集
        return Collections.emptyMap();
    }

    @Override
    public void cleanup(String taskId, List<WorkerCoordinator.SubTask> subTasks, IsolationContext context) {
        // 持久沙箱：不随任务结束删除，由外部显式调用清理
        // 这样多轮协作时可以复用沙箱，避免重复全量推送
        Boolean allLocal = context.getAttribute("allLocal");
        if (Boolean.TRUE.equals(allLocal)) {
            return;
        }
        logger.info("Persistent sandbox strategy: skipping cleanup for task {} (sandboxes preserved for reuse)",
                taskId);
    }

    @Override
    public String resolveWorkerWorkspacePath(WorkerCoordinator.SubTask subTask, IsolationContext context) {
        Path sandboxPath = context.getSandboxPath(subTask.getSubTaskId());
        return sandboxPath != null ? sandboxPath.toString() : null;
    }

    /**
     * 更新指定 worker 的快照（在 Gateway 本地应用了 Worker 返回的 diffs 后调用）。
     * 确保下一轮增量推送基于最新状态计算。
     *
     * @param workerId    worker 标识
     * @param projectRoot 项目根目录
     */
    public void updateSnapshot(String workerId, Path projectRoot) {
        try {
            Map<String, String> newSnapshot = projectSyncService.snapshotProject(projectRoot);
            lastPushedSnapshots.put(workerId, newSnapshot);
            logger.info("Updated snapshot for worker {} ({} files)", workerId, newSnapshot.size());
        } catch (IOException e) {
            logger.warn("Failed to update snapshot for worker {}: {}", workerId, e.getMessage());
        }
    }

    /**
     * 清除指定 worker 的快照（下次推送将全量）。
     */
    public void clearSnapshot(String workerId) {
        lastPushedSnapshots.remove(workerId);
        logger.info("Cleared snapshot for worker {}", workerId);
    }

    /**
     * 判断 host 是否为本地地址。
     */
    public static boolean isLocalHost(String host) {
        if (host == null) return true;
        return LOCAL_HOSTS.contains(host.trim().toLowerCase());
    }
}
