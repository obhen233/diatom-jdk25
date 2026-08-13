package com.github.obhen233.core.pipeline;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.github.obhen233.spi.SshPasswordCipher;
import com.github.obhen233.spi.SpiLoader;
import com.github.obhen233.util.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;
import java.util.Properties;

/**
 * PipelineRunner that uploads files to remote hosts via SFTP (SCP).
 * Supports the "scp" action type.
 *
 * Expects step configuration:
 *   action: "scp"
 *   host: "user@host:port"
 *   files:
 *     - local: "path/to/local/file"
 *       remote: "/path/to/remote/file"
 */
public class ScpRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ScpRunner.class);
    private static final int DEFAULT_SSH_PORT = 22;
    private static final int CONNECTION_TIMEOUT = 15000;

    @Override
    public String getActionType() {
        return "scp";
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String hostWithUser = step.getHost();
        if (hostWithUser == null || hostWithUser.trim().isEmpty()) {
            callback.onError("SCP step '" + step.getName() + "' has no host configured");
            return false;
        }

        if (step.getFiles() == null || step.getFiles().isEmpty()) {
            callback.onError("SCP step '" + step.getName() + "' has no files to transfer");
            return false;
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

        int totalFiles = step.getFiles().size();
        callback.onOutput(I18n.get("deploy.scp.conn", user, host, totalFiles) + "\n");

        Session session = null;
        try {
            JSch jsch = new JSch();

            boolean usingPassword = password != null && !password.isEmpty();
            boolean usingKey = keyPath != null && !keyPath.isEmpty();

            // Use key file if specified, otherwise try default keys only if no password is provided.
            if (usingKey) {
                jsch.addIdentity(keyPath);
                callback.onOutput(I18n.get("deploy.ssh.key_ok") + "\n");
            } else if (!usingPassword) {
                String userHome = System.getProperty("user.home");
                String[] defaultKeys = {
                    userHome + "/.ssh/id_rsa",
                    userHome + "/.ssh/id_ecdsa",
                    userHome + "/.ssh/id_ed25519",
                    userHome + "/.ssh/id_dsa"
                };
                boolean anyKeyAdded = false;
                for (String key : defaultKeys) {
                    File keyFile = new File(key);
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
            if (usingPassword) {
                config.put("PreferredAuthentications", "keyboard-interactive,password");
            } else {
                config.put("PreferredAuthentications", "publickey,keyboard-interactive,password");
            }
            session.setConfig(config);
            session.setTimeout(CONNECTION_TIMEOUT);

            if (usingPassword) {
                session.setPassword(password);
                session.setUserInfo(new SshUserInfo(password));
                callback.onOutput(I18n.get("deploy.ssh.password_warn") + "\n");
            } else {
                // Still set UserInfo for host key prompts (StrictHostKeyChecking=ask)
                session.setUserInfo(new SshUserInfo(null));
            }

            session.connect(CONNECTION_TIMEOUT);

            // Open SFTP channel
            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            boolean allSuccess = true;
            try {
                for (int i = 0; i < step.getFiles().size(); i++) {
                    ScpFileEntry fileEntry = step.getFiles().get(i);
                    String localPath = fileEntry.getLocal();
                    String remotePath = fileEntry.getRemote();

                    callback.onOutput("  [" + (i + 1) + "/" + totalFiles + "] " + localPath + " -> " + remotePath + "\n");

                    File localFile = new File(localPath);
                    if (!localFile.exists()) {
                        callback.onOutput("    \u2717 Local file not found: " + localPath + "\n");
                        allSuccess = false;
                        continue;
                    }

                    try {
                        // Ensure remote directory exists
                        String remoteDir = remotePath.substring(0, Math.max(remotePath.lastIndexOf('/'), 0));
                        if (!remoteDir.isEmpty()) {
                            mkdirSftp(sftpChannel, remoteDir);
                        }

                        sftpChannel.put(localPath, remotePath,
                                new ProgressMonitor(callback, step.getName(), localFile.length()),
                                ChannelSftp.OVERWRITE);
                        callback.onOutput("    \u2713 Transferred (" + formatFileSize(localFile.length()) + ")\n");
                    } catch (SftpException e) {
                        callback.onOutput("    \u2717 Transfer failed: " + e.getMessage() + "\n");
                        logger.error("SCP transfer failed: {} -> {}: {}", localPath, remotePath, e.getMessage());
                        allSuccess = false;
                    }
                }
            } finally {
                sftpChannel.disconnect();
            }

            if (allSuccess) {
                callback.onOutput(I18n.get("deploy.scp.success", step.getName(), totalFiles) + "\n");
            } else {
                callback.onOutput(I18n.get("deploy.scp.errors", step.getName()) + "\n");
            }
            return allSuccess;

        } catch (JSchException e) {
            String errorMsg = e.getMessage();
            callback.onOutput("\n" + I18n.get("deploy.scp.conn_failed", errorMsg) + "\n");
            logger.error("SCP error for {}@{}: {}", user, host, errorMsg);
            return false;
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * Recursively create remote directories via SFTP.
     */
    private void mkdirSftp(ChannelSftp channel, String dir) throws SftpException {
        String[] parts = dir.split("/");
        String current = "";
        for (String part : parts) {
            if (part.isEmpty()) continue;
            current += "/" + part;
            try {
                channel.stat(current);
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    channel.mkdir(current);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Format file size in human-readable format.
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * JSch progress monitor that forwards SCP byte-level progress to the pipeline callback.
     */
    private static class ProgressMonitor implements SftpProgressMonitor {
        private final PipelineCallback callback;
        private final String stepName;
        private final long fallbackSize;
        private long fileSize;
        private long startTime;
        private long totalTransferred;
        private long lastReported;

        ProgressMonitor(PipelineCallback callback, String stepName, long fallbackSize) {
            this.callback = callback;
            this.stepName = stepName;
            this.fallbackSize = fallbackSize;
        }

        @Override
        public void init(int op, String src, String dest, long max) {
            this.fileSize = max > 0 ? max : fallbackSize;
            this.startTime = System.currentTimeMillis();
            this.totalTransferred = 0;
            this.lastReported = 0;
        }

        @Override
        public boolean count(long count) {
            this.totalTransferred += count;
            long now = System.currentTimeMillis();
            if (now - lastReported >= 500) {
                long elapsed = now - startTime;
                long speedBps = elapsed > 0 ? totalTransferred * 1000 / elapsed : 0;
                callback.onProgress(stepName, totalTransferred, fileSize, speedBps);
                lastReported = now;
            }
            return true;
        }

        @Override
        public void end() {
            callback.onProgress(stepName, fileSize, fileSize, 0);
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
