package com.github.obhen233.compiler.service.workspace;

import com.github.obhen233.compiler.constant.Constants;
import com.github.obhen233.compiler.i18n.I18n;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;

/**
 * Project tree service - handles file tree operations
 */
@Service
public class ProjectTreeService {

    @Autowired
    private ProjectManagementService projectManagementService;

    /** Directories excluded from Package Explorer */
    public static final Set<String> EXCLUDED_DIRS = new HashSet<>(Arrays.asList(
        ".git", ".svn", ".hg", ".idea", ".vscode", ".settings", ".gradle"
    ));

    /** Lazy load directories (load children only when user expands) */
    public static final Set<String> LAZY_LOAD_DIRS = new HashSet<>(Arrays.asList(
        "node_modules", ".m2", "vendor"
    ));

    /** Maximum recursion depth to prevent StackOverflowError */
    private static final int MAX_RECURSION_DEPTH = 50;

    /**
     * Get project tree structure
     */
    public Map<String, Object> getProjectTree(String name, int depth, String path) {
        File projectDir = new File(Constants.workspacePath, name);
        if (!projectDir.exists()) return fail(I18n.get("project.notFound"));
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);

        // If path is specified, load that directory (relative to project root)
        File targetDir = projectDir;
        if (path != null && !path.isEmpty()) {
            targetDir = new File(projectDir, path);
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                return fail(I18n.get("file.notFound", path));
            }
            // Security check: ensure within project directory
            if (!targetDir.toPath().normalize().startsWith(projectDir.toPath().normalize())) {
                return fail(I18n.get("file.pathInvalid"));
            }
        }

        int maxDepth = depth > 0 ? depth : Integer.MAX_VALUE;
        result.put("tree", buildTree(targetDir, projectDir, 0, maxDepth));
        result.put("projectType", projectManagementService.detectProjectType(projectDir));
        result.put("rootPath", path != null ? path : "");
        return result;
    }

    private Map<String, Object> buildTree(File file, File projectRoot) {
        return buildTree(file, projectRoot, 0, Integer.MAX_VALUE);
    }

    private Map<String, Object> buildTree(File file, File projectRoot, int currentDepth, int maxDepth) {
        Map<String, Object> node = new HashMap<>();
        node.put("name", file.getName());
        if (file.isDirectory()) {
            String relPath = projectRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/');

            // Exclude hidden/useless directories
            if (EXCLUDED_DIRS.contains(file.getName())) {
                return null;
            }

            boolean isPackage = isJavaPackageDir(file, relPath);
            node.put("type", "directory");
            node.put("nodeKind", isPackage ? "package" : "folder");

            // Lazy load directories: mark as expandable but don't load children
            if (LAZY_LOAD_DIRS.contains(file.getName())) {
                node.put("lazy", true);
                node.put("children", Collections.emptyList());
                return node;
            }

            // Safety limit: prevent StackOverflowError for deeply nested directories
            if (currentDepth >= MAX_RECURSION_DEPTH || currentDepth >= maxDepth) {
                node.put("lazy", true);
                node.put("children", Collections.emptyList());
                return node;
            }

            List<Map<String, Object>> children = new ArrayList<>();
            File[] files = file.listFiles();
            if (files != null) {
                // Skip deep scanning of target/build directories
                if ("target".equals(file.getName()) || "build".equals(file.getName())) {
                    node.put("lazy", true);
                    node.put("children", Collections.emptyList());
                    return node;
                }

                Arrays.sort(files, (a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareTo(b.getName());
                });
                for (File f : files) {
                    if (EXCLUDED_DIRS.contains(f.getName())) continue;
                    Map<String, Object> child = buildTree(f, projectRoot, currentDepth + 1, maxDepth);
                    if (child != null) children.add(child);
                }
            }
            node.put("children", children);
        } else {
            node.put("type", "file");
            String fname = file.getName().toLowerCase();
            if (fname.endsWith(".java")) node.put("nodeKind", "java");
            else if (fname.endsWith(".jar")) node.put("nodeKind", "jar");
            else if (fname.endsWith(".xml")) node.put("nodeKind", "xml");
            else if (fname.endsWith(".properties")) node.put("nodeKind", "properties");
            else if (fname.endsWith(".gradle")) node.put("nodeKind", "gradle");
            else node.put("nodeKind", "file");
        }
        return node;
    }

    /**
     * Determine if a directory is a Java package directory
     * Conditions: under src/ path, and directory name follows Java package naming conventions
     */
    private boolean isJavaPackageDir(File dir, String relPath) {
        if (!relPath.startsWith("src/") && !relPath.equals("src")) return false;
        if (relPath.equals("src")) return false;
        // Maven/Gradle standard structure - main, java, test, etc. are not packages
        String[] structureDirs = {"src/main", "src/main/java", "src/main/resources",
                                  "src/test", "src/test/java", "src/test/resources"};
        for (String sd : structureDirs) {
            if (relPath.equals(sd)) return false;
        }
        // Under source root, and directory name follows Java identifier rules, treat as package
        boolean underSourceRoot = relPath.startsWith("src/main/java/")
                || relPath.startsWith("src/test/java/");
        // Compatible with simple project structure (directly under src/)
        if (!underSourceRoot) {
            underSourceRoot = relPath.startsWith("src/")
                    && !relPath.startsWith("src/main/")
                    && !relPath.startsWith("src/test/");
        }
        if (!underSourceRoot) return false;
        String dirName = dir.getName();
        return dirName.matches("[a-zA-Z_$][a-zA-Z0-9_$]*");
    }

    /**
     * List directory contents with pagination (for large Package Explorer directories)
     */
    public Map<String, Object> listDirectory(String name, String path, int page, int size, String sort, String order) {
        File projectDir = new File(Constants.workspacePath, name);
        if (!projectDir.exists()) return fail(I18n.get("project.notFound"));
        File targetDir = new File(projectDir, path);
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            return fail(I18n.get("file.notFound", path));
        }
        // Security check
        if (!targetDir.toPath().normalize().startsWith(projectDir.toPath().normalize())) {
            return fail(I18n.get("file.pathInvalid"));
        }

        File[] allFiles = targetDir.listFiles();
        if (allFiles == null) allFiles = new File[0];

        // Sort
        Comparator<File> comparator;
        if ("size".equals(sort)) {
            comparator = (a, b) -> Long.compare(a.length(), b.length()) * ("desc".equals(order) ? -1 : 1);
        } else if ("modified".equals(sort)) {
            comparator = (a, b) -> Long.compare(a.lastModified(), b.lastModified()) * ("desc".equals(order) ? -1 : 1);
        } else {
            comparator = (a, b) -> {
                // Directories first
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName()) * ("desc".equals(order) ? -1 : 1);
            };
        }
        Arrays.sort(allFiles, comparator);

        int total = allFiles.length;
        int totalPages = (total + size - 1) / size;
        int start = page * size;
        int end = Math.min(start + size, total);

        List<Map<String, Object>> items = new ArrayList<>();
        if (start < total) {
            for (int i = start; i < end; i++) {
                File f = allFiles[i];
                Map<String, Object> item = new HashMap<>();
                item.put("name", f.getName());
                item.put("type", f.isDirectory() ? "directory" : "file");
                item.put("size", f.length());
                item.put("modified", f.lastModified());
                if (f.isDirectory()) {
                    // Count subdirectories and files
                    File[] children = f.listFiles();
                    item.put("childrenCount", children != null ? children.length : 0);
                } else {
                    String fname = f.getName().toLowerCase();
                    item.put("nodeKind", fname.endsWith(".java") ? "java"
                            : fname.endsWith(".jar") ? "jar"
                            : fname.endsWith(".xml") ? "xml"
                            : fname.endsWith(".properties") ? "properties"
                            : fname.endsWith(".gradle") ? "gradle" : "file");
                }
                items.add(item);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("path", path);
        result.put("page", page);
        result.put("size", size);
        result.put("totalElements", total);
        result.put("totalPages", totalPages);
        result.put("hasNext", page < totalPages - 1);
        result.put("hasPrevious", page > 0);
        result.put("items", items);
        return result;
    }

    Map<String, Object> fail(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("message", msg);
        return r;
    }
}
