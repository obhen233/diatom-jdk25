package com.github.obhen233.core.mcp;

/**
 * ANSI color codes for MCP command output.
 */
public class McpColor {

    public static final String RESET = "\033[0m";
    public static final String BOLD = "\033[1m";
    public static final String DIM = "\033[2m";

    // Colors
    public static final String GREEN = "\033[32m";
    public static final String RED = "\033[31m";
    public static final String YELLOW = "\033[33m";
    public static final String BLUE = "\033[34m";
    public static final String CYAN = "\033[36m";
    public static final String MAGENTA = "\033[35m";

    // Light colors
    public static final String LIGHT_GREEN = "\033[92m";
    public static final String LIGHT_RED = "\033[91m";
    public static final String LIGHT_YELLOW = "\033[93m";
    public static final String LIGHT_BLUE = "\033[94m";

    // Background
    public static final String BG_YELLOW = "\033[43m";

    private static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

    /**
     * Apply color to message if not Windows (Windows console has limited ANSI support)
     */
    public static String colorize(String msg, String color) {
        if (isWindows) {
            return msg;
        }
        return color + msg + RESET;
    }

    public static String green(String msg) { return colorize(msg, GREEN); }
    public static String red(String msg) { return colorize(msg, RED); }
    public static String yellow(String msg) { return colorize(msg, YELLOW); }
    public static String blue(String msg) { return colorize(msg, BLUE); }
    public static String cyan(String msg) { return colorize(msg, CYAN); }
    public static String magenta(String msg) { return colorize(msg, MAGENTA); }

    public static String lightGreen(String msg) { return colorize(msg, LIGHT_GREEN); }
    public static String lightRed(String msg) { return colorize(msg, LIGHT_RED); }
    public static String lightYellow(String msg) { return colorize(msg, LIGHT_YELLOW); }
    public static String lightBlue(String msg) { return colorize(msg, LIGHT_BLUE); }

    public static String bold(String msg) { return colorize(msg, BOLD); }
    public static String dim(String msg) { return colorize(msg, DIM); }

    /**
     * Create a string repeated n times (Java 8 compatible)
     */
    public static String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    // Symbols
    public static String success(String msg) { return green("✓ ") + msg; }
    public static String error(String msg) { return red("✗ ") + msg; }
    public static String warning(String msg) { return yellow("⚠ WARNING: ") + msg; }
    public static String info(String msg) { return blue("ℹ ") + msg; }

    // Check mark for connected status
    public static String connected(String name) {
        return green("✓ ") + name + " (" + cyan("connected") + ")";
    }

    // X mark for disconnected/failed
    public static String disconnected(String name, String error) {
        if (error != null && !error.isEmpty()) {
            return red("✗ ") + name + " (" + yellow("disconnected") + "): " + error;
        }
        return red("✗ ") + name + " (" + yellow("disconnected") + ")";
    }
}
