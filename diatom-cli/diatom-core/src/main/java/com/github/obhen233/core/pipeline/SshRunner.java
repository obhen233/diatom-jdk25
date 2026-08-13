package com.github.obhen233.core.pipeline;

import com.github.obhen233.spi.SshPasswordCipher;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.util.I18n;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Properties;

/**
 * PipelineRunner that executes commands on remote hosts via SSH.
 * Supports the "ssh_command" action type.
 */
public class SshRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SshRunner.class);
    private static final int DEFAULT_SSH_PORT = 22;
    private static final int CONNECTION_TIMEOUT = 15000;

    @Override
    public String getActionType() {
        return "ssh_command";
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String hostWithUser = step.getHost();
        if (hostWithUser == null || hostWithUser.trim().isEmpty()) {
            callback.onError("SSH step '" + step.getName() + "' has no host configured");
            return false;
        }

        String command = step.getCommand();
        if (command == null || command.trim().isEmpty()) {
            if (step.getCommands() != null && !step.getCommands().isEmpty()) {
                command = CommandJoiner.join(step.getCommands());
            } else {
                callback.onError("SSH step '" + step.getName() + "' has no command");
                return false;
            }
        }

        // Parse host:port and user
        String host;
        int port = DEFAULT_SSH_PORT;
        String user = variables.getOrDefault("SSH_USER", "root");

        String hostPart = hostWithUser;
        if (hostWithUser.contains("@")) {
            String[] parts = hostWithUser.split("@", 2);
            user = parts[0];
            hostPart = parts[1];
        }
        if (hostPart.contains(":")) {
            String[] parts = hostPart.split(":", 2);
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                callback.onError("Invalid port in host: " + hostWithUser);
                return false;
            }
        } else {
            host = hostPart;
        }

        String password = decryptSshPassword(variables.get("SSH_PASSWORD"));
        String keyPath = variables.get("SSH_KEY_PATH");

        callback.onOutput(I18n.get("deploy.ssh.conn", user, host, command) + "\n");
        logger.info("SSH step '{}': {}@{}:{} executing: {}", step.getName(), user, host, port, command);

        Session session = null;
        try {
            JSch jsch = new JSch();

            boolean usingPassword = password != null && !password.isEmpty();
            boolean usingKey = keyPath != null && !keyPath.isEmpty();

            // Use key file if specified, otherwise try default keys only if no password is provided.
            // Loading many default keys while using password auth can exceed the server's MaxAuthTries.
            if (usingKey) {
                jsch.addIdentity(keyPath);
                callback.onOutput(I18n.get("deploy.ssh.key_ok") + "\n");
            } else if (!usingPassword) {
                // Try default private keys only when password auth is not configured
                String userHome = System.getProperty("user.home");
                String[] defaultKeys = {
                    userHome + "/.ssh/id_rsa",
                    userHome + "/.ssh/id_ecdsa",
                    userHome + "/.ssh/id_ed25519",
                    userHome + "/.ssh/id_dsa"
                };
                boolean anyKeyAdded = false;
                for (String key : defaultKeys) {
                    java.io.File keyFile = new java.io.File(key);
                    if (keyFile.exists()) {
                        jsch.addIdentity(key);
                        anyKeyAdded = true;
                    }
                }
                if (!anyKeyAdded) {
                    callback.onOutput(I18n.get("deploy.ssh.no_key") + "\n");
                }
            }

            session = jsch.getSession(user, host, port);

            // Host key verification: "ask" prompts user via SshUserInfo when
            // a console is available (CLI mode). Falls back to auto-accept
            // when no console is present (IDE/embedded mode).
            Properties config = new Properties();
            String strictCheck = variables.getOrDefault("STRICT_HOST_KEY_CHECK", "ask");
            config.put("StrictHostKeyChecking", strictCheck);
            // Avoid publickey attempts exhausting MaxAuthTries when password auth is intended.
            if (usingPassword) {
                config.put("PreferredAuthentications", "keyboard-interactive,password");
            } else {
                config.put("PreferredAuthentications", "publickey,keyboard-interactive,password");
            }
            session.setConfig(config);
            session.setTimeout(CONNECTION_TIMEOUT);

            // Provide password through UserInfo to support both password and keyboard-interactive auth.
            // UserInfo is always set so that host key prompts (StrictHostKeyChecking=ask) work.
            SshUserInfo userInfo = new SshUserInfo(password);
            session.setUserInfo(userInfo);
            if (usingPassword) {
                session.setPassword(password);
                callback.onOutput(I18n.get("deploy.ssh.password_warn") + "\n");
            }

            session.connect(CONNECTION_TIMEOUT);

            // Execute command via SSH exec channel
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();

            channel.connect();

            // Read stdout
            long totalOutput = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    callback.onOutput(line + "\n");
                    totalOutput += line.length() + 1;
                    if (totalOutput > 512 * 1024) {
                        callback.onOutput("... (output too long, truncated)\n");
                        channel.disconnect();
                        break;
                    }
                }
            }

            // Read stderr
            StringBuilder errOutput = new StringBuilder();
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(err, "UTF-8"))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    errOutput.append(line).append("\n");
                }
            }

            // Wait for channel to close
            while (!channel.isClosed()) {
                Thread.sleep(100);
            }

            int exitCode = channel.getExitStatus();

            // Append stderr if any
            if (errOutput.length() > 0) {
                callback.onOutput("\n[stderr]\n" + errOutput.toString());
            }

            channel.disconnect();

            boolean success = exitCode == 0;
            if (success) {
                callback.onOutput("\n" + I18n.get("deploy.ssh.success", step.getName(), exitCode) + "\n");
            } else {
                callback.onOutput("\n" + I18n.get("deploy.ssh.failed", step.getName(), exitCode) + "\n");
            }
            return success;

        } catch (JSchException e) {
            String errorMsg = e.getMessage();
            callback.onOutput("\n" + I18n.get("deploy.ssh.conn_failed", errorMsg) + "\n");
            logger.error("SSH error for {}@{}: {}", user, host, errorMsg);
            return false;
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * Decrypt SSH password if it is encrypted (has {@code $ENC$} prefix).
     * Plaintext passwords are returned as-is for backward compatibility.
     * Uses the {@link SshPasswordCipher} SPI (custom or default).
     */
    private static String decryptSshPassword(String password) {
        if (password == null || password.isEmpty()) {
            return password;
        }
        SshPasswordCipher cipher = SpiLoader.getFirst(SshPasswordCipher.class,
                new DefaultSshPasswordCipher());
        return cipher.decrypt(password);
    }
}
