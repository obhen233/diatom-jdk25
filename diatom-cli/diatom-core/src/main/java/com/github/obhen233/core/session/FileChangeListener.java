package com.github.obhen233.core.session;

/**
 * Listener for real-time file change notifications during AI task execution.
 * <p>
 * Called after every file write/modify/delete operation, providing
 * before-and-after content for diff computation and real-time display.
 */
public interface FileChangeListener {

    /**
     * Called when a file has been changed by a tool execution.
     *
     * @param filePath   the path of the changed file (relative to workspace)
     * @param oldContent the content before the change (null if file was created)
     * @param newContent the content after the change (null if file was deleted)
     * @param operation  the operation type: CREATE, MODIFY, or DELETE
     * @param category   the file category (PROJECT_SOURCE, HELPER_SCRIPT, etc.)
     */
    void onFileChanged(String filePath, String oldContent,
                       String newContent, String operation, FileCategory category);
}
