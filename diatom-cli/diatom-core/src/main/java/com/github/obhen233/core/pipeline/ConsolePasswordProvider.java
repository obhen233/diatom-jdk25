package com.github.obhen233.core.pipeline;

import com.github.obhen233.spi.PasswordProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;

/**
 * Default password provider for CLI mode.
 *
 * Uses {@link java.io.Console#readPassword()} for masked (no-echo) input.
 * Falls back to {@link java.io.BufferedReader} on {@code System.in} when
 * no console is available (e.g., redirected input), though masking is
 * not supported in that case.
 */
public class ConsolePasswordProvider implements PasswordProvider {

    private static final Logger logger = LoggerFactory.getLogger(ConsolePasswordProvider.class);

    @Override
    public String promptPassword(String host, String user) {
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword("[SSH] %s@%s password: ", user, host);
            if (password == null) return null;
            return new String(password);
        }
        // Fallback: no console available (e.g., piped input)
        logger.warn("No console available — SSH password will be visible");
        System.out.print("[SSH] " + user + "@" + host + " password: ");
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        if (scanner.hasNextLine()) {
            return scanner.nextLine();
        }
        return null;
    }
}
