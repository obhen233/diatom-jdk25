package com.github.obhen233.core.gateway.registry;

import com.github.obhen233.core.gateway.topology.TopologyConfigProvider;
import com.github.obhen233.util.InstallPaths;
import com.github.obhen233.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 基于文件系统的 Worker 注册表实现
 * 路径: {installHome}/gateway/registry/{workerId}.json
 */
public class FileSystemWorkerRegistry implements WorkerRegistry {
    private static final Logger logger = LoggerFactory.getLogger(FileSystemWorkerRegistry.class);
    private static final long SUSPECT_TIMEOUT_MS = 30_000;
    private static final long OFFLINE_TIMEOUT_MS = 60_000;
    private static final long HEARTBEAT_SCAN_INTERVAL_MS = 10_000;

    private final Path registryDir;
    private final Map<String, WorkerInfo> workers = new ConcurrentHashMap<>();
    private final List<Consumer<RegistryEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "registry-heartbeat-scanner");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    public FileSystemWorkerRegistry() {
        this.registryDir = InstallPaths.getGatewayRegistryDir();
        try {
            Files.createDirectories(registryDir);
            loadExistingRegistrations();
            startHeartbeatScanner();
            startWatchService();
        } catch (IOException e) {
            logger.warn("Failed to initialize filesystem registry: {}", e.getMessage());
        }
    }

    @Override
    public void register(WorkerInfo worker) {
        // Apply topology-defined capabilities (if active topology exists)
        TopologyConfigProvider configProvider = TopologyConfigProvider.getInstance();
        if (configProvider != null) {
            configProvider.applyCapabilities(worker);
        }

        workers.put(worker.getWorkerId(), worker);
        writeRegistrationFile(worker);
        notifyListeners(new RegistryEvent(worker.getWorkerId(), RegistryEvent.EventType.REGISTERED, worker));
        logger.info("Worker registered: {} at {}:{}", worker.getWorkerId(), worker.getHost(), worker.getPort());
    }

    @Override
    public void deregister(String workerId) {
        WorkerInfo removed = workers.remove(workerId);
        deleteRegistrationFile(workerId);
        if (removed != null) {
            notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.DEREGISTERED, removed));
            logger.info("Worker deregistered: {}", workerId);
        }
    }

    @Override
    public void heartbeat(String workerId, WorkerMetrics metrics) {
        WorkerInfo worker = workers.get(workerId);
        if (worker != null) {
            worker.setMetrics(metrics);
            worker.getMetrics().updateHeartbeat();
            if (worker.getStatus() != WorkerInfo.WorkerStatus.ONLINE) {
                worker.setStatus(WorkerInfo.WorkerStatus.ONLINE);
                notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.STATUS_CHANGED, worker));
            }
            writeRegistrationFile(worker);
        }
    }

    @Override
    public List<WorkerInfo> availableWorkers() {
        return workers.values().stream()
                .filter(WorkerInfo::isAvailable)
                .collect(Collectors.toList());
    }

    @Override
    public WorkerInfo getWorker(String workerId) {
        return workers.get(workerId);
    }

    @Override
    public void markShuttingDown(String workerId) {
        WorkerInfo worker = workers.get(workerId);
        if (worker != null) {
            worker.setStatus(WorkerInfo.WorkerStatus.SHUTTING_DOWN);
            writeRegistrationFile(worker);
            notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.STATUS_CHANGED, worker));
        }
    }

    @Override
    public void subscribe(Consumer<RegistryEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void shutdown() {
        running = false;
        scheduler.shutdown();
    }

    private void writeRegistrationFile(WorkerInfo worker) {
        try {
            Path file = registryDir.resolve(worker.getWorkerId() + ".json");
            String json = toJson(worker);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.warn("Failed to write registration file for {}: {}", worker.getWorkerId(), e.getMessage());
        }
    }

    private void deleteRegistrationFile(String workerId) {
        try {
            Path file = registryDir.resolve(workerId + ".json");
            Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.warn("Failed to delete registration file for {}: {}", workerId, e.getMessage());
        }
    }

    private void loadExistingRegistrations() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(registryDir, "*.json")) {
            for (Path entry : stream) {
                try {
                    String content = new String(Files.readAllBytes(entry), StandardCharsets.UTF_8);
                    WorkerInfo worker = fromJson(content);
                    if (worker != null) {
                        workers.put(worker.getWorkerId(), worker);
                        logger.debug("Loaded existing registration: {}", worker.getWorkerId());
                    }
                } catch (Exception e) {
                    logger.debug("Skipping invalid registration file: {}", entry.getFileName());
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load existing registrations: {}", e.getMessage());
        }
    }

    private void startHeartbeatScanner() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            long now = System.currentTimeMillis();
            for (WorkerInfo worker : workers.values()) {
                long age = now - worker.getMetrics().getLastHeartbeat();
                if (age > OFFLINE_TIMEOUT_MS && worker.getStatus() == WorkerInfo.WorkerStatus.ONLINE) {
                    worker.setStatus(WorkerInfo.WorkerStatus.OFFLINE);
                    notifyListeners(new RegistryEvent(worker.getWorkerId(), RegistryEvent.EventType.HEARTBEAT_TIMEOUT, worker));
                    logger.warn("Worker heartbeat timeout (offline): {} ({}ms since last heartbeat)", worker.getWorkerId(), age);
                } else if (age > SUSPECT_TIMEOUT_MS && worker.getStatus() == WorkerInfo.WorkerStatus.ONLINE) {
                    worker.setStatus(WorkerInfo.WorkerStatus.SUSPECT);
                    notifyListeners(new RegistryEvent(worker.getWorkerId(), RegistryEvent.EventType.STATUS_CHANGED, worker));
                    logger.warn("Worker heartbeat timeout (suspect): {} ({}ms since last heartbeat)", worker.getWorkerId(), age);
                }
            }
        }, HEARTBEAT_SCAN_INTERVAL_MS, HEARTBEAT_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void startWatchService() {
        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            registryDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            Thread watcher = new Thread(() -> {
                while (running) {
                    try {
                        WatchKey key = watchService.poll(5, TimeUnit.SECONDS);
                        if (key != null) {
                            for (WatchEvent<?> event : key.pollEvents()) {
                                handleWatchEvent(event);
                            }
                            key.reset();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "registry-watch-service");
            watcher.setDaemon(true);
            watcher.start();
        } catch (IOException e) {
            logger.warn("Failed to start WatchService for registry: {}", e.getMessage());
        }
    }

    private void handleWatchEvent(WatchEvent<?> event) {
        Path fileName = (Path) event.context();
        String name = fileName.toString();
        if (!name.endsWith(".json")) return;
        String workerId = name.substring(0, name.length() - ".json".length());

        if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
            WorkerInfo removed = workers.remove(workerId);
            if (removed != null) {
                notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.DEREGISTERED, removed));
            }
        } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                || event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
            // Load or update worker from the file
            loadWorkerFromFile(workerId, fileName);
        }
    }

    /**
     * Load a single worker registration file into the workers map.
     */
    private void loadWorkerFromFile(String workerId, Path fileName) {
        try {
            Path file = registryDir.resolve(fileName);
            if (!Files.exists(file)) return;
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            WorkerInfo worker = fromJson(content);
            if (worker != null) {
                boolean isNew = !workers.containsKey(workerId);
                workers.put(workerId, worker);
                if (isNew) {
                    notifyListeners(new RegistryEvent(workerId, RegistryEvent.EventType.REGISTERED, worker));
                    logger.info("Worker registered via WatchService: {} at {}:{}", workerId, worker.getHost(), worker.getPort());
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to load worker from WatchService event: {} - {}", fileName, e.getMessage());
        }
    }

    private void notifyListeners(RegistryEvent event) {
        for (Consumer<RegistryEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Registry listener error: {}", e.getMessage());
            }
        }
    }

    private String toJson(WorkerInfo w) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("workerId", w.getWorkerId());
        map.put("host", w.getHost());
        map.put("port", w.getPort());
        map.put("model", w.getModel());
        String ws = w.getWorkspace();
        if (ws != null && !ws.isEmpty()) {
            map.put("workspace", ws);
        }
        map.put("tier", w.getTier());
        map.put("capabilities", w.getCapabilities());
        map.put("traits", w.getTraits());
        map.put("costPer1kTokens", w.getCostPer1kTokens());
        map.put("maxConcurrency", w.getMaxConcurrency());
        String authToken = w.getAuthToken();
        if (authToken != null && !authToken.isEmpty()) {
            map.put("authToken", authToken);
        }
        map.put("status", w.getStatus().name());
        map.put("lastHeartbeat", w.getMetrics().getLastHeartbeat());
        map.put("currentLoad", w.getMetrics().getCurrentLoad());
        String group = w.getGroup();
        if (group != null && !group.isEmpty()) {
            map.put("group", group);
        }
        map.put("pid", w.getPid());
        map.put("useSsl", w.isUseSsl());
        map.put("registeredAt", w.getRegisteredAt());
        String gwId = w.getGatewayId();
        if (gwId != null && !gwId.isEmpty()) {
            map.put("gatewayId", gwId);
        }
        return JsonUtils.toJson(map);
    }

    private WorkerInfo fromJson(String json) {
        try {
            WorkerInfo w = new WorkerInfo();
            w.setWorkerId(extractJsonString(json, "workerId"));
            w.setHost(extractJsonString(json, "host"));
            w.setPort(extractJsonInt(json, "port"));
            w.setModel(extractJsonString(json, "model"));
            w.setWorkspace(extractJsonString(json, "workspace"));
            w.setTier(extractJsonString(json, "tier"));
            w.setPid(extractJsonLong(json, "pid"));
            w.setRegisteredAt(extractJsonLong(json, "registeredAt"));
            w.setGroup(extractJsonString(json, "group"));
            w.setUseSsl(extractJsonBoolean(json, "useSsl"));
            w.setAuthToken(extractJsonString(json, "authToken"));
            w.setCostPer1kTokens(extractJsonDouble(json, "costPer1kTokens"));
            w.setMaxConcurrency(extractJsonInt(json, "maxConcurrency"));
            // Parse capabilities map
            String capsJson = extractJsonObject(json, "capabilities");
            if (capsJson != null && !capsJson.isEmpty() && !"null".equals(capsJson) && !"{}".equals(capsJson)) {
                w.setCapabilities(parseCapabilitiesMap(capsJson));
            }
            // Parse traits array
            String traitsJson = extractJsonArray(json, "traits");
            if (traitsJson != null && !traitsJson.isEmpty() && !"[]".equals(traitsJson)) {
                w.setTraits(parseStringArray(traitsJson));
            }
            String status = extractJsonString(json, "status");
            if (status != null) {
                w.setStatus(WorkerInfo.WorkerStatus.valueOf(status));
            }
            w.getMetrics().setLastHeartbeat(extractJsonLong(json, "lastHeartbeat"));
            w.getMetrics().setCurrentLoad(extractJsonDouble(json, "currentLoad"));
            w.setGatewayId(extractJsonString(json, "gatewayId"));
            return w;
        } catch (Exception e) {
            logger.debug("Failed to parse worker JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract a JSON object value for a given key (everything between { and matching }).
     */
    private static String extractJsonObject(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":{";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": {";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start = json.indexOf('{', start);
        if (start < 0) return null;
        int depth = 0;
        int end = start;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break; } }
        }
        return end > start ? json.substring(start, end) : null;
    }

    /**
     * Extract a JSON array value for a given key (everything between [ and matching ]).
     */
    private static String extractJsonArray(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\":[";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": [";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start = json.indexOf('[', start);
        if (start < 0) return null;
        int depth = 0;
        int end = start;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) { end = i + 1; break; } }
        }
        return end > start ? json.substring(start, end) : null;
    }

    /**
     * Parse a capabilities JSON object like {"cap1":1.0,"cap2":0.5} into a Map.
     */
    private static Map<String, Double> parseCapabilitiesMap(String json) {
        Map<String, Double> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;
        // Remove surrounding braces
        String inner = json.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
        inner = inner.trim();
        if (inner.isEmpty()) return result;

        // Parse key-value pairs: "key":value
        int i = 0;
        while (i < inner.length()) {
            // Find opening quote of key
            int keyStart = inner.indexOf('\"', i);
            if (keyStart < 0) break;
            int keyEnd = inner.indexOf('\"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = inner.substring(keyStart + 1, keyEnd);
            // Find colon after key
            int colon = inner.indexOf(':', keyEnd + 1);
            if (colon < 0) break;
            // Find value end (next comma or end of string)
            int valEnd = inner.indexOf(',', colon + 1);
            if (valEnd < 0) valEnd = inner.length();
            String valStr = inner.substring(colon + 1, valEnd).trim();
            try {
                result.put(key, Double.parseDouble(valStr));
            } catch (NumberFormatException ignored) {
            }
            i = valEnd + 1;
        }
        return result;
    }

    /**
     * Parse a JSON string array like ["item1","item2"] into a List.
     */
    private static java.util.List<String> parseStringArray(String json) {
        java.util.List<String> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        String inner = json.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        inner = inner.trim();
        if (inner.isEmpty()) return result;

        int i = 0;
        while (i < inner.length()) {
            int start = inner.indexOf('\"', i);
            if (start < 0) break;
            int end = inner.indexOf('\"', start + 1);
            if (end < 0) break;
            result.add(inner.substring(start + 1, end));
            i = end + 1;
        }
        return result;
    }

    private String extractJsonString(String json, String key) {
        // Try spaced format "key": "value"
        String search = "\"" + key + "\": \"";
        int start = json.indexOf(search);
        if (start < 0) {
            // Try compact format "key":"value"
            search = "\"" + key + "\":\"";
            start = json.indexOf(search);
            if (start < 0) return null;
        }
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end <= start) return null;
        String val = json.substring(start, end);
        // Limit length to prevent OOM from corrupted data (e.g. backslash accumulation)
        if (val.length() > 500) {
            return null;
        }
        // Unescape JSON: \\ → \
        val = val.replace("\\\\", "\\");
        return val;
    }

    /**
     * Safely extract a numeric substring: limits length to 50 chars to avoid OOM on malformed JSON.
     */
    private static String safeNumericSubstring(String json, int start, int end) {
        if (start < 0 || start >= json.length()) return "0";
        if (end < 0 || end > json.length()) end = json.length();
        int maxLen = Math.min(end - start, 50);
        if (maxLen <= 0) return "0";
        return json.substring(start, start + maxLen).trim();
    }

    private static int findJsonKeyIndex(String json, String key) {
        // Try spaced format "key": value
        String search = "\"" + key + "\": ";
        int idx = json.indexOf(search);
        if (idx >= 0) return idx + search.length();
        // Try compact format "key":value (ensure not inside a longer key name)
        search = "\"" + key + "\":";
        idx = json.indexOf(search);
        if (idx >= 0 && (idx == 0 || json.charAt(idx - 1) != '\"')) return idx + search.length();
        return -1;
    }

    private int extractJsonInt(String json, String key) {
        int start = findJsonKeyIndex(json, key);
        if (start < 0) return 0;
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        try {
            return Integer.parseInt(safeNumericSubstring(json, start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long extractJsonLong(String json, String key) {
        int start = findJsonKeyIndex(json, key);
        if (start < 0) return 0;
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        try {
            return Long.parseLong(safeNumericSubstring(json, start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double extractJsonDouble(String json, String key) {
        int start = findJsonKeyIndex(json, key);
        if (start < 0) return 0;
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        try {
            return Double.parseDouble(safeNumericSubstring(json, start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean extractJsonBoolean(String json, String key) {
        int start = findJsonKeyIndex(json, key);
        if (start < 0) return false;
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        if (end > start) {
            return "true".equalsIgnoreCase(json.substring(start, end).trim());
        }
        return false;
    }

}
