package com.github.obhen233.core.session;

import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.database.ChangeLogDao;
import com.github.obhen233.core.database.SnapshotDao;
import com.github.obhen233.util.I18n;
import com.github.obhen233.util.PathUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import com.github.obhen233.util.JsonUtils;

public class SessionTracker {
    private static final Logger logger = LoggerFactory.getLogger(SessionTracker.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");
    private static final ObjectMapper mapper = JsonUtils.getMapper();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String NEWLINE = System.lineSeparator();

    private final boolean changeLogEnabled;
    private final boolean auditLogEnabled;
    private final int snapshotInterval;

    private final Set<String> modifiedFiles = new HashSet<>();
    private final Set<String> createdFiles = new HashSet<>();
    private final Set<String> deletedFiles = new HashSet<>();
    private final Set<String> readFiles = new HashSet<>();
    private final List<AuditEntry> auditEntries = new ArrayList<>();

    // Store original content for modified files (path -> original content)
    private final Map<String, String> originalContents = new HashMap<>();
    // Store deleted content (path -> deleted content)
    private final Map<String, String> deletedContents = new HashMap<>();
    // Store new content for created files (path -> new content)
    private final Map<String, String> newContents = new HashMap<>();

    // Change history stack for incremental undo
    private final Stack<ChangeRecord> changeHistory = new Stack<>();

    // File change listener for real-time diff notification
    private transient volatile FileChangeListener changeListener;

    // ========== DAO Integration (Optional) ==========
    private ChangeLogDao changeLogDao;
    private SnapshotDao snapshotDao;
    private String currentTaskId;
    private int currentStepNumber;
    private int operationCount = 0;
    private int currentSnapshotId = -1;
    private String workspacePath;

    /**
     * Create SessionTracker with default settings from AppConfig.
     * - changeLogEnabled: false (default)
     * - auditLogEnabled: true (default)
     * - snapshotInterval: from config (default 5)
     */
    public SessionTracker() {
        AppConfig config = new AppConfig();
        this.changeLogEnabled = config.isChangeLogEnabled();
        this.auditLogEnabled = config.isAuditLogEnabled();
        this.snapshotInterval = config.getSnapshotInterval();
    }

    /**
     * Create SessionTracker with explicit settings.
     * @param changeLogEnabled whether to write change_*.log files
     * @param auditLogEnabled whether to write audit entries
     */
    public SessionTracker(boolean changeLogEnabled, boolean auditLogEnabled) {
        this.changeLogEnabled = changeLogEnabled;
        this.auditLogEnabled = auditLogEnabled;
        this.snapshotInterval = 5;  // Default
    }

    // ========== DAO Integration Methods ==========

    /**
     * Set the task context for DAO operations
     */
    public void setTaskContext(String taskId, int stepNumber, String workspacePath) {
        this.currentTaskId = taskId;
        this.currentStepNumber = stepNumber;
        this.workspacePath = workspacePath;
        this.operationCount = 0;
    }

    /**
     * Clear the task context
     */
    public void clearTaskContext() {
        this.currentTaskId = null;
        this.currentStepNumber = 0;
        this.workspacePath = null;
        this.operationCount = 0;
        this.currentSnapshotId = -1;
    }

    /**
     * Set DAO references for persistence
     */
    public void setDaos(ChangeLogDao changeLogDao, SnapshotDao snapshotDao) {
        this.changeLogDao = changeLogDao;
        this.snapshotDao = snapshotDao;
    }

    /**
     * Check if DAO is available
     */
    public boolean isDaoAvailable() {
        return changeLogDao != null && currentTaskId != null;
    }

    // ========== File Operation Recording ==========

    public void recordFileRead(String path) {
        if (path != null) {
            readFiles.add(path);
            if (auditLogEnabled) {
                addAuditEntry("READ", path, null, null);
            }
            // Write to change log if DAO available
            if (isDaoAvailable()) {
                writeChangeLog("READ", path, null, null, "File read");
            }
        }
    }

    public void recordFileModified(String path) {
        if (path != null) {
            modifiedFiles.add(path);
            if (auditLogEnabled) {
                addAuditEntry("MODIFY", path, null, null);
            }
            if (isDaoAvailable()) {
                writeChangeLog("MODIFY", path, null, null, "File modified");
            }
        }
    }

    public void recordFileCreated(String path) {
        if (path != null) {
            createdFiles.add(path);
            modifiedFiles.remove(path);
            if (auditLogEnabled) {
                addAuditEntry("CREATE", path, null, null);
            }
            if (isDaoAvailable()) {
                writeChangeLog("CREATE", path, null, null, "File created");
            }
        }
    }

    public void recordFileDeleted(String path) {
        if (path != null) {
            deletedFiles.add(path);
            modifiedFiles.remove(path);
            createdFiles.remove(path);
            if (auditLogEnabled) {
                addAuditEntry("DELETE", path, null, null);
            }
            if (isDaoAvailable()) {
                writeChangeLog("DELETE", path, null, null, "File deleted");
            }
        }
    }

    public void recordCommandApproved(String command) {
        if (command != null && auditLogEnabled) {
            addAuditEntry("CMD_APPROVE", command, null, null);
        }
    }

    public void recordOriginalContent(String path, String content) {
        if (path != null && content != null) {
            originalContents.put(path, content);
        }
    }

    public void recordDeletedContent(String path, String content) {
        if (path != null && content != null) {
            deletedContents.put(path, content);
        }
    }

    public void recordNewContent(String path, String content) {
        if (path != null && content != null) {
            newContents.put(path, content);
        }
    }

    public void recordChange(String operation, String path, String oldContent, String newContent) {
        FileCategory category = FileCategory.classifyPath(path);
        changeHistory.push(new ChangeRecord(operation, path, oldContent, newContent, category));
        if (auditLogEnabled) {
            auditLogger.info("[{}] {} {} [{}] | undo-able", LocalDateTime.now().format(formatter), operation, path, category);
        }

        // Persist to DAO if available
        if (isDaoAvailable()) {
            writeChangeLog(operation, path, oldContent, newContent, null);

            // Create file snapshot periodically
            if (++operationCount % snapshotInterval == 0) {
                flushToSnapshot();
            }
        }

        // Notify file change listener for real-time diff
        FileChangeListener listener = this.changeListener;
        if (listener != null) {
            try {
                listener.onFileChanged(path, oldContent, newContent, operation, category);
            } catch (Exception e) {
                auditLogger.warn("FileChangeListener error", e);
            }
        }
    }

    /**
     * Set a listener for real-time file change notifications.
     * The listener is called after every recordChange() invocation.
     */
    public void setFileChangeListener(FileChangeListener listener) {
        this.changeListener = listener;
    }

    // ========== DAO Helper Methods ==========

    private void writeChangeLog(String operation, String path, String oldContent, String newContent, String summary) {
        if (changeLogDao == null || currentTaskId == null) return;

        try {
            ChangeLogDao.ChangeLog log = ChangeLogDao.ChangeLog.create(
                currentTaskId,
                currentStepNumber,
                operation,
                path,
                operation,
                newContent != null ? newContent : oldContent,
                summary != null ? summary : operation + ": " + path,
                "SUCCESS"
            );
            changeLogDao.insert(log);
        } catch (Exception e) {
            auditLogger.warn("Failed to write change log to database", e);
        }
    }

    private void flushToSnapshot() {
        if (snapshotDao == null || currentTaskId == null || !hasChanges()) return;

        try {
            // Create snapshot if not exists
            if (currentSnapshotId < 0) {
                currentSnapshotId = snapshotDao.createSnapshot(
                    currentTaskId,
                    "AUTO",
                    "Auto snapshot after " + operationCount + " operations",
                    null
                );
            }

            // Add modified and created files to snapshot
            for (String path : modifiedFiles) {
                String content = newContents.get(path);
                if (content != null) {
                    SnapshotDao.FileSnapshot fileSnapshot = createDeltaAwareFileSnapshot(path, "MODIFY", content);
                    int fileId = snapshotDao.createFileSnapshot(fileSnapshot);
                    snapshotDao.linkFileSnapshot(currentSnapshotId, fileId);
                }
            }

            for (String path : createdFiles) {
                String content = newContents.get(path);
                if (content != null) {
                    SnapshotDao.FileSnapshot fileSnapshot = createDeltaAwareFileSnapshot(path, "CREATE", content);
                    int fileId = snapshotDao.createFileSnapshot(fileSnapshot);
                    snapshotDao.linkFileSnapshot(currentSnapshotId, fileId);
                }
            }

            for (String path : deletedFiles) {
                String content = deletedContents.get(path);
                if (content != null) {
                    SnapshotDao.FileSnapshot fileSnapshot = createDeltaAwareFileSnapshot(path, "DELETE", content);
                    int fileId = snapshotDao.createFileSnapshot(fileSnapshot);
                    snapshotDao.linkFileSnapshot(currentSnapshotId, fileId);
                }
            }
        } catch (Exception e) {
            auditLogger.warn("Failed to create snapshot", e);
        }
    }

    private SnapshotDao.FileSnapshot createDeltaAwareFileSnapshot(String path, String operation, String content) {
        SnapshotDao.FileSnapshot base = snapshotDao.findLatestFileSnapshot(currentTaskId, path);
        if (base != null) {
            String baseContent = snapshotDao.resolveContent(base);
            String delta = SnapshotDao.createDelta(baseContent, content);
            return SnapshotDao.FileSnapshot.create(currentTaskId, path, operation, content, base.id, delta);
        }
        return SnapshotDao.FileSnapshot.create(currentTaskId, path, operation, content, null);
    }

    /**
     * Force create a snapshot (e.g., before a risky operation)
     */
    public void forceSnapshot(String description) {
        if (!isDaoAvailable()) return;
        flushToSnapshot();

        currentSnapshotId = snapshotDao.createSnapshot(
            currentTaskId,
            "MANUAL",
            description,
            currentSnapshotId > 0 ? currentSnapshotId : null
        );
    }

    public boolean hasChanges() {
        return !modifiedFiles.isEmpty() || !createdFiles.isEmpty() || !deletedFiles.isEmpty();
    }

    /**
     * Undo the last change (incremental undo).
     * @return Summary of what was undone
     */
    public String undoLastChange() {
        if (changeHistory.isEmpty()) {
            return "没有可撤销的修改。";
        }

        ChangeRecord lastChange = changeHistory.pop();
        String path = lastChange.path;
        String operation = lastChange.operation;
        String oldContent = lastChange.oldContent;

        StringBuilder sb = new StringBuilder();
        sb.append("撤销: ").append(operation).append(" - ").append(path).append(NEWLINE);

        try {
            Path filePath = Paths.get(path);

            switch (operation) {
                case "CREATE":
                    // Undo create = delete the file
                    Files.deleteIfExists(filePath);
                    createdFiles.remove(path);
                    sb.append("已删除文件: ").append(path);
                    break;

                case "MODIFY":
                    // Undo modify = restore original content
                    if (oldContent != null) {
                        Files.write(filePath, oldContent.getBytes());
                        sb.append("已恢复文件内容: ").append(path);
                    } else {
                        sb.append("无法恢复: 原始内容未记录");
                    }
                    break;

                case "DELETE":
                    // Undo delete = restore the file
                    if (oldContent != null) {
                        Files.createDirectories(filePath.getParent());
                        Files.write(filePath, oldContent.getBytes());
                        deletedFiles.remove(path);
                        sb.append("已恢复文件: ").append(path);
                    } else {
                        sb.append("无法恢复: 文件内容未记录");
                    }
                    break;
            }
        } catch (IOException e) {
            sb.append("撤销失败: ").append(e.getMessage());
        }

        return sb.toString();
    }

    /**
     * Build a summary of changes for display.
     * - If > 5 files changed, only show file names
     * - If <= 5 files changed, show diff (+/- lines) with 10-line limit
     * - Full details always written to log file (if changeLogEnabled)
     */
    public String buildSummary() {
        if (!hasChanges()) {
            return I18n.get("change_summary_empty");
        }

        // Write full details to log file first (only if enabled)
        String logPath = changeLogEnabled ? writeDetailedLog() : null;

        int totalChanges = createdFiles.size() + modifiedFiles.size() + deletedFiles.size();
        StringBuilder sb = new StringBuilder();

        // Header with total count
        sb.append(I18n.get("change_summary_header", totalChanges, createdFiles.size(), modifiedFiles.size(), deletedFiles.size()));
        sb.append(NEWLINE);

        // If more than 5 files changed, only show file names
        if (totalChanges > 5) {
            sb.append(I18n.get("change_summary_more_files")).append(NEWLINE);

            for (String f : createdFiles) {
                sb.append(I18n.get("change_file_created", f)).append(NEWLINE);
            }
            for (String f : modifiedFiles) {
                sb.append(I18n.get("change_file_modified", f)).append(NEWLINE);
            }
            for (String f : deletedFiles) {
                sb.append(I18n.get("change_file_deleted", f)).append(NEWLINE);
            }
        } else {
            // Show detailed diff for each file (max 10 lines each)
            if (!createdFiles.isEmpty()) {
                sb.append("【").append(I18n.get("label_created")).append("】").append(NEWLINE);
                for (String f : createdFiles) {
                    sb.append("+ ").append(f).append(NEWLINE);
                    String content = newContents.get(f);
                    if (content != null) {
                        sb.append(NEWLINE).append(formatContentDiff(content, 10, true));
                    }
                }
                sb.append(NEWLINE);
            }

            if (!modifiedFiles.isEmpty()) {
                sb.append("【").append(I18n.get("label_modified")).append("】").append(NEWLINE);
                for (String f : modifiedFiles) {
                    sb.append("M ").append(f).append(NEWLINE);
                    String original = originalContents.get(f);
                    String newContent = newContents.get(f);
                    if (original != null || newContent != null) {
                        sb.append(NEWLINE).append(formatDiff(original, newContent, 10));
                    }
                }
                sb.append(NEWLINE);
            }

            if (!deletedFiles.isEmpty()) {
                sb.append("【").append(I18n.get("label_deleted")).append("】").append(NEWLINE);
                for (String f : deletedFiles) {
                    sb.append("- ").append(f).append(NEWLINE);
                    String content = deletedContents.get(f);
                    if (content != null) {
                        sb.append(formatContentDiff(content, 10, false));
                    }
                }
                sb.append(NEWLINE);
            }
        }

        if (logPath != null) {
            sb.append(I18n.get("change_summary_log", logPath));
        }
        return sb.toString();
    }

    /**
     * Build summary from database for audit purposes.
     * Used when reading change logs after a task has been saved.
     */
    public String buildSummaryFromDatabase(String taskId) {
        if (changeLogDao == null) {
            return buildSummary();  // Fallback to in-memory
        }

        try {
            List<ChangeLogDao.ChangeLog> logs = changeLogDao.findByTaskId(taskId);
            if (logs.isEmpty()) {
                return I18n.get("change_summary_empty");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Change Log from Database ===").append(NEWLINE);
            sb.append("Task: ").append(taskId).append(NEWLINE);
            sb.append("Total changes: ").append(logs.size()).append(NEWLINE);
            sb.append(NEWLINE);

            // Group by operation type
            int creates = 0, modifies = 0, deletes = 0, reads = 0;
            for (ChangeLogDao.ChangeLog log : logs) {
                switch (log.operation) {
                    case "CREATE": creates++; break;
                    case "MODIFY": modifies++; break;
                    case "DELETE": deletes++; break;
                    case "READ": reads++; break;
                }
            }

            sb.append("Summary: ").append(creates).append(" created, ")
              .append(modifies).append(" modified, ")
              .append(deletes).append(" deleted, ")
              .append(reads).append(" read").append(NEWLINE);
            sb.append(NEWLINE);

            // Show recent changes
            sb.append("Recent changes:").append(NEWLINE);
            int count = 0;
            for (ChangeLogDao.ChangeLog log : logs) {
                if (count++ >= 20) {
                    sb.append("  ... and ").append(logs.size() - count).append(" more").append(NEWLINE);
                    break;
                }
                String statusIcon = "SUCCESS".equals(log.status) ? "✓" : "✗";
                sb.append(String.format("  [%s] %s %s - %s%n",
                    statusIcon, log.operation, log.filePath != null ? log.filePath : log.toolName,
                    log.summary != null ? log.summary : ""));
            }

            return sb.toString();
        } catch (Exception e) {
            auditLogger.warn("Failed to build summary from database", e);
            return buildSummary();  // Fallback to in-memory
        }
    }

    public static String formatDiff(String original, String newContent, int maxLines) {
        if (original == null) original = "";
        if (newContent == null) newContent = "";

        String[] origLines = original.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        // Step 1: find common prefix (matching lines from start)
        int prefixLen = 0;
        while (prefixLen < origLines.length && prefixLen < newLines.length
                && origLines[prefixLen].equals(newLines[prefixLen])) {
            prefixLen++;
        }

        // Step 2: find common suffix (matching lines from end)
        int origEnd = origLines.length - 1;
        int newEnd = newLines.length - 1;
        while (origEnd >= prefixLen && newEnd >= prefixLen
                && origLines[origEnd].equals(newLines[newEnd])) {
            origEnd--;
            newEnd--;
        }

        StringBuilder sb = new StringBuilder();
        int lineNum = 1;
        int outputLines = 0;

        // Output common prefix
        for (int i = 0; i < prefixLen && outputLines < maxLines * 2; i++) {
            sb.append(String.format("%6d:   %s%n", lineNum++, origLines[i]));
            outputLines++;
        }

        // Step 3: LCS diff on middle section
        if (origEnd >= prefixLen || newEnd >= prefixLen) {
            String[] midOrig = Arrays.copyOfRange(origLines, prefixLen, origEnd + 1);
            String[] midNew = Arrays.copyOfRange(newLines, prefixLen, newEnd + 1);
            outputLines += appendLcsDiff(midOrig, midNew, sb, lineNum, maxLines * 2 - outputLines);
        }

        // Output common suffix
        int suffixLineNum = countLines(sb) + 1;
        for (int i = origEnd + 1; i < origLines.length; i++) {
            if (outputLines >= maxLines * 2) break;
            sb.append(String.format("%6d:   %s%n", suffixLineNum++, origLines[i]));
            outputLines++;
        }

        // Truncation check
        if (outputLines >= maxLines * 2) {
            int remainingTotal = origLines.length + newLines.length;
            int outputTotal = countLines(sb);
            if (remainingTotal > outputTotal) {
                sb.append(String.format("  ... (%d more lines)%n", remainingTotal - outputTotal));
            }
        }

        return sb.toString().trim().replace(NEWLINE, "\n") + NEWLINE;
    }

    /**
     * Append LCS-based diff of two line arrays to the StringBuilder.
     * Uses dynamic programming LCS to find minimal edit operations.
     */
    private static int appendLcsDiff(String[] orig, String[] newL,
                              StringBuilder sb, int startLineNum, int maxOutput) {
        int m = orig.length, n = newL.length;

        // For large middle sections, fall back to simple all-changed display
        if (m > 500 || n > 500) {
            return appendSimpleDiff(orig, newL, sb, startLineNum, maxOutput);
        }

        // Compute LCS table (int[m+1][n+1])
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (orig[i - 1].equals(newL[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // Backtrack through LCS table to collect operations (in reverse order)
        List<String> ops = new ArrayList<>();
        int i = m, j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && orig[i - 1].equals(newL[j - 1])) {
                ops.add("  " + orig[i - 1]);  // context
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                ops.add("+ " + newL[j - 1]);  // addition
                j--;
            } else {
                ops.add("- " + orig[i - 1]);  // deletion
                i--;
            }
        }

        // Output operations in forward order
        int lineNum = startLineNum;
        int output = 0;
        int k = ops.size() - 1;
        for (; k >= 0 && output < maxOutput; k--) {
            String op = ops.get(k);
            String prefix = op.substring(0, 2);
            String content = op.substring(2);
            if ("  ".equals(prefix)) {
                sb.append(String.format("%6d:   %s%n", lineNum++, content));
            } else if ("- ".equals(prefix)) {
                sb.append(String.format("%6d: - %s%n", lineNum++, content));
            } else {
                sb.append(String.format("%6d: + %s%n", lineNum++, content));
            }
            output++;
        }

        if (output >= maxOutput && k > 0) {
            sb.append(String.format("  ... (%d more diff lines)%n", k));
        }

        return output;
    }

    /**
     * Simple fallback: show all middle section lines as changes (deletions then additions).
     */
    private static int appendSimpleDiff(String[] orig, String[] newL,
                                 StringBuilder sb, int startLineNum, int maxOutput) {
        int lineNum = startLineNum;
        int output = 0;
        for (String line : orig) {
            if (output >= maxOutput) break;
            sb.append(String.format("%6d: - %s%n", lineNum++, line));
            output++;
        }
        for (String line : newL) {
            if (output >= maxOutput) break;
            sb.append(String.format("%6d: + %s%n", lineNum++, line));
            output++;
        }
        return output;
    }

    /**
     * Count the number of lines in the StringBuilder content.
     */
    private static int countLines(StringBuilder sb) {
        int count = 0;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') count++;
        }
        return count;
    }

    private String formatContentDiff(String content, int maxLines, boolean isAddition) {
        if (content == null) return "";

        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        String prefix = isAddition ? "+" : "-";

        for (int i = 0; i < Math.min(lines.length, maxLines); i++) {
            sb.append(String.format("%6d: %s %s%n", i + 1, prefix, lines[i]));
        }

        if (lines.length > maxLines) {
            sb.append(String.format("  ... (%d more lines)%n", lines.length - maxLines));
        }

        return sb.toString();
    }

    /**
     * Write detailed change log to file (only if changeLogEnabled)
     */
    private String writeDetailedLog() {
        if (!changeLogEnabled) {
            return null;
        }

        String userDir = PathUtils.getWorkingDir();
        String logDir = userDir + "/logs";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String logFile = logDir + "/change_" + timestamp + ".log";

        try {
            Files.createDirectories(Paths.get(logDir));
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile))) {
                writer.println("=== File Change Log ===");
                writer.println("Time: " + LocalDateTime.now().format(formatter));
                writer.println();

                if (!createdFiles.isEmpty()) {
                    writer.println("【Created Files】");
                    for (String f : createdFiles) {
                        writer.println("+ " + f);
                        String content = newContents.get(f);
                        if (content != null) {
                            String[] lines = content.split("\n");
                            writer.println("  Content (" + lines.length + " lines):");
                            for (int i = 0; i < lines.length; i++) {
                                writer.println("  + " + lines[i]);
                            }
                        }
                    }
                    writer.println();
                }

                if (!modifiedFiles.isEmpty()) {
                    writer.println("【Modified Files】");
                    for (String f : modifiedFiles) {
                        writer.println("M " + f);
                        String original = originalContents.get(f);
                        String newContent = newContents.get(f);
                        if (original != null || newContent != null) {
                            writer.println(getDetailedDiff(original, newContent));
                        }
                    }
                    writer.println();
                }

                if (!deletedFiles.isEmpty()) {
                    writer.println("【Deleted Files】");
                    for (String f : deletedFiles) {
                        writer.println("- " + f);
                        String content = deletedContents.get(f);
                        if (content != null) {
                            String[] lines = content.split("\n");
                            writer.println("  Content (" + lines.length + " lines):");
                            for (int i = 0; i < lines.length; i++) {
                                writer.println("  - " + lines[i]);
                            }
                        }
                    }
                    writer.println();
                }
            }
            auditLogger.info("Change log written to: {}", logFile);
            return logFile;
        } catch (IOException e) {
            auditLogger.error("Failed to write change log: {}", e.getMessage());
            return logFile;
        }
    }

    /**
     * Generate detailed diff with +/- indicators for actual changes
     */
    private String getDetailedDiff(String original, String newContent) {
        if (original == null) original = "";
        if (newContent == null) newContent = "";

        String[] origLines = original.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        StringBuilder sb = new StringBuilder();
        sb.append("  Original: ").append(origLines.length).append(" lines").append(NEWLINE);
        sb.append("  New: ").append(newLines.length).append(" lines").append(NEWLINE);
        sb.append("  Diff:").append(NEWLINE);

        // Step 1: find common prefix
        int prefixLen = 0;
        while (prefixLen < origLines.length && prefixLen < newLines.length
                && origLines[prefixLen].equals(newLines[prefixLen])) {
            prefixLen++;
        }

        // Step 2: find common suffix
        int origEnd = origLines.length - 1;
        int newEnd = newLines.length - 1;
        while (origEnd >= prefixLen && newEnd >= prefixLen
                && origLines[origEnd].equals(newLines[newEnd])) {
            origEnd--;
            newEnd--;
        }

        // Output common prefix
        for (int i = 0; i < prefixLen; i++) {
            sb.append(String.format("   %6d: %s%n", i + 1, origLines[i]));
        }

        // Step 3: LCS diff on middle section
        if (origEnd >= prefixLen || newEnd >= prefixLen) {
            String[] midOrig = Arrays.copyOfRange(origLines, prefixLen, origEnd + 1);
            String[] midNew = Arrays.copyOfRange(newLines, prefixLen, newEnd + 1);
            int midOffset = prefixLen;
            appendDetailedLcsDiff(midOrig, midNew, sb, midOffset, 100);
        }

        // Output common suffix
        int suffixStartOrig = Math.max(prefixLen, origEnd + 1);
        for (int i = suffixStartOrig; i < origLines.length; i++) {
            sb.append(String.format("   %6d: %s%n", i + 1, origLines[i]));
        }

        return sb.toString();
    }

    /**
     * LCS-based detailed diff for middle section, with position tracking.
     */
    private void appendDetailedLcsDiff(String[] orig, String[] newL,
                                       StringBuilder sb, int startOffset, int maxOutput) {
        int m = orig.length, n = newL.length;
        if (m > 500 || n > 500) {
            for (String line : orig) {
                if (maxOutput <= 0) break;
                sb.append(String.format("  -%6d: %s%n", startOffset++, line));
                maxOutput--;
            }
            for (String line : newL) {
                if (maxOutput <= 0) break;
                sb.append(String.format("  +%6d: %s%n", startOffset++, line));
                maxOutput--;
            }
            return;
        }

        // Compute LCS table
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (orig[i - 1].equals(newL[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // Backtrack to collect operations
        // Each operation: type + content + origPos + newPos
        // type: 'K' (keep), 'D' (delete), 'A' (add)
        List<DiffOp> ops = new ArrayList<>();
        int i = m, j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && orig[i - 1].equals(newL[j - 1])) {
                ops.add(new DiffOp('K', orig[i - 1], startOffset + i, startOffset + j));
                i--; j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                ops.add(new DiffOp('A', newL[j - 1], -1, startOffset + j));
                j--;
            } else {
                ops.add(new DiffOp('D', orig[i - 1], startOffset + i, -1));
                i--;
            }
        }

        // Output in forward order (reversed from collection)
        int outCount = 0;
        int k = ops.size() - 1;
        for (; k >= 0 && outCount < maxOutput; k--) {
            DiffOp op = ops.get(k);
            switch (op.type) {
                case 'K':
                    sb.append(String.format("   %6d: %s%n", op.origPos, op.content));
                    outCount++;
                    break;
                case 'D':
                    sb.append(String.format("  -%6d: %s%n", op.origPos, op.content));
                    outCount++;
                    break;
                case 'A':
                    sb.append(String.format("  +%6d: %s%n", op.newPos, op.content));
                    outCount++;
                    break;
            }
        }

        if (outCount >= maxOutput && k > 0) {
            sb.append(String.format("  ... (%d lines truncated, use log for full diff)%n", k));
        }
    }

    /**
     * Diff operation record for detailed diff output.
     */
    private static class DiffOp {
        final char type;     // 'K' keep, 'D' delete, 'A' add
        final String content;
        final int origPos;   // line number in original, -1 for additions
        final int newPos;    // line number in new, -1 for deletions

        DiffOp(char type, String content, int origPos, int newPos) {
            this.type = type;
            this.content = content;
            this.origPos = origPos;
            this.newPos = newPos;
        }
    }

    /**
     * Revert all changes made during this session.
     * @return Summary of what was reverted
     */
    public String revertChanges() {
        StringBuilder sb = new StringBuilder();
        int reverted = 0;

        // Undo all changes in reverse order (LIFO)
        while (!changeHistory.isEmpty()) {
            ChangeRecord change = changeHistory.pop();
            String path = change.path;
            String operation = change.operation;
            String oldContent = change.oldContent;

            try {
                Path filePath = Paths.get(path);

                switch (operation) {
                    case "CREATE":
                        Files.deleteIfExists(filePath);
                        sb.append("已删除: ").append(path).append(NEWLINE);
                        reverted++;
                        break;
                    case "MODIFY":
                        if (oldContent != null) {
                            Files.write(filePath, oldContent.getBytes());
                            sb.append("已恢复: ").append(path).append(NEWLINE);
                            reverted++;
                        }
                        break;
                    case "DELETE":
                        if (oldContent != null) {
                            Files.createDirectories(filePath.getParent());
                            Files.write(filePath, oldContent.getBytes());
                            sb.append("已恢复: ").append(path).append(NEWLINE);
                            reverted++;
                        }
                        break;
                }
            } catch (IOException e) {
                sb.append("恢复失败: ").append(path).append(" - ").append(e.getMessage()).append(NEWLINE);
            }
        }

        if (reverted == 0) {
            sb.append("没有需要恢复的修改。");
        } else {
            sb.append(NEWLINE).append("共恢复 ").append(reverted).append(" 项修改。");
        }

        // Clear tracking sets
        modifiedFiles.clear();
        createdFiles.clear();
        deletedFiles.clear();
        originalContents.clear();
        deletedContents.clear();
        newContents.clear();

        return sb.toString();
    }

    /**
     * Get the number of undoable changes.
     */
    public int getUndoableCount() {
        return changeHistory.size();
    }

    public void clear() {
        modifiedFiles.clear();
        createdFiles.clear();
        deletedFiles.clear();
        readFiles.clear();
        originalContents.clear();
        deletedContents.clear();
        newContents.clear();
        auditEntries.clear();
        changeHistory.clear();
    }

    public Set<String> getModifiedFiles() {
        return new HashSet<>(modifiedFiles);
    }

    public Set<String> getCreatedFiles() {
        return new HashSet<>(createdFiles);
    }

    public Set<String> getDeletedFiles() {
        return new HashSet<>(deletedFiles);
    }

    public boolean isAuditLogEnabled() {
        return auditLogEnabled;
    }

    /**
     * Build a file change summary for checkpoint storage
     */
    public String buildFileChangeSummary() {
        if (!hasChanges()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!createdFiles.isEmpty()) {
            sb.append("CREATED:").append(String.join(",", createdFiles)).append(";");
        }
        if (!modifiedFiles.isEmpty()) {
            sb.append("MODIFIED:").append(String.join(",", modifiedFiles)).append(";");
        }
        if (!deletedFiles.isEmpty()) {
            sb.append("DELETED:").append(String.join(",", deletedFiles)).append(";");
        }
        return sb.toString();
    }

    /**
     * Get tool result hashes for checkpoint verification
     */
    public String getToolResultHashes() {
        if (changeHistory.isEmpty()) {
            return null;
        }
        List<String> hashes = new ArrayList<>();
        for (ChangeRecord record : changeHistory) {
            // Hash the new content for verification
            String content = record.getNewContent();
            String hash = content != null ? SnapshotDao.hash(content) : null;
            hashes.add(hash);
        }
        try {
            return mapper.writeValueAsString(hashes);
        } catch (Exception e) {
            logger.warn("Failed to serialize tool result hashes", e);
            return null;
        }
    }

    private void addAuditEntry(String operation, String path, String originalContent, String newContent) {
        AuditEntry entry = new AuditEntry(operation, path, originalContent, newContent);
        auditEntries.add(entry);
        auditLogger.info("[{}] {} {} | original: {} chars | new: {} chars",
            entry.timestamp, operation, path,
            originalContent != null ? originalContent.length() : 0,
            newContent != null ? newContent.length() : 0);
    }

    public void flushAuditLog() {
        if (auditEntries.isEmpty() || !auditLogEnabled) {
            return;
        }
        auditLogger.info("=== Audit Log Summary ===");
        for (AuditEntry entry : auditEntries) {
            auditLogger.info("[{}] {} {}", entry.timestamp, entry.operation, entry.path);
        }
    }

    public List<AuditEntry> getAuditEntries() {
        return new ArrayList<>(auditEntries);
    }

    public static class AuditEntry {
        private final String timestamp;
        private final String operation;
        private final String path;
        private final String originalContent;
        private final String newContent;

        public AuditEntry(String operation, String path, String originalContent, String newContent) {
            this.timestamp = LocalDateTime.now().format(formatter);
            this.operation = operation;
            this.path = path;
            this.originalContent = originalContent;
            this.newContent = newContent;
        }

        public String getTimestamp() { return timestamp; }
        public String getOperation() { return operation; }
        public String getPath() { return path; }
        public String getOriginalContent() { return originalContent; }
        public String getNewContent() { return newContent; }
    }

    /**
     * Record for incremental undo - stores one change operation.
     */
    public static class ChangeRecord {
        private final String operation;
        private final String path;
        private final String oldContent;
        private final String newContent;
        private final FileCategory category;

        public ChangeRecord(String operation, String path, String oldContent, String newContent) {
            this(operation, path, oldContent, newContent, FileCategory.classifyPath(path));
        }

        public ChangeRecord(String operation, String path, String oldContent, String newContent, FileCategory category) {
            this.operation = operation;
            this.path = path;
            this.oldContent = oldContent;
            this.newContent = newContent;
            this.category = category;
        }

        public String getOperation() { return operation; }
        public String getPath() { return path; }
        public String getOldContent() { return oldContent; }
        public String getNewContent() { return newContent; }
        public FileCategory getCategory() { return category; }
    }
}