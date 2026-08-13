package com.github.obhen233.compiler.service;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.entity.IdeSetting;
import com.github.obhen233.compiler.repository.IdeSettingRepository;
import com.github.obhen233.compiler.i18n.I18n;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.*;
import java.util.*;

/**
 * 版本控制服务：Git (JGit) + SVN (命令行)
 */
@Service
public class VcsService {

    private static final Logger logger = LoggerFactory.getLogger(VcsService.class);

    @Resource
    private IdeSettingRepository settingRepo;

    // ==================== Git 操作 ====================

    private File getProjectDir(String projectName) {
        return new File(Constants.workspacePath, projectName);
    }

    /** 检测项目是否已初始化 Git */
    public Map<String, Object> gitStatus(String projectName) {
        Map<String, Object> r = new HashMap<>();
        File dir = getProjectDir(projectName);
        File gitDir = new File(dir, ".git");
        if (!gitDir.exists()) {
            r.put("initialized", false);
            return r;
        }
        try (Git git = Git.open(dir)) {
            r.put("initialized", true);
            Status status = git.status().call();
            r.put("branch", git.getRepository().getBranch());
            r.put("added", status.getAdded());
            r.put("modified", status.getModified());
            r.put("untracked", status.getUntracked());
            r.put("removed", status.getRemoved());
            r.put("changed", status.getChanged());
            r.put("conflicting", status.getConflicting());
            r.put("clean", status.isClean());

            // 远程信息
            StoredConfig config = git.getRepository().getConfig();
            String remoteUrl = config.getString("remote", "origin", "url");
            r.put("remoteUrl", remoteUrl != null ? remoteUrl : "");

            // 分支列表
            List<String> branches = new ArrayList<>();
            for (Ref ref : git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()) {
                branches.add(ref.getName().replaceFirst("^refs/heads/", "").replaceFirst("^refs/remotes/", ""));
            }
            r.put("branches", branches);
        } catch (Exception e) {
            r.put("error", e.getMessage());
        }
        return r;
    }

    /** git init */
    public Map<String, Object> gitInit(String projectName) {
        try {
            Git.init().setDirectory(getProjectDir(projectName)).call().close();
            return ok(I18n.get("vcs.gitInitialized"));
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git clone */
    public Map<String, Object> gitClone(String projectName, String url, String username, String password) {
        try {
            File dir = getProjectDir(projectName);
            if (!dir.exists()) dir.mkdirs();
            CloneCommand cmd = Git.cloneRepository().setURI(url).setDirectory(dir);
            if (username != null && !username.isEmpty()) {
                cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password != null ? password : ""));
            }
            cmd.call().close();
            return ok(I18n.get("vcs.cloneComplete"));
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git add (添加指定文件到暂存区) */
    public Map<String, Object> gitAdd(String projectName, List<String> paths) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            AddCommand cmd = git.add();
            if (paths == null || paths.isEmpty()) {
                // add all
                cmd.addFilepattern(".");
            } else {
                for (String p : paths) {
                    cmd.addFilepattern(p);
                }
            }
            cmd.call();
            Map<String, Object> r = ok(I18n.get("vcs.addedToStaging"));
            r.put("paths", paths == null || paths.isEmpty() ? Collections.singletonList(".") : paths);
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git reset HEAD <paths> (取消暂存) */
    public Map<String, Object> gitUnstage(String projectName, List<String> paths) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            if (paths == null || paths.isEmpty()) {
                // Unstage all: reset HEAD
                git.reset().setRef("HEAD").call();
            } else {
                for (String p : paths) {
                    git.reset().addPath(p).call();
                }
            }
            return ok(I18n.get("vcs.unstaged"));
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git checkout -- <paths> (丢弃工作区更改) */
    public Map<String, Object> gitDiscard(String projectName, List<String> paths) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            if (paths == null || paths.isEmpty()) {
                return fail("No paths specified");
            }
            for (String p : paths) {
                git.checkout().addPath(p).call();
            }
            return ok(I18n.get("vcs.discardSuccess"));
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 获取指定文件的 diff 内容，同时返回旧版和当前版本内容 */
    public Map<String, Object> gitFileDiff(String projectName, String filePath) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            Repository repo = git.getRepository();
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // 读取旧版本内容 (HEAD)
            String oldContent = "";
            try {
                ObjectId headId = repo.resolve("HEAD^{tree}");
                if (headId != null) {
                    org.eclipse.jgit.treewalk.TreeWalk tw = org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, filePath, headId);
                    if (tw != null) {
                        ObjectLoader loader = repo.open(tw.getObjectId(0));
                        oldContent = new String(loader.getBytes(), "UTF-8");
                        tw.close();
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to read HEAD content for {}: {}", filePath, e.getMessage());
            }

            // 读取当前工作区版本内容
            String newContent = "";
            File workingFile = new File(getProjectDir(projectName), filePath);
            if (workingFile.exists()) {
                newContent = new String(java.nio.file.Files.readAllBytes(workingFile.toPath()), "UTF-8");
            }

            // 生成 unified diff
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repo);
                formatter.setDiffComparator(RawTextComparator.DEFAULT);
                formatter.setDetectRenames(true);
                formatter.setPathFilter(PathFilter.create(filePath));

                ObjectId headTree = repo.resolve("HEAD^{tree}");
                if (headTree != null) {
                    try (ObjectReader reader = repo.newObjectReader()) {
                        CanonicalTreeParser oldTree = new CanonicalTreeParser();
                        oldTree.reset(reader, headTree);
                        List<DiffEntry> entries = formatter.scan(oldTree, new FileTreeIterator(repo));
                        for (DiffEntry entry : entries) formatter.format(entry);
                    }
                }

                if (out.size() == 0) {
                    List<DiffEntry> entries = formatter.scan(new DirCacheIterator(repo.readDirCache()), new FileTreeIterator(repo));
                    for (DiffEntry entry : entries) formatter.format(entry);
                }
            }

            String diff = out.toString("UTF-8");
            if (diff.isEmpty()) diff = buildGitFallbackDiff(projectName, filePath);

            Map<String, Object> r = ok(null);
            r.put("diff", diff);
            r.put("oldContent", oldContent);
            r.put("newContent", newContent);
            r.put("file", filePath);
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    private String buildGitFallbackDiff(String projectName, String filePath) throws IOException {
        File workingFile = new File(getProjectDir(projectName), filePath);
        if (!workingFile.exists()) return "";
        String content = new String(java.nio.file.Files.readAllBytes(workingFile.toPath()), "UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(filePath).append("\n");
        for (String line : content.split("\\r?\\n", -1)) {
            sb.append("+").append(line).append("\n");
        }
        return sb.toString();
    }

    /** git add + commit */
    public Map<String, Object> gitCommit(String projectName, String message, boolean addAll) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            if (addAll) {
                git.add().addFilepattern(".").call();
                // 也处理删除的文件
                git.add().addFilepattern(".").setUpdate(true).call();
            }
            RevCommit commit = git.commit().setMessage(message).call();
            Map<String, Object> r = ok(I18n.get("vcs.commitSuccess"));
            r.put("commitId", commit.getName());
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /**
     * Custom CredentialsProvider that delegates to git's credential helper.
     * This allows JGit to use system git credentials (Windows Credential Manager, etc.)
     */
    private static class GitCredentialProvider extends CredentialsProvider {
        private static final String GIT_CREDENTIAL_HELPER = "git credential fill";

        @Override
        public boolean isInteractive() {
            return false;
        }

        @Override
        public boolean supports(CredentialItem... items) {
            return true;
        }

        @Override
        public boolean get(URIish uri, CredentialItem... items) throws UnsupportedOperationException {
            try {
                String protocol = uri.getScheme() != null ? uri.getScheme() : "https";
                String host = uri.getHost();
                int port = uri.getPort();
                String path = uri.getPath();
                String url = protocol + "://" + host + (port > 0 ? ":" + port : "") + path;

                // Call git credential fill to get credentials
                ProcessBuilder pb = new ProcessBuilder();
                boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
                if (isWin) {
                    pb.command("cmd", "/c", "echo url=" + url + " & " + GIT_CREDENTIAL_HELPER);
                } else {
                    pb.command("/bin/sh", "-c", "echo url=" + url + " | " + GIT_CREDENTIAL_HELPER);
                }
                pb.redirectErrorStream(true);
                Process process = pb.start();

                StringBuilder input = new StringBuilder();
                input.append("url=").append(url).append("\n");
                if (uri.getUser() != null && !uri.getUser().isEmpty()) {
                    input.append("username=").append(uri.getUser()).append("\n");
                }

                process.getOutputStream().write(input.toString().getBytes("UTF-8"));
                process.getOutputStream().flush();
                process.getOutputStream().close();

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                process.waitFor();

                String result = output.toString();
                String username = extractCredential(result, "username");
                String password = extractCredential(result, "password");

                for (CredentialItem item : items) {
                    if (item instanceof CredentialItem.Username) {
                        ((CredentialItem.Username) item).setValue(username != null ? username : "");
                    } else if (item instanceof CredentialItem.Password) {
                        ((CredentialItem.Password) item).setValue(password != null ? password.toCharArray() : new char[0]);
                    }
                }

                return username != null && !username.isEmpty();
            } catch (Exception e) {
                return false;
            }
        }

        private String extractCredential(String input, String key) {
            for (String line : input.split("\n")) {
                if (line.startsWith(key + "=")) {
                    return line.substring(key.length() + 1);
                }
            }
            return null;
        }
    }

    /** git push - uses JGit with custom credential provider that uses git's credential helper */
    public Map<String, Object> gitPush(String projectName, String username, String password, boolean force) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            PushCommand cmd = git.push();
            // Use custom credential provider that delegates to git's credential helper
            cmd.setCredentialsProvider(new GitCredentialProvider());
            if (force) cmd.setForce(true);
            Iterable<PushResult> results = cmd.call();
            StringBuilder sb = new StringBuilder();
            for (PushResult pr : results) {
                for (RemoteRefUpdate ru : pr.getRemoteUpdates()) {
                    sb.append(ru.getRemoteName()).append(": ").append(ru.getStatus()).append("\n");
                }
            }
            Map<String, Object> r = ok(I18n.get("vcs.pushComplete"));
            r.put("detail", sb.toString().trim());
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git pull - uses JGit with custom credential provider that uses git's credential helper */
    public Map<String, Object> gitPull(String projectName, String username, String password) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            PullCommand cmd = git.pull();
            // Use custom credential provider that delegates to git's credential helper
            cmd.setCredentialsProvider(new GitCredentialProvider());
            PullResult result = cmd.call();
            Map<String, Object> r = ok(I18n.get("vcs.pullComplete"));
            r.put("fetchResult", result.getFetchResult() != null ? result.getFetchResult().getMessages() : "");
            r.put("mergeStatus", result.getMergeResult() != null ? result.getMergeResult().getMergeStatus().toString() : "");
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git merge */
    public Map<String, Object> gitMerge(String projectName, String branchName) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            Repository repo = git.getRepository();
            ObjectId branchId = repo.resolve(branchName);
            if (branchId == null) return fail(I18n.get("vcs.branchNotFound", branchName));
            MergeResult result = git.merge().include(branchId).call();
            Map<String, Object> r = ok("Merge: " + result.getMergeStatus());
            r.put("mergeStatus", result.getMergeStatus().toString());
            if (result.getConflicts() != null) {
                r.put("conflicts", result.getConflicts().keySet());
            }
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git checkout (切换分支) */
    public Map<String, Object> gitCheckout(String projectName, String branchName, boolean create) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            // 处理远程分支: origin/xxx -> 创建本地跟踪分支 xxx
            if (branchName.startsWith("origin/")) {
                String localName = branchName.substring("origin/".length());
                // 检查本地分支是否已存在
                boolean localExists = git.branchList().call().stream()
                        .anyMatch(ref -> ref.getName().equals("refs/heads/" + localName));
                if (!localExists) {
                    git.checkout()
                            .setCreateBranch(true)
                            .setName(localName)
                            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                            .setStartPoint(branchName)
                            .call();
                    return ok(I18n.get("vcs.branchCreated", branchName, localName));
                }
                // 本地已存在，直接切换
                branchName = localName;
            }
            CheckoutCommand cmd = git.checkout().setName(branchName);
            if (create) cmd.setCreateBranch(true);
            cmd.call();
            return ok(I18n.get("vcs.branchSwitched", branchName));
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git cherry-pick */
    public Map<String, Object> gitCherryPick(String projectName, String commitId) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            ObjectId id = git.getRepository().resolve(commitId);
            if (id == null) return fail(I18n.get("vcs.commitNotFound", commitId));
            RevWalk walk = new RevWalk(git.getRepository());
            RevCommit commit = walk.parseCommit(id);
            CherryPickResult result = git.cherryPick().include(commit).call();
            walk.close();
            Map<String, Object> r = ok("Cherry-pick: " + result.getStatus());
            r.put("status", result.getStatus().toString());
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git log */
    public Map<String, Object> gitLog(String projectName, int maxCount, int skip) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            LogCommand cmd = git.log().setMaxCount(maxCount).setSkip(skip);
            List<Map<String, Object>> commits = new ArrayList<>();
            for (RevCommit c : cmd.call()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getName());
                m.put("shortId", c.abbreviate(7).name());
                m.put("message", c.getFullMessage());
                m.put("author", c.getAuthorIdent().getName());
                m.put("email", c.getAuthorIdent().getEmailAddress());
                m.put("time", c.getAuthorIdent().getWhen().getTime());
                m.put("timeStr", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(c.getAuthorIdent().getWhen()));
                commits.add(m);
            }
            Map<String, Object> r = ok(null);
            r.put("commits", commits);
            r.put("branch", git.getRepository().getBranch());
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** git diff (两个 commit 之间或工作区变更) */
    public Map<String, Object> gitDiff(String projectName, String oldRef, String newRef) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            Repository repo = git.getRepository();
            List<Map<String, String>> diffs = new ArrayList<>();

            if (oldRef == null || oldRef.isEmpty()) {
                // 工作区 vs HEAD
                List<DiffEntry> entries = git.diff().call();
                for (DiffEntry d : entries) {
                    Map<String, String> m = new HashMap<>();
                    m.put("changeType", d.getChangeType().name());
                    m.put("oldPath", d.getOldPath());
                    m.put("newPath", d.getNewPath());
                    diffs.add(m);
                }
            } else {
                AbstractTreeIterator oldTree = prepareTreeParser(repo, oldRef);
                AbstractTreeIterator newTree = (newRef != null && !newRef.isEmpty())
                        ? prepareTreeParser(repo, newRef)
                        : new EmptyTreeIterator();
                List<DiffEntry> entries = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
                for (DiffEntry d : entries) {
                    Map<String, String> m = new HashMap<>();
                    m.put("changeType", d.getChangeType().name());
                    m.put("oldPath", d.getOldPath());
                    m.put("newPath", d.getNewPath());
                    diffs.add(m);
                }
            }
            Map<String, Object> r = ok(null);
            r.put("diffs", diffs);
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 获取某个 commit 中某个文件的内容 */
    public Map<String, Object> gitShowFile(String projectName, String commitId, String filePath) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            Repository repo = git.getRepository();
            ObjectId id = repo.resolve(commitId);
            if (id == null) return fail("Commit 不存在");
            RevWalk walk = new RevWalk(repo);
            RevCommit commit = walk.parseCommit(id);
            org.eclipse.jgit.treewalk.TreeWalk tw = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                    repo, filePath, commit.getTree());
            if (tw == null) return fail(I18n.get("vcs.fileNotFound", filePath));
            ObjectLoader loader = repo.open(tw.getObjectId(0));
            String content = new String(loader.getBytes(), "UTF-8");
            walk.close();
            Map<String, Object> r = ok(null);
            r.put("content", content);
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** 设置远程仓库地址 */
    public Map<String, Object> gitSetRemote(String projectName, String url) {
        try (Git git = Git.open(getProjectDir(projectName))) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", url);
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.save();
            return ok(I18n.get("vcs.remoteSet", url));
        } catch (Exception e) {
            return fail(e);
        }
    }

    private AbstractTreeIterator prepareTreeParser(Repository repo, String ref) throws Exception {
        ObjectId id = repo.resolve(ref + "^{tree}");
        if (id == null) throw new IllegalArgumentException("无法解析: " + ref);
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (ObjectReader reader = repo.newObjectReader()) {
            parser.reset(reader, id);
        }
        return parser;
    }

    // ==================== SVN 操作 (命令行) ====================

    private String getSvnPath() {
        return settingRepo.findById("svnPath").map(IdeSetting::getValue).orElse("svn");
    }

    public Map<String, Object> svnExec(String projectName, String... args) {
        String svn = getSvnPath();
        if (svn == null || svn.isEmpty()) svn = "svn";
        File dir = getProjectDir(projectName);
        List<String> cmd = new ArrayList<>();
        cmd.add(svn);
        Collections.addAll(cmd, args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir)
                    .redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            int code = p.waitFor();
            Map<String, Object> r = new HashMap<>();
            r.put("success", code == 0);
            r.put("output", sb.toString().trim());
            r.put("exitCode", code);
            return r;
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** svn add (添加文件到版本控制) */
    public Map<String, Object> svnAdd(String projectName, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return svnExec(projectName, "add", "--force", ".");
        }
        List<String> args = new ArrayList<>();
        args.add("add");
        args.addAll(paths);
        return svnExec(projectName, args.toArray(new String[0]));
    }

    public Map<String, Object> svnCheckout(String projectName, String url, String username, String password) {
        List<String> args = new ArrayList<>(Arrays.asList("checkout", url, "."));
        if (username != null && !username.isEmpty()) {
            args.addAll(Arrays.asList("--username", username));
            if (password != null) args.addAll(Arrays.asList("--password", password));
        }
        args.add("--non-interactive");
        return svnExec(projectName, args.toArray(new String[0]));
    }

    public Map<String, Object> svnCommit(String projectName, String message) {
        return svnExec(projectName, "commit", "-m", message);
    }

    public Map<String, Object> svnUpdate(String projectName) {
        return svnExec(projectName, "update");
    }

    public Map<String, Object> svnLog(String projectName, int limit) {
        return svnExec(projectName, "log", "--limit", String.valueOf(limit), "--xml");
    }

    public Map<String, Object> svnStatus(String projectName) {
        Map<String, Object> r = svnExec(projectName, "status");
        String output = (String) r.getOrDefault("output", "");
        List<String> modified = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> untracked = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> conflicting = new ArrayList<>();
        List<String> clean = new ArrayList<>();

        if (output != null && !output.isEmpty()) {
            for (String line : output.split("\\r?\\n")) {
                if (line == null || line.isEmpty()) continue;
                char status = line.charAt(0);
                String path = line.length() > 7 ? line.substring(7).trim() : "";
                if (path.isEmpty()) continue;
                switch (status) {
                    case 'M':
                        modified.add(path);
                        break;
                    case 'A':
                        added.add(path);
                        break;
                    case '?':
                        untracked.add(path);
                        break;
                    case 'D':
                    case '!':
                        removed.add(path);
                        break;
                    case 'C':
                        conflicting.add(path);
                        break;
                    default:
                        clean.add(path);
                        break;
                }
            }
        }

        r.put("initialized", true);
        r.put("vcsType", "svn");
        r.put("modified", modified);
        r.put("added", added);
        r.put("untracked", untracked);
        r.put("removed", removed);
        r.put("conflicting", conflicting);
        r.put("clean", modified.isEmpty() && added.isEmpty() && untracked.isEmpty() && removed.isEmpty() && conflicting.isEmpty());
        r.put("cleanFiles", clean);
        return r;
    }

    public Map<String, Object> svnFileDiff(String projectName, String filePath) {
        Map<String, Object> r = svnExec(projectName, "diff", filePath);
        r.put("file", filePath);
        r.put("diff", r.getOrDefault("output", ""));

        // 获取 SVN BASE 版本内容 (svn cat)
        String oldContent = "";
        Map<String, Object> catResult = svnExec(projectName, "cat", filePath);
        if (Boolean.TRUE.equals(catResult.get("success"))) {
            oldContent = (String) catResult.getOrDefault("output", "");
        }
        r.put("oldContent", oldContent);

        // 获取当前工作区版本内容
        String newContent = "";
        File workingFile = new File(getProjectDir(projectName), filePath);
        if (workingFile.exists()) {
            try {
                newContent = new String(java.nio.file.Files.readAllBytes(workingFile.toPath()), "UTF-8");
            } catch (IOException e) {
                logger.debug("Failed to read working file for SVN diff: {}", e.getMessage());
            }
        }
        r.put("newContent", newContent);

        return r;
    }

    public Map<String, Object> svnDiff(String projectName) {
        return svnExec(projectName, "diff");
    }

    /**
     * Check if a specific file is tracked in git (i.e., exists in HEAD or staged).
     * Returns true if the project has a .git dir AND the file is tracked.
     * Returns false if the project has no git repo, or the file is untracked/new.
     */
    public boolean isFileTrackedInGit(String projectName, String relativePath) {
        if (projectName == null || relativePath == null) return false;
        File projectDir = getProjectDir(projectName);
        File gitDir = new File(projectDir, ".git");
        if (!gitDir.exists()) return false;

        try (Git git = Git.open(projectDir)) {
            Repository repo = git.getRepository();
            // Check if HEAD exists (has commits)
            ObjectId headId = repo.resolve("HEAD^{tree}");
            if (headId == null) return false; // No commits yet → nothing is tracked

            // Check if file exists in HEAD tree
            try (org.eclipse.jgit.treewalk.TreeWalk tw = new org.eclipse.jgit.treewalk.TreeWalk(repo)) {
                tw.addTree(headId);
                tw.setRecursive(true);
                tw.setFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(relativePath));
                if (tw.next()) {
                    return true; // File found in HEAD → tracked
                }
            }

            // Also check if staged (added but not yet committed)
            Status status = git.status().call();
            return status.getAdded().contains(relativePath);
        } catch (Exception e) {
            logger.warn("Failed to check git tracking for {}/{}: {}", projectName, relativePath, e.getMessage());
            return false;
        }
    }

    // ==================== 工具 ====================

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        if (msg != null) r.put("message", msg);
        return r;
    }

    private static Map<String, Object> fail(Exception e) {
        return fail(e != null ? e.getMessage() : null);
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", msg);
        return r;
    }
}
