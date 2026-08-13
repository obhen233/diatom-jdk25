package com.github.obhen233.cli.provider;

import com.github.obhen233.cli.TerminalUI;
import com.github.obhen233.core.database.ChangeLogDao;
import com.github.obhen233.core.database.SnapshotDao;
import com.github.obhen233.core.database.TaskDao;
import com.github.obhen233.core.session.SessionTracker;
import com.github.obhen233.spi.CoreCommandProvider;
import com.github.obhen233.spi.command.CommandOutput;
import com.github.obhen233.util.I18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Snapshot command provider.
 * Provides list/show/diff/rollback for per-task file snapshots.
 */
public class SnapshotCommandProvider implements CoreCommandProvider,
        TerminalUI.SnapshotAware, TerminalUI.TaskDaoAware, TerminalUI.ChangeLogAware {

    private SnapshotDao snapshotDao;
    private TaskDao taskDao;
    private ChangeLogDao changeLogDao;

    public void initSnapshotDao(SnapshotDao snapshotDao) {
        this.snapshotDao = snapshotDao;
    }

    public void initTaskDao(TaskDao taskDao) {
        this.taskDao = taskDao;
    }

    public void initChangeLogDao(ChangeLogDao changeLogDao) {
        this.changeLogDao = changeLogDao;
    }

    @Override
    public String getCommandName() {
        return "snapshot";
    }

    @Override
    public String getDescription() {
        return "{{cli.snapshot.description}}";
    }

    @Override
    public String getHelp() {
        return "{{cli.snapshot.help}}";
    }

    @Override
    public String execute(String args, CommandOutput output) {
        if (snapshotDao == null || taskDao == null) {
            return "ERROR {{snapshot.error.not_available}}";
        }

        String[] parts = args.trim().split("\\s+");

        if (parts.length == 0 || parts[0].isEmpty()) {
            return "ERROR {{snapshot.usage}}";
        }

        String task = parts[0];

        if (parts.length == 1) {
            return handleSnapshotList(task);
        }

        if (parts.length == 2) {
            return handleSnapshotShow(task, parts[1]);
        }

        if (parts.length == 3) {
            String action = parts[2].toLowerCase(Locale.ROOT);
            if ("diff".equals(action)) {
                return handleSnapshotDiff(task, parts[1]);
            } else if ("rollback".equals(action)) {
                return handleSnapshotRollback(task, parts[1]);
            }
        }

        return "ERROR {{snapshot.error.unknown}}";
    }

    private String handleSnapshotList(String taskId) {
        List<SnapshotDao.Snapshot> snapshots = snapshotDao.findSnapshotsByTaskId(taskId);
        if (snapshots.isEmpty()) {
            return "INFO {{snapshot.list.empty:" + taskId + "}}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("INFO Snapshots for task ").append(taskId).append(" (").append(snapshots.size()).append(" total):\n");
        sb.append(String.format("%-6s %-6s %-10s %-8s %-26s %s%n", "ORD", "ID", "TYPE", "PARENT", "TIME", "DESCRIPTION"));
        sb.append(String.format("%-6s %-6s %-10s %-8s %-26s %s%n", "---", "--", "----", "------", "----", "-----------"));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int ordinal = 1;
        for (SnapshotDao.Snapshot s : snapshots) {
            sb.append(String.format("%-6d %-6d %-10s %-8s %-26s %s%n",
                ordinal++,
                s.id,
                s.snapshotType != null ? s.snapshotType : "AUTO",
                s.parentSnapshotId != null ? String.valueOf(s.parentSnapshotId) : "-",
                sdf.format(new Date(s.createdAt)),
                s.description != null ? s.description : ""));
        }
        return sb.toString();
    }

    private String handleSnapshotShow(String taskId, String snapRef) {
        SnapshotDao.Snapshot snapshot = resolveSnapshot(taskId, snapRef);
        if (snapshot == null) {
            return "ERROR {{snapshot.error.not_found:" + snapRef + "}}";
        }

        List<SnapshotDao.FileSnapshot> files = snapshotDao.getSnapshotsFiles(taskId, snapshot.id);
        if (files.isEmpty()) {
            return "INFO {{snapshot.show.empty:" + snapshot.id + "}}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("INFO Snapshot ").append(snapshot.id).append(" for task ").append(taskId).append("\n");
        sb.append("Type: ").append(snapshot.snapshotType != null ? snapshot.snapshotType : "AUTO").append("\n");
        sb.append("Parent: ").append(snapshot.parentSnapshotId != null ? snapshot.parentSnapshotId : "(none)").append("\n");
        List<SnapshotDao.Snapshot> children = findChildSnapshots(taskId, snapshot.id);
        sb.append("Children: ").append(formatSnapshotIds(children)).append("\n");
        sb.append("Created: ").append(formatTime(snapshot.createdAt)).append("\n");
        sb.append("Files:").append("\n");

        for (SnapshotDao.FileSnapshot f : files) {
            sb.append(String.format("  [%s] %s (hash=%s, %d bytes)%n",
                f.operation != null ? f.operation : "?",
                f.filePath,
                f.contentHash != null ? f.contentHash.substring(0, Math.min(16, f.contentHash.length())) : "",
                f.content != null ? f.content.length : 0));
            String content = snapshotDao.resolveContent(f);
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < Math.min(lines.length, 5); i++) {
                sb.append(String.format("    %4d: %s%n", i + 1, lines[i]));
            }
            if (lines.length > 5) {
                sb.append(String.format("    ... (%d more lines)%n", lines.length - 5));
            }
        }
        return sb.toString();
    }

    private String handleSnapshotDiff(String taskId, String snapRef) {
        SnapshotDao.Snapshot snapshot = resolveSnapshot(taskId, snapRef);
        if (snapshot == null) {
            return "ERROR {{snapshot.error.not_found:" + snapRef + "}}";
        }

        List<SnapshotDao.FileSnapshot> files = snapshotDao.getSnapshotsFiles(taskId, snapshot.id);
        if (files.isEmpty()) {
            return "INFO {{snapshot.diff.empty:" + snapshot.id + "}}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("INFO Diff for snapshot ").append(snapshot.id).append(" vs current disk:\n");

        for (SnapshotDao.FileSnapshot f : files) {
            sb.append("\n--- ").append(f.filePath).append(" [").append(f.operation).append("] ---\n");
            String snapshotContent = snapshotDao.resolveContent(f);

            if ("DELETE".equals(f.operation)) {
                Path currentPath = Paths.get(f.filePath);
                if (Files.exists(currentPath)) {
                    sb.append("NOTE: File currently exists on disk but was deleted in this snapshot.\n");
                } else {
                    sb.append("(deleted in snapshot; file does not exist on disk)\n");
                }
                String[] lines = snapshotContent.split("\n", -1);
                for (int i = 0; i < Math.min(lines.length, 5); i++) {
                    sb.append(String.format("-%4d: %s%n", i + 1, lines[i]));
                }
                if (lines.length > 5) {
                    sb.append(String.format("... (%d more deleted lines)%n", lines.length - 5));
                }
            } else if ("CREATE".equals(f.operation)) {
                sb.append("(created in snapshot; diff vs empty file):\n");
                sb.append(SessionTracker.formatDiff("", snapshotContent, 5));
            } else {
                // MODIFY
                String currentContent = readFile(f.filePath);
                sb.append(SessionTracker.formatDiff(currentContent, snapshotContent, 5));
            }
        }

        // Diff against parent snapshot if present
        if (snapshot.parentSnapshotId != null) {
            sb.append("\n--- Parent diff (snapshot ").append(snapshot.id).append(" vs parent ").append(snapshot.parentSnapshotId).append(") ---\n");
            List<SnapshotDao.FileSnapshot> parentFiles = snapshotDao.getSnapshotsFiles(taskId, snapshot.parentSnapshotId);
            for (SnapshotDao.FileSnapshot f : files) {
                SnapshotDao.FileSnapshot parent = findByPath(parentFiles, f.filePath);
                String current = snapshotDao.resolveContent(f);
                String previous = parent != null ? snapshotDao.resolveContent(parent) : "";
                if (parent == null || !current.equals(previous)) {
                    sb.append("\n").append(f.filePath).append(":\n");
                    sb.append(SessionTracker.formatDiff(previous, current, 5));
                }
            }
        }

        return sb.toString();
    }

    private String handleSnapshotRollback(String taskId, String snapRef) {
        SnapshotDao.Snapshot snapshot = resolveSnapshot(taskId, snapRef);
        if (snapshot == null) {
            return "ERROR {{snapshot.error.not_found:" + snapRef + "}}";
        }

        List<SnapshotDao.FileSnapshot> files = snapshotDao.getSnapshotsFiles(taskId, snapshot.id);
        if (files.isEmpty()) {
            return "INFO {{snapshot.rollback.empty:" + snapshot.id + "}}";
        }

        int restored = 0;
        int deleted = 0;
        int failed = 0;

        for (SnapshotDao.FileSnapshot f : files) {
            try {
                Path path = Paths.get(f.filePath);
                if ("DELETE".equals(f.operation)) {
                    Files.deleteIfExists(path);
                    deleted++;
                } else {
                    if (path.getParent() != null) {
                        Files.createDirectories(path.getParent());
                    }
                    byte[] content = snapshotDao.resolveContent(f).getBytes(StandardCharsets.UTF_8);
                    Files.write(path, content);
                    restored++;
                }
            } catch (IOException e) {
                failed++;
            }
        }

        List<SnapshotDao.Snapshot> children = findChildSnapshots(taskId, snapshot.id);

        StringBuilder sb = new StringBuilder();
        sb.append("SUCCESS Rolled back snapshot ").append(snapshot.id).append(" for task ").append(taskId).append(".\n");
        if (!children.isEmpty()) {
            sb.append("WARNING: snapshot ").append(snapshot.id).append(" has later child snapshots: ")
                .append(formatSnapshotIds(children)).append(".\n");
            sb.append("Rollback restored files to snapshot ").append(snapshot.id)
                .append(" only; later snapshots remain in database and can be restored separately.\n");
        }
        sb.append("Restored: ").append(restored).append(", Deleted: ").append(deleted);
        if (failed > 0) {
            sb.append(", Failed: ").append(failed);
        }
        sb.append("\nNOTE: If a later task modified the same file, that change has been overwritten by this rollback.");
        return sb.toString();
    }

    private SnapshotDao.Snapshot resolveSnapshot(String taskId, String snapRef) {
        List<SnapshotDao.Snapshot> snapshots = snapshotDao.findSnapshotsByTaskId(taskId);
        if (snapshots.isEmpty()) {
            return null;
        }
        try {
            int ordinal = Integer.parseInt(snapRef);
            if (ordinal >= 1 && ordinal <= snapshots.size()) {
                return snapshots.get(ordinal - 1);
            }
        } catch (NumberFormatException e) {
            // Try as numeric snapshot id
            try {
                int id = Integer.parseInt(snapRef);
                for (SnapshotDao.Snapshot s : snapshots) {
                    if (s.id == id) {
                        return s;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private List<SnapshotDao.Snapshot> findChildSnapshots(String taskId, int snapshotId) {
        List<SnapshotDao.Snapshot> children = new java.util.ArrayList<>();
        List<SnapshotDao.Snapshot> snapshots = snapshotDao.findSnapshotsByTaskId(taskId);
        for (SnapshotDao.Snapshot snapshot : snapshots) {
            if (snapshot.parentSnapshotId != null && snapshot.parentSnapshotId == snapshotId) {
                children.add(snapshot);
            }
        }
        return children;
    }

    private String formatSnapshotIds(List<SnapshotDao.Snapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snapshots.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(snapshots.get(i).id);
        }
        return sb.toString();
    }

    private SnapshotDao.FileSnapshot findByPath(List<SnapshotDao.FileSnapshot> files, String path) {
        if (files == null) return null;
        for (SnapshotDao.FileSnapshot f : files) {
            if (f.filePath != null && f.filePath.equals(path)) {
                return f;
            }
        }
        return null;
    }

    private String readFile(String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) return "";
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private String formatTime(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
    }
}
