package com.github.obhen233.core.workspace;

import com.github.obhen233.core.database.DatabaseManager;
import com.github.obhen233.core.database.entity.WorkspaceContextEntity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * In-memory registry for additional workspace paths, persisted to the
 * {@code workspace_context} database table.
 * <p>
 * The primary workspace (current working directory) is NOT stored in this
 * registry -- it is always accessible. Additional workspaces registered here
 * allow the AI to read/write files across multiple projects.
 * <p>
 * All mutations are synchronously persisted to the database so that the
 * registered workspaces survive application restarts.
 */
public class WorkspaceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceRegistry.class);

    private final SessionFactory sf;
    private final Map<String, WorkspaceEntry> pathIndex = new LinkedHashMap<>();  // rootPath -> entry
    private final Map<Long, WorkspaceEntry> idIndex = new LinkedHashMap<>();      // id -> entry
    private final String primaryWorkspaceDir;

    public WorkspaceRegistry(DatabaseManager db, String primaryWorkspaceDir) {
        this.sf = db.getSessionFactory();
        this.primaryWorkspaceDir = Paths.get(primaryWorkspaceDir).toAbsolutePath().normalize().toString();
        loadFromDatabase();
    }

    // ====== DB Operations ======

    /**
     * Load all workspaces from the database, excluding the primary working directory.
     */
    private void loadFromDatabase() {
        pathIndex.clear();
        idIndex.clear();

        try (Session session = sf.openSession()) {
            List<WorkspaceContextEntity> entities = session.createQuery(
                    "FROM WorkspaceContextEntity ORDER BY id", WorkspaceContextEntity.class).list();
            Path primaryPath = Paths.get(primaryWorkspaceDir).toAbsolutePath().normalize();
            for (WorkspaceContextEntity entity : entities) {
                Path entryPath = Paths.get(entity.getRootPath()).toAbsolutePath().normalize();
                if (entryPath.equals(primaryPath)) {
                    logger.debug("Skipping primary workspace in registry: {}", entity.getRootPath());
                    continue;
                }
                WorkspaceEntry entry = toEntry(entity);
                pathIndex.put(entry.rootPath, entry);
                idIndex.put(entry.id, entry);
            }
            logger.info("Loaded {} registered workspaces from database", pathIndex.size());
        } catch (Exception e) {
            logger.warn("Failed to load workspaces from database", e);
        }
    }

    private WorkspaceEntry toEntry(WorkspaceContextEntity entity) {
        WorkspaceEntry entry = new WorkspaceEntry();
        entry.id = entity.getId();
        entry.name = entity.getName();
        entry.rootPath = entity.getRootPath();
        entry.description = entity.getDescription();
        entry.createdAt = entity.getCreatedAt() != null ? entity.getCreatedAt() : 0;
        entry.updatedAt = entity.getUpdatedAt() != null ? entity.getUpdatedAt() : 0;
        return entry;
    }

    // ====== Public API ======

    /**
     * Register a new workspace. Persists to database immediately.
     *
     * @param name        display name for the workspace
     * @param rootPath    absolute path to the workspace root
     * @param description optional description
     * @return the registered entry, or {@code null} if the path is already registered
     */
    public synchronized WorkspaceEntry addWorkspace(String name, String rootPath, String description) {
        Path normalized = Paths.get(rootPath).toAbsolutePath().normalize();
        String normalizedPath = normalized.toString();

        // Prevent registering the primary workspace
        Path primaryPath = Paths.get(primaryWorkspaceDir).toAbsolutePath().normalize();
        if (normalized.equals(primaryPath)) {
            logger.warn("Cannot register primary workspace as additional workspace: {}", normalizedPath);
            return null;
        }

        // Check for duplicate
        if (pathIndex.containsKey(normalizedPath)) {
            logger.warn("Workspace already registered: {}", normalizedPath);
            return null;
        }

        long now = System.currentTimeMillis();
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            WorkspaceContextEntity entity = new WorkspaceContextEntity();
            entity.setName(name);
            entity.setRootPath(normalizedPath);
            entity.setDescription(description);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            session.persist(entity);
            session.getTransaction().commit();

            WorkspaceEntry entry = new WorkspaceEntry();
            entry.id = entity.getId();
            entry.name = name;
            entry.rootPath = normalizedPath;
            entry.description = description;
            entry.createdAt = now;
            entry.updatedAt = now;
            pathIndex.put(normalizedPath, entry);
            idIndex.put(entry.id, entry);
            logger.info("Registered workspace: {} ({})", name, normalizedPath);
            return entry;
        } catch (Exception e) {
            logger.warn("Failed to insert workspace: {}", normalizedPath, e);
        }
        return null;
    }

    /**
     * Remove a workspace by its database ID.
     *
     * @param id the workspace ID
     * @return true if removed, false if not found
     */
    public synchronized boolean removeWorkspace(long id) {
        WorkspaceEntry entry = idIndex.get(id);
        if (entry == null) {
            return false;
        }

        try (Session session = sf.openSession()) {
            session.beginTransaction();
            org.hibernate.query.Query<?> q = session.createQuery(
                    "DELETE FROM WorkspaceContextEntity WHERE id = :id");
            q.setParameter("id", id);
            int affected = q.executeUpdate();
            session.getTransaction().commit();
            if (affected > 0) {
                pathIndex.remove(entry.rootPath);
                idIndex.remove(id);
                logger.info("Removed workspace: {} ({})", entry.name, entry.rootPath);
                return true;
            }
        } catch (Exception e) {
            logger.warn("Failed to delete workspace id={}", id, e);
        }
        return false;
    }

    /**
     * Remove a workspace by its root path.
     *
     * @param rootPath the absolute path of the workspace root
     * @return true if removed, false if not found
     */
    public synchronized boolean removeWorkspace(String rootPath) {
        Path normalized = Paths.get(rootPath).toAbsolutePath().normalize();
        String normalizedPath = normalized.toString();
        WorkspaceEntry entry = pathIndex.get(normalizedPath);
        if (entry == null) {
            return false;
        }
        return removeWorkspace(entry.id);
    }

    /**
     * List all registered workspaces (unmodifiable).
     */
    public List<WorkspaceEntry> listWorkspaces() {
        return Collections.unmodifiableList(new ArrayList<>(idIndex.values()));
    }

    /**
     * Find the workspace entry that contains the given absolute path.
     *
     * @param absolutePath an absolute file path
     * @return the containing workspace entry, or null if not in any registered workspace
     */
    public WorkspaceEntry findContainingWorkspace(String absolutePath) {
        if (absolutePath == null) return null;
        Path target = Paths.get(absolutePath).toAbsolutePath().normalize();
        for (WorkspaceEntry entry : idIndex.values()) {
            Path wsRoot = Paths.get(entry.rootPath).toAbsolutePath().normalize();
            if (target.startsWith(wsRoot)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Check whether the given absolute path falls within any registered workspace.
     */
    public boolean isInAnyWorkspace(String absolutePath) {
        return findContainingWorkspace(absolutePath) != null;
    }

    /**
     * Get the primary workspace directory.
     */
    public String getPrimaryWorkspaceDir() {
        return primaryWorkspaceDir;
    }

    /**
     * Ensure the primary workspace is registered in the database and return its ID.
     * <p>
     * Unlike {@link #addWorkspace(String, String, String)} which skips the primary
     * workspace, this method explicitly inserts or finds the primary workspace entry
     * so that all tasks and projects can be associated with a valid workspace ID.
     *
     * @return the workspace ID, or null if registration fails
     */
    public synchronized Long ensurePrimaryWorkspace() {
        // First try to find existing entry
        try (Session session = sf.openSession()) {
            WorkspaceContextEntity existing = session.createQuery(
                    "FROM WorkspaceContextEntity WHERE rootPath = :path", WorkspaceContextEntity.class)
                    .setParameter("path", primaryWorkspaceDir)
                    .uniqueResult();
            if (existing != null) {
                return existing.getId();
            }
        } catch (Exception e) {
            logger.warn("Failed to find primary workspace", e);
            return null;
        }

        // Create new workspace entry
        String name = Paths.get(primaryWorkspaceDir).getFileName().toString();
        if (name == null || name.isEmpty()) {
            name = primaryWorkspaceDir;
        }
        long now = System.currentTimeMillis();
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            WorkspaceContextEntity entity = new WorkspaceContextEntity();
            entity.setName(name);
            entity.setRootPath(primaryWorkspaceDir);
            entity.setDescription("Primary workspace");
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            session.persist(entity);
            session.getTransaction().commit();
            long id = entity.getId();
            logger.info("Registered primary workspace: {} (id={}, path={})", name, id, primaryWorkspaceDir);
            return id;
        } catch (Exception e) {
            logger.warn("Failed to insert primary workspace", e);
        }
        return null;
    }

    /**
     * Get all registered workspace root paths.
     */
    public Set<String> getAllWorkspaceRoots() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(pathIndex.keySet()));
    }

    /**
     * Reload from database. Useful if another process may have modified the table.
     */
    public synchronized void reload() {
        loadFromDatabase();
    }

    // ====== Data Class ======

    /**
     * A registered workspace entry.
     */
    public static class WorkspaceEntry {
        private long id;
        private String name;
        private String rootPath;
        private String description;
        private long createdAt;
        private long updatedAt;

        public long getId() { return id; }
        public String getName() { return name; }
        public String getRootPath() { return rootPath; }
        public String getDescription() { return description; }
        public long getCreatedAt() { return createdAt; }
        public long getUpdatedAt() { return updatedAt; }

        @Override
        public String toString() {
            return String.format("WorkspaceEntry{id=%d, name='%s', rootPath='%s'}",
                id, name, rootPath);
        }
    }
}
