package com.github.obhen233.core.gateway;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerInfo.WorkerStatus;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.core.gateway.task.TaskState;
import com.github.obhen233.core.gateway.task.TaskStatus;
import com.github.obhen233.core.mcp.McpColor;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Terminal-based real-time dashboard for Gateway monitoring.
 * Renders worker status, task summary, and load charts in the terminal.
 * Refreshes every 3 seconds. Press 'q' to exit.
 */
public class TerminalDashboardRenderer {
    private static final Logger logger = LoggerFactory.getLogger(TerminalDashboardRenderer.class);

    private final Terminal terminal;
    private final WorkerRegistry registry;
    private final TaskManager taskManager;
    private final String instanceId;
    private final boolean isWindows;

    private static final long REFRESH_INTERVAL_MS = 3000;
    private static final int MIN_WIDTH_FOR_CHART = 60;

    // Task status display order
    private static final TaskStatus[] DISPLAY_STATUSES = {
        TaskStatus.COMPLETED, TaskStatus.IN_PROGRESS, TaskStatus.PENDING,
        TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.SUSPENDED,
        TaskStatus.ASSIGNED, TaskStatus.TIMEOUT, TaskStatus.TIMEOUT_SOON,
        TaskStatus.TOKEN_EXHAUSTED, TaskStatus.SUSPECT, TaskStatus.CANCELLING
    };

    public TerminalDashboardRenderer(Terminal terminal, WorkerRegistry registry,
                                      TaskManager taskManager, String instanceId) {
        this.terminal = terminal;
        this.registry = registry;
        this.taskManager = taskManager;
        this.instanceId = instanceId;
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Enter the dashboard loop. Blocks until user presses 'q'.
     */
    public void enterDashboard() {
        // Enter raw mode for character-by-character input
        org.jline.terminal.Attributes savedAttributes = null;
        try {
            savedAttributes = terminal.enterRawMode();
            org.jline.utils.NonBlockingReader reader =
                (org.jline.utils.NonBlockingReader) terminal.reader();
            while (true) {
                // Non-blocking read with timeout — works reliably on all platforms
                int ch = reader.read(REFRESH_INTERVAL_MS);
                if (ch == 'q' || ch == 'Q') {
                    break;
                }

                clearScreen();
                int width = terminal.getWidth();
                if (width < 40) width = 40; // Minimum sensible width

                renderHeader(width);
                renderWorkerTable(width);
                renderTaskSummary(width);
                renderLoadChart(width);
                renderFooter(width);

                terminal.flush();
            }
        } catch (IOException e) {
            logger.error("Dashboard loop interrupted", e);
        } finally {
            if (savedAttributes != null) {
                terminal.setAttributes(savedAttributes);
            }
            terminal.writer().flush();
        }
    }

    /**
     * Clear the terminal screen. On Windows, print newlines instead of ANSI codes.
     */
    private void clearScreen() {
        if (isWindows) {
            // Print enough newlines to "clear" the visible area
            for (int i = 0; i < 80; i++) {
                terminal.writer().println();
            }
        } else {
            terminal.writer().print("\033[2J\033[H");
        }
    }

    /**
     * Render the dashboard header with instance ID, time, and refresh interval.
     */
    private void renderHeader(int width) {
        String title = " Diatom Gateway Dashboard ";
        String separator = McpColor.repeat("=", width);
        terminal.writer().println(McpColor.bold(separator));
        terminal.writer().println(McpColor.bold(title));
        String instanceInfo = " Instance: " + instanceId
                + "  [" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "]"
                + "  Refresh: " + (REFRESH_INTERVAL_MS / 1000) + "s";
        terminal.writer().println(McpColor.dim(instanceInfo));
        terminal.writer().println(McpColor.bold(separator));
        terminal.writer().println();
    }

    /**
     * Render the worker table showing each worker's status, model, load, etc.
     */
    private void renderWorkerTable(int width) {
        List<WorkerInfo> workers = registry.localWorkers();
        terminal.writer().println(McpColor.bold(" Workers (" + workers.size() + ")"));
        if (workers.isEmpty()) {
            terminal.writer().println("   " + McpColor.dim("No workers connected."));
            terminal.writer().println();
            return;
        }

        // Table header
        String header = String.format(" %-16s %-14s %-14s %-12s %-10s %-10s %s",
                "ID", "Status", "Model", "Group", "Load", "Active/Max", "Heartbeat");
        terminal.writer().println(header);
        terminal.writer().println(McpColor.dim(McpColor.repeat("-", Math.min(width, 90))));

        for (WorkerInfo w : workers) {
            String statusColor = getStatusColor(w.getStatus());
            String statusStr = statusColor + padRight(w.getStatus().name(), 14) + McpColor.RESET;
            String loadBar = buildLoadBar(w.getMetrics().getCurrentLoad(), 8);
            String activeMax = w.getMetrics().getActiveTasks() + "/" + w.getMaxConcurrency();
            String heartbeat = formatHeartbeatAge(w.getMetrics().getHeartbeatAgeMs());

            // On Windows, statusColor is stripped, so statusStr is just the name
            if (isWindows) {
                statusStr = padRight(w.getStatus().name(), 14);
            }

            String groupStr = w.getGroup() != null && !w.getGroup().isEmpty()
                    ? w.getGroup().replaceAll("[\\x00-\\x1f\\x7f]", "_") : "-";
            String line = String.format(" %-16s %-14s %-14s %-12s %-10s %-10s %s",
                    w.getWorkerId(), statusStr, w.getModel() != null ? w.getModel() : "-",
                    groupStr, loadBar, activeMax, heartbeat);
            terminal.writer().println(line);
        }
        terminal.writer().println();
    }

    /**
     * Render task summary with block bars showing count per status.
     */
    private void renderTaskSummary(int width) {
        List<TaskState> allTasks = taskManager.getAllTasks();
        terminal.writer().println(McpColor.bold(" Tasks (" + allTasks.size() + " total)"));

        if (allTasks.isEmpty()) {
            terminal.writer().println("   " + McpColor.dim("No tasks."));
            terminal.writer().println();
            return;
        }

        // Count tasks by status
        Map<TaskStatus, Integer> counts = new ConcurrentHashMap<>();
        for (TaskState t : allTasks) {
            TaskStatus s = t.getStatus();
            counts.put(s, counts.getOrDefault(s, 0) + 1);
        }

        int maxCount = 0;
        for (int c : counts.values()) {
            if (c > maxCount) maxCount = c;
        }

        int barWidth = Math.min(30, width - 25);
        if (barWidth < 5) barWidth = 5;

        for (TaskStatus status : DISPLAY_STATUSES) {
            int count = counts.getOrDefault(status, 0);
            if (count == 0) continue;

            String label = padRight(status.name(), 18);
            String color = getTaskStatusColor(status);
            String bar = buildBlockBar(count, maxCount, barWidth);

            String line;
            if (isWindows) {
                line = " " + label + " " + count + "\t" + bar;
            } else {
                line = " " + color + label + McpColor.RESET + " " + count + "\t" + bar;
            }
            terminal.writer().println(line);
        }
        terminal.writer().println();
    }

    /**
     * Render worker load chart with block bars.
     */
    private void renderLoadChart(int width) {
        List<WorkerInfo> workers = registry.localWorkers();
        if (width < MIN_WIDTH_FOR_CHART || workers.isEmpty()) {
            return; // Skip chart if terminal is too narrow or no workers
        }

        terminal.writer().println(McpColor.bold(" Worker Load"));

        // Find max worker ID length for alignment
        int maxIdLen = 0;
        for (WorkerInfo w : workers) {
            if (w.getWorkerId().length() > maxIdLen) maxIdLen = w.getWorkerId().length();
        }
        maxIdLen = Math.min(maxIdLen, 20);

        int barWidth = width - maxIdLen - 15; // Leave room for percentage
        if (barWidth < 5) barWidth = 5;

        for (WorkerInfo w : workers) {
            double load = w.getMetrics().getCurrentLoad();
            String id = padRight(w.getWorkerId(), maxIdLen);
            String bar = buildBlockBar((int) Math.round(load * barWidth), barWidth, barWidth);
            String pct = String.format("%5.1f%%", load * 100);

            String line = " " + id + " " + bar + " " + pct;
            terminal.writer().println(line);
        }
        terminal.writer().println();
    }

    /**
     * Render the footer with exit hint.
     */
    private void renderFooter(int width) {
        terminal.writer().println(McpColor.dim(McpColor.repeat("-", width)));
        terminal.writer().println(McpColor.dim(" Press 'q' to exit dashboard"));
    }

    // ========== Rendering Helpers ==========

    /**
     * Block character for bar rendering.
     * Uses ASCII '#' on Windows (where Unicode may not render correctly),
     * Unicode FULL BLOCK on other platforms.
     */
    private static final char BLOCK_CHAR = System.getProperty("os.name", "").toLowerCase().contains("win") ? '#' : '\u2588';

    /**
     * Build a load bar like [████    ].
     */
    private static String buildLoadBar(double load, int width) {
        int filled = (int) Math.round(load * width);
        if (filled < 0) filled = 0;
        if (filled > width) filled = width;

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < filled; i++) {
            sb.append(BLOCK_CHAR);
        }
        for (int i = filled; i < width; i++) {
            sb.append(' ');
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Build a block bar using full blocks.
     */
    private static String buildBlockBar(int len, int total, int width) {
        if (total <= 0) return McpColor.repeat(" ", width);
        int filled = (int) Math.round((double) len / total * width);
        if (filled < 0) filled = 0;
        if (filled > width) filled = width;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filled; i++) {
            sb.append(BLOCK_CHAR);
        }
        for (int i = filled; i < width; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Get ANSI color for a worker status.
     */
    private static String getStatusColor(WorkerStatus status) {
        if (status == null) return McpColor.DIM;
        switch (status) {
            case ONLINE: return McpColor.GREEN;
            case SUSPECT: return McpColor.YELLOW;
            case SHUTTING_DOWN: return McpColor.RED;
            case OFFLINE: return McpColor.DIM;
            default: return McpColor.DIM;
        }
    }

    /**
     * Get ANSI color for a task status.
     */
    private static String getTaskStatusColor(TaskStatus status) {
        if (status == null) return McpColor.DIM;
        switch (status) {
            case COMPLETED: return McpColor.GREEN;
            case IN_PROGRESS: return McpColor.BLUE;
            case PENDING: return McpColor.YELLOW;
            case FAILED: return McpColor.RED;
            case CANCELLED: return McpColor.DIM;
            case SUSPENDED: return McpColor.YELLOW;
            case ASSIGNED: return McpColor.MAGENTA;
            case TIMEOUT: return McpColor.RED;
            case TIMEOUT_SOON: return McpColor.YELLOW;
            case TOKEN_EXHAUSTED: return McpColor.MAGENTA;
            case SUSPECT: return McpColor.YELLOW;
            case CANCELLING: return McpColor.RED;
            default: return McpColor.DIM;
        }
    }

    /**
     * Format heartbeat age in human-readable form.
     */
    private static String formatHeartbeatAge(long ms) {
        if (ms < 0) ms = 0;
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format("%.1fs", ms / 1000.0);
        long minutes = ms / 60000;
        long seconds = (ms % 60000) / 1000;
        return minutes + "m " + seconds + "s";
    }

    /**
     * Pad a string to the specified length with trailing spaces.
     */
    private static String padRight(String s, int len) {
        if (s == null) s = "";
        if (s.length() >= len) return s.substring(0, len);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
