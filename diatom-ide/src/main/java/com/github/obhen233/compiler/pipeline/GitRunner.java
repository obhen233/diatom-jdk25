package com.github.obhen233.compiler.pipeline;

import com.github.obhen233.core.pipeline.PipelineCallback;
import com.github.obhen233.core.pipeline.PipelineRunner;
import com.github.obhen233.core.pipeline.PipelineStep;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.api.TagCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;

/**
 * PipelineRunner for Git operations using JGit.
 * Supports the "git" action type.
 * Registered as a @Bean in IdeSpringConfig, automatically collected
 * by the starter's PipelineConfiguration.
 */
public class GitRunner implements PipelineRunner {

    private static final Logger logger = LoggerFactory.getLogger(GitRunner.class);

    @Override
    public String getActionType() {
        return "git";
    }

    @Override
    public boolean execute(PipelineStep step, Map<String, String> variables, PipelineCallback callback) throws Exception {
        String command = step.getCommand();
        if (command == null || command.trim().isEmpty()) {
            callback.onError("Git step '" + step.getName() + "' has no command");
            return false;
        }

        command = command.trim();
        callback.onOutput("$ git " + command + "\n");
        logger.info("Git step '{}': git {}", step.getName(), command);

        // Determine the git operation and arguments
        String op;
        String args;
        int spaceIdx = command.indexOf(' ');
        if (spaceIdx < 0) {
            op = command.toLowerCase();
            args = "";
        } else {
            op = command.substring(0, spaceIdx).toLowerCase();
            args = command.substring(spaceIdx + 1).trim();
        }

        // Determine working directory
        String projectDir = variables.get("PROJECT_DIR");
        File workDir = projectDir != null ? new File(projectDir) : new File(".");

        // Build credentials from variables
        CredentialsProvider credentialsProvider = null;
        String gitUser = variables.get("GIT_USER");
        String gitPassword = variables.get("GIT_PASSWORD");
        if (gitUser != null && gitPassword != null && !gitUser.isEmpty() && !gitPassword.isEmpty()) {
            credentialsProvider = new UsernamePasswordCredentialsProvider(gitUser, gitPassword);
        }

        switch (op) {
            case "clone":
                return handleClone(args, workDir, credentialsProvider, callback);
            case "checkout":
                return handleCheckout(args, workDir, callback);
            case "pull":
                return handlePull(args, workDir, credentialsProvider, callback);
            case "push":
                return handlePush(args, workDir, credentialsProvider, callback);
            case "commit":
                return handleCommit(args, workDir, callback);
            case "tag":
                return handleTag(args, workDir, callback);
            case "branch":
                return handleBranch(args, workDir, callback);
            case "status":
                return handleStatus(workDir, callback);
            default:
                callback.onError("Unknown git operation: " + op + ". Supported: clone, checkout, pull, push, commit, tag, branch, status");
                return false;
        }
    }

    private boolean handleClone(String args, File workDir, CredentialsProvider cp, PipelineCallback callback) {
        try {
            // Parse URL and target dir from args
            // format: "url [targetDir]"
            String url;
            String targetDir = null;
            int spaceIdx = args.indexOf(' ');
            if (spaceIdx < 0) {
                url = args;
            } else {
                url = args.substring(0, spaceIdx);
                targetDir = args.substring(spaceIdx + 1).trim();
            }

            CloneCommand cloneCmd = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(targetDir != null ? new File(workDir, targetDir) : workDir);
            if (cp != null) {
                cloneCmd.setCredentialsProvider(cp);
            }

            callback.onOutput("  Cloning " + url + " ...\n");
            Git result = cloneCmd.call();
            result.close();
            callback.onOutput("  Clone complete\n");
            return true;
        } catch (Exception e) {
            callback.onOutput("✗ Clone failed: " + e.getMessage() + "\n");
            logger.error("Git clone failed", e);
            return false;
        }
    }

    private boolean handleCheckout(String args, File workDir, PipelineCallback callback) {
        try (Git git = openGit(workDir)) {
            String branchName = args;
            boolean create = false;

            // Support "-b branchname" syntax for create-and-switch
            if (branchName.startsWith("-b ")) {
                create = true;
                branchName = branchName.substring(3).trim();
            }

            CheckoutCommand checkout = git.checkout();
            if (create) {
                checkout.setCreateBranch(true);
                checkout.setName(branchName);
                // Set start point to current HEAD if creating
                checkout.setStartPoint(Constants.HEAD);
            } else {
                checkout.setName(branchName);
            }
            checkout.call();
            callback.onOutput("  Checked out '" + branchName + "'\n");
            return true;
        } catch (Exception e) {
            callback.onOutput("✗ Checkout failed: " + e.getMessage() + "\n");
            logger.error("Git checkout failed", e);
            return false;
        }
    }

    private boolean handlePull(String args, File workDir, CredentialsProvider cp, PipelineCallback callback) {
        try (Git git = openGit(workDir)) {
            PullCommand pull = git.pull();
            if (cp != null) {
                pull.setCredentialsProvider(cp);
            }
            if (args != null && !args.isEmpty()) {
                String[] parts = args.split("\\s+");
                if (parts.length >= 1) pull.setRemote(parts[0]);
                if (parts.length >= 2) pull.setRemoteBranchName(parts[1]);
            }
            org.eclipse.jgit.api.PullResult result = pull.call();
            boolean success = result.isSuccessful();
            if (success) {
                callback.onOutput("  Pull successful\n");
            } else {
                callback.onOutput("  Pull completed with conflicts or no changes\n");
            }
            return success;
        } catch (Exception e) {
            callback.onOutput("✗ Pull failed: " + e.getMessage() + "\n");
            logger.error("Git pull failed", e);
            return false;
        }
    }

    private boolean handlePush(String args, File workDir, CredentialsProvider cp, PipelineCallback callback) {
        try (Git git = openGit(workDir)) {
            PushCommand push = git.push();
            if (cp != null) {
                push.setCredentialsProvider(cp);
            }
            if (args != null && !args.isEmpty()) {
                String[] parts = args.split("\\s+");
                if (parts.length >= 1) push.setRemote(parts[0]);
                if (parts.length >= 2) push.add(parts[1]);
            } else {
                push.add("HEAD");
            }
            push.call();
            callback.onOutput("  Push successful\n");
            return true;
        } catch (Exception e) {
            callback.onOutput("✗ Push failed: " + e.getMessage() + "\n");
            logger.error("Git push failed", e);
            return false;
        }
    }

    private boolean handleCommit(String args, File workDir, PipelineCallback callback) {
        try (Git git = openGit(workDir)) {
            String message = args;
            // Strip quotes if present
            if (message.startsWith("-m ")) {
                message = message.substring(3).trim();
            }
            if ((message.startsWith("\"") && message.endsWith("\"")) ||
                (message.startsWith("'") && message.endsWith("'"))) {
                message = message.substring(1, message.length() - 1);
            }

            // Stage all changes first
            git.add().addFilepattern(".").call();
            org.eclipse.jgit.api.CommitCommand commit = git.commit()
                    .setMessage(message)
                    .setAll(true);

            // Set author from variables if available
            String authorName = System.getProperty("user.name");
            String authorEmail = authorName + "@localhost";
            commit.setAuthor(authorName, authorEmail);

            org.eclipse.jgit.revwalk.RevCommit rev = commit.call();
            callback.onOutput("  Committed: " + rev.getId().abbreviate(8).name() + " - " + message + "\n");
            return true;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("nothing to commit")) {
                callback.onOutput("  Nothing to commit, working tree clean\n");
                return true;
            }
            callback.onOutput("✗ Commit failed: " + msg + "\n");
            logger.error("Git commit failed", e);
            return false;
        }
    }

    private boolean handleTag(String args, File workDir, PipelineCallback callback) {
        try (Git git = openGit(workDir)) {
            String tagName = args;
            TagCommand tag = git.tag()
                    .setName(tagName)
                    .setMessage("Release " + tagName);
            tag.call();
            callback.onOutput("  Tag created: " + tagName + "\n");
            return true;
        } catch (Exception e) {
            callback.onOutput("✗ Tag failed: " + e.getMessage() + "\n");
            logger.error("Git tag failed", e);
            return false;
        }
    }

    private boolean handleBranch(String args, File workDir, PipelineCallback callback) {
        try (Git git = openGit(workDir)) {
            String branchName = args;
            CreateBranchCommand branch = git.branchCreate()
                    .setName(branchName)
                    .setStartPoint(Constants.HEAD);
            branch.call();
            callback.onOutput("  Branch created: " + branchName + "\n");
            return true;
        } catch (Exception e) {
            callback.onOutput("✗ Branch creation failed: " + e.getMessage() + "\n");
            logger.error("Git branch failed", e);
            return false;
        }
    }

    private boolean handleStatus(File workDir, PipelineCallback callback) {
        try (Git git = openGit(workDir)) {
            org.eclipse.jgit.api.Status status = git.status().call();
            StringBuilder sb = new StringBuilder();
            sb.append("  Branch: ").append(git.getRepository().getBranch()).append("\n");
            sb.append("  Modified: ").append(status.getModified().size()).append("\n");
            sb.append("  Added: ").append(status.getAdded().size()).append("\n");
            sb.append("  Removed: ").append(status.getRemoved().size()).append("\n");
            sb.append("  Untracked: ").append(status.getUntracked().size()).append("\n");
            sb.append("  Uncommitted: ").append(status.getUncommittedChanges().size()).append("\n");
            callback.onOutput(sb.toString());
            return true;
        } catch (Exception e) {
            callback.onOutput("✗ Status failed: " + e.getMessage() + "\n");
            logger.error("Git status failed", e);
            return false;
        }
    }

    /**
     * Open an existing Git repository in the given directory.
     * Searches parent directories for .git folder.
     */
    private Git openGit(File workDir) throws Exception {
        RepositoryBuilder builder = new RepositoryBuilder()
                .findGitDir(workDir);
        if (builder.getGitDir() == null) {
            throw new Exception("Not a git repository (or any parent): " + workDir.getAbsolutePath());
        }
        Repository repository = builder.build();
        return new Git(repository);
    }
}
