package com.github.obhen233.spi.command;

import com.github.obhen233.core.mcp.McpColor;

/**
 * CLI output implementation using System.out with ANSI colors.
 * Windows is detected and ANSI codes are stripped if not supported.
 */
public class CliCommandOutput implements CommandOutput {

    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void print(String text) {
        System.out.println(text);
        buffer.append(text).append("\n");
    }

    @Override
    public void printSuccess(String text) {
        String colored = McpColor.success(text);
        System.out.println(colored);
        buffer.append(McpColor.success(text)).append("\n");
    }

    @Override
    public void printError(String text) {
        String colored = McpColor.error(text);
        System.out.println(colored);
        buffer.append(colored).append("\n");
    }

    @Override
    public void printInfo(String text) {
        String colored = McpColor.info(text);
        System.out.println(colored);
        buffer.append(colored).append("\n");
    }

    @Override
    public void printDim(String text) {
        String colored = McpColor.dim(text);
        System.out.println(colored);
        buffer.append(colored).append("\n");
    }

    @Override
    public void printWarning(String text) {
        String colored = McpColor.warning(text);
        System.out.println(colored);
        buffer.append(colored).append("\n");
    }

    @Override
    public void printBold(String text) {
        String colored = McpColor.bold(text);
        System.out.println(colored);
        buffer.append(colored).append("\n");
    }

    @Override
    public void printColored(String text, String ansiColor) {
        String colored = McpColor.colorize(text, ansiColor);
        System.out.println(colored);
        buffer.append(colored).append("\n");
    }

    @Override
    public StringBuilder getBuffer() {
        return buffer;
    }
}
