package com.github.obhen233.spi;

/**
 * Provides interactive password prompting for SSH authentication.
 *
 * When a deploy step targets a remote host and neither a password nor
 * an SSH key is configured in deploy.yaml, the pipeline invokes this
 * provider to collect the password interactively.
 *
 * <p>Implementations should mask the input (e.g., {@code ****} or
 * {@code ····}) and may cache the password for the duration of a
 * single prompt. Password caching across multiple steps for the same
 * host is handled by the caller ({@code PipelineService}).</p>
 *
 * <p>CLI default: {@code ConsolePasswordProvider} uses
 * {@link java.io.Console#readPassword()}.
 * IDE projects can provide their own implementation as a Spring bean
 * or via SPI.</p>
 */
@FunctionalInterface
public interface PasswordProvider {

    /**
     * Prompt the user for an SSH password.
     *
     * @param host the target host address
     * @param user the SSH user name
     * @return the password string, or {@code null} if the user cancels
     */
    String promptPassword(String host, String user);
}
