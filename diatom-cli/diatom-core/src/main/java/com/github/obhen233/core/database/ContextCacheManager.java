package com.github.obhen233.core.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.entity.ProjectContextEntity;
import com.github.obhen233.core.database.entity.WorkspaceContextEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

import com.github.obhen233.util.JsonUtils;

/**
 * Manages project context caching in SQLite via Hibernate.
 * Reduces memory footprint by storing indexed context on disk.
 */
public class ContextCacheManager {
    private static final Logger logger = LoggerFactory.getLogger(ContextCacheManager.class);
    private static final ObjectMapper mapper = JsonUtils.getMapper();

    private final SessionFactory sf;

    public ContextCacheManager(DatabaseManager db) {
        this.sf = db != null ? db.getSessionFactory() : null;
    }

    /**
     * Get or create a default workspace for the given project path
     */
    private Long getOrCreateWorkspace(String projectPath) {
        // Extract workspace root from project path (use parent directory as workspace)
        String workspaceRoot = new java.io.File(projectPath).getParent();
        if (workspaceRoot == null) {
            workspaceRoot = projectPath;
        }
        // Use the directory name (not full path) as the workspace display name
        String workspaceName = new java.io.File(workspaceRoot).getName();
        if (workspaceName == null || workspaceName.isEmpty()) {
            workspaceName = workspaceRoot;
        }

        long now = System.currentTimeMillis();
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            WorkspaceContextEntity existing = session.createQuery(
                "FROM WorkspaceContextEntity WHERE rootPath = :rootPath", WorkspaceContextEntity.class)
                .setParameter("rootPath", workspaceRoot)
                .uniqueResult();

            if (existing != null) {
                session.getTransaction().commit();
                return existing.getId();
            }

            WorkspaceContextEntity entity = new WorkspaceContextEntity();
            entity.setName(workspaceName);
            entity.setRootPath(workspaceRoot);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            session.persist(entity);
            session.getTransaction().commit();
            return entity.getId();
        } catch (Exception e) {
            logger.warn("Failed to get or create workspace via Hibernate", e);
        }
        return null;
    }

    /**
     * Save or update project context cache
     * Only writes if the data has actually changed (using hash comparison)
     */
    public void saveContext(String projectPath, String projectName, String projectType,
                           Map<String, Object> contextData, Map<String, String> fileIndex) {
        // 计算当前数据的哈希值
        String currentHash = computeHash(contextData, fileIndex);

        // 检查缓存是否有效且数据未变化
        if (isCacheValid(projectPath, Long.MAX_VALUE)) {
            CachedContext existing = loadContext(projectPath);
            if (existing != null) {
                String existingHash = computeHash(existing.contextData, existing.fileIndex);
                if (currentHash.equals(existingHash)) {
                    logger.debug("Context cache unchanged, skipping write for: {}", projectPath);
                    return;  // 数据未变化，跳过写入
                }
            }
        }

        // Get or create workspace for this project
        Long workspaceId = getOrCreateWorkspace(projectPath);
        long now = System.currentTimeMillis();

        try (Session session = sf.openSession()) {
            session.beginTransaction();
            ProjectContextEntity existing = session.createQuery(
                "FROM ProjectContextEntity WHERE projectPath = :path", ProjectContextEntity.class)
                .setParameter("path", projectPath)
                .uniqueResult();

            if (existing == null) {
                existing = new ProjectContextEntity();
                existing.setProjectPath(projectPath);
                existing.setCreatedAt(now);
            }
            existing.setWorkspaceId(workspaceId);
            existing.setProjectName(projectName);
            existing.setProjectType(projectType);
            existing.setIndexedAt(now);
            existing.setContextData(serialize(contextData));
            existing.setFileIndex(serialize(fileIndex));
            existing.setUpdatedAt(now);
            session.merge(existing);
            session.getTransaction().commit();
            logger.info("Saved context cache for: {}", projectPath);
        } catch (Exception e) {
            logger.warn("Failed to save context via Hibernate", e);
        }
    }

    /**
     * Compute a simple hash of the context data to detect changes
     */
    private String computeHash(Map<String, Object> contextData, Map<String, String> fileIndex) {
        try {
            String data = serialize(contextData) + "|" + serialize(fileIndex);
            // 使用简单的哈希而非加密哈希（性能考虑）
            return String.valueOf(data.hashCode());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Load project context from cache
     */
    public CachedContext loadContext(String projectPath) {
        try (Session session = sf.openSession()) {
            ProjectContextEntity entity = session.createQuery(
                "FROM ProjectContextEntity WHERE projectPath = :path", ProjectContextEntity.class)
                .setParameter("path", projectPath)
                .uniqueResult();
            if (entity != null) {
                return new CachedContext(
                    entity.getProjectPath(),
                    entity.getProjectName(),
                    entity.getProjectType(),
                    entity.getIndexedAt(),
                    deserializeMap(entity.getContextData()),
                    deserializeFileIndex(entity.getFileIndex())
                );
            }
        } catch (Exception e) {
            logger.warn("Failed to load context via Hibernate", e);
        }

        return null;
    }

    /**
     * Check if context cache is valid (not too old)
     */
    public boolean isCacheValid(String projectPath, long maxAgeMillis) {
        try (Session session = sf.openSession()) {
            ProjectContextEntity entity = session.createQuery(
                "FROM ProjectContextEntity WHERE projectPath = :path", ProjectContextEntity.class)
                .setParameter("path", projectPath)
                .uniqueResult();
            if (entity != null && entity.getIndexedAt() != null) {
                return (Instant.now().toEpochMilli() - entity.getIndexedAt()) < maxAgeMillis;
            }
        } catch (Exception e) {
            logger.warn("Failed to check cache validity via Hibernate", e);
        }

        return false;
    }

    /**
     * Delete context cache for a project
     */
    public void deleteContext(String projectPath) {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            session.createQuery("DELETE FROM ProjectContextEntity WHERE projectPath = :path")
                .setParameter("path", projectPath)
                .executeUpdate();
            session.getTransaction().commit();
            logger.info("Deleted context cache for: {}", projectPath);
        } catch (Exception e) {
            logger.warn("Failed to delete context via Hibernate", e);
        }
    }

    /**
     * Clear all cached contexts
     */
    public void clearAll() {
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            session.createQuery("DELETE FROM ProjectContextEntity").executeUpdate();
            session.getTransaction().commit();
            logger.info("Cleared all context cache");
        } catch (Exception e) {
            logger.warn("Failed to clear context cache via Hibernate", e);
        }
    }

    /**
     * Get list of cached projects
     */
    public java.util.List<CachedContext> listCachedContexts() {
        java.util.List<CachedContext> contexts = new java.util.ArrayList<>();
        try (Session session = sf.openSession()) {
            java.util.List<ProjectContextEntity> entities = session.createQuery(
                "FROM ProjectContextEntity ORDER BY indexedAt DESC", ProjectContextEntity.class)
                .list();
            for (ProjectContextEntity entity : entities) {
                contexts.add(new CachedContext(
                    entity.getProjectPath(),
                    entity.getProjectName(),
                    entity.getProjectType(),
                    entity.getIndexedAt(),
                    deserializeMap(entity.getContextData()),
                    deserializeFileIndex(entity.getFileIndex())
                ));
            }
        } catch (Exception e) {
            logger.warn("Failed to list contexts via Hibernate", e);
        }
        return contexts;
    }

    private String serialize(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize", e);
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeMap(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> deserializeFileIndex(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cached context data holder
     */
    public static class CachedContext {
        private final String projectPath;
        private final String projectName;
        private final String projectType;
        private final long indexedAt;
        private final Map<String, Object> contextData;
        private final Map<String, String> fileIndex;

        public CachedContext(String projectPath, String projectName, String projectType,
                            long indexedAt, Map<String, Object> contextData, Map<String, String> fileIndex) {
            this.projectPath = projectPath;
            this.projectName = projectName;
            this.projectType = projectType;
            this.indexedAt = indexedAt;
            this.contextData = contextData;
            this.fileIndex = fileIndex;
        }

        public String getProjectPath() { return projectPath; }
        public String getProjectName() { return projectName; }
        public String getProjectType() { return projectType; }
        public long getIndexedAt() { return indexedAt; }
        public Map<String, Object> getContextData() { return contextData; }
        public Map<String, String> getFileIndex() { return fileIndex; }

        public String getSummary() {
            return String.format("%s | %s | %s | %s",
                projectName != null ? projectName : "unknown",
                projectType != null ? projectType : "unknown",
                fileIndex != null ? fileIndex.size() + " files" : "0 files",
                java.time.Instant.ofEpochMilli(indexedAt).toString()
            );
        }
    }
}
