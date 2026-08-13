package com.github.obhen233.spi.command;

/**
 * IDE output implementation that strips ANSI escape sequences.
 * Accumulates output to StringBuilder for WebSocket transmission.
 */
public class IdeCommandOutput implements CommandOutput {

    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void print(String text) {
        buffer.append(text).append("\n");
    }

    @Override
    public void printSuccess(String text) {
        buffer.append("✓ ").append(text).append("\n");
    }

    @Override
    public void printError(String text) {
        buffer.append("✗ ").append(text).append("\n");
    }

    @Override
    public void printInfo(String text) {
        buffer.append("ℹ ").append(text).append("\n");
    }

    @Override
    public void printDim(String text) {
        buffer.append(text).append("\n");
    }

    @Override
    public void printWarning(String text) {
        buffer.append("⚠ WARNING: ").append(text).append("\n");
    }

    @Override
    public void printBold(String text) {
        buffer.append(text).append("\n");
    }

    @Override
    public void printColored(String text, String ansiColor) {
        // Strip ANSI codes in IDE mode
        String stripped = stripAnsi(text);
        buffer.append(stripped).append("\n");
    }

    @Override
    public StringBuilder getBuffer() {
        return buffer;
    }

    /**
     * Strip ANSI escape sequences from a string
     */
    private static String stripAnsi(String text) {
        if (text == null) return "";
        // Pattern to match ANSI escape sequences
        return text.replaceAll("\033\\[[0-9;]*m", "");
    }
}
