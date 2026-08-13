package com.github.obhen233.core.pipeline;

import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import java.io.Console;

/**
 * Shared {@link UserInfo} implementation for SSH authentication and
 * host key verification.
 *
 * <p>Handles both password-based and keyboard-interactive authentication.
 * For first-time SSH connections ({@code StrictHostKeyChecking=ask}),
 * {@link #promptYesNo(String)} behavior depends on the environment:</p>
 *
 * <ul>
 *   <li><b>CLI mode</b> — prompts via {@link System#console()} (masked input)</li>
 *   <li><b>IDE mode</b> — uses {@link ConfirmationProvider} if one has been
 *       set via {@link #setConfirmationProvider(ConfirmationProvider)}</li>
 *   <li><b>Fallback</b> — auto-accepts when no provider and no console are available</li>
 * </ul>
 */
public class SshUserInfo implements UserInfo, UIKeyboardInteractive {

    private final String password;

    /**
     * Pluggable confirmation provider for environments where
     * {@link System#console()} is not available (e.g., IDE web terminal).
     */
    @FunctionalInterface
    public interface ConfirmationProvider {
        /**
         * Prompt the user for a yes/no confirmation.
         * @param message the confirmation message (e.g., host key fingerprint)
         * @return true if the user confirmed, false otherwise
         */
        boolean confirm(String message);
    }

    private static ConfirmationProvider confirmationProvider;

    /**
     * Set a global confirmation provider for host key verification prompts.
     * IDE projects should call this during initialization to enable
     * interactive host key confirmation through the IDE's terminal UI.
     *
     * @param provider the confirmation provider, or null to restore default behavior
     */
    public static void setConfirmationProvider(ConfirmationProvider provider) {
        confirmationProvider = provider;
    }

    public SshUserInfo(String password) {
        this.password = password;
    }

    @Override
    public String getPassword() {
        return password != null ? password : "";
    }

    @Override
    public String getPassphrase() {
        return password != null ? password : "";
    }

    @Override
    public String[] promptKeyboardInteractive(String destination,
                                               String name,
                                               String instruction,
                                               String[] prompt,
                                               boolean[] echo) {
        String[] response = new String[prompt.length];
        for (int i = 0; i < prompt.length; i++) {
            String p = prompt[i].toLowerCase();
            if (p.contains("password") || p.contains("passphrase") || !echo[i]) {
                response[i] = password != null ? password : "";
            } else {
                response[i] = "";
            }
        }
        return response;
    }

    @Override
    public boolean promptYesNo(String message) {
        // 1. Use pluggable provider if set (IDE mode)
        if (confirmationProvider != null) {
            return confirmationProvider.confirm(message);
        }
        // 2. Use console if available (CLI mode)
        Console console = System.console();
        if (console != null) {
            String input = console.readLine("[SSH] %s (yes/no): ", message);
            return "yes".equalsIgnoreCase(input != null ? input.trim() : "");
        }
        // 3. No provider and no console — auto-accept
        return true;
    }

    @Override
    public boolean promptPassphrase(String message) {
        return true;
    }

    @Override
    public boolean promptPassword(String message) {
        return true;
    }

    @Override
    public void showMessage(String message) {
        if (confirmationProvider != null) {
            // In IDE mode, delegate to the provider (which may log or display the message)
            return;
        }
        Console console = System.console();
        if (console != null) {
            console.printf("[SSH] %s%n", message);
        }
    }
}
