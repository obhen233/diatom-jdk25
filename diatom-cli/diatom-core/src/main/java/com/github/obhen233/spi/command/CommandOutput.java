package com.github.obhen233.spi.command;

/**
 * Output interface for CLI commands.
 * Decouples command execution from output rendering.
 */
public interface CommandOutput {

    /**
     * Print raw text
     */
    void print(String text);

    /**
     * Print success message (green)
     */
    void printSuccess(String text);

    /**
     * Print error message (red)
     */
    void printError(String text);

    /**
     * Print info message (blue)
     */
    void printInfo(String text);

    /**
     * Print dimmed text
     */
    void printDim(String text);

    /**
     * Print warning message (yellow)
     */
    void printWarning(String text);

    /**
     * Print bold text
     */
    void printBold(String text);

    /**
     * Print colored text with specified ANSI color
     */
    void printColored(String text, String ansiColor);

    /**
     * Get accumulated output as StringBuilder
     */
    StringBuilder getBuffer();
}
