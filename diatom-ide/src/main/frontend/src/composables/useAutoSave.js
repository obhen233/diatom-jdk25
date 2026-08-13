/**
 * Auto-save Composable
 * Debounced auto-save with dirty tracking.
 */
import { ref, onBeforeUnmount } from 'vue'
import { useEditorStore } from '../stores/editor.js'

export function useAutoSave(delay = 1000) {
  const editorStore = useEditorStore()
  let autoSaveTimer = null

  /**
   * Schedule an auto-save for the current tab after a delay.
   */
  function scheduleAutoSave() {
    const key = editorStore.currentTab
    if (!key) return
    editorStore.markDirty(key, true)
    if (autoSaveTimer) clearTimeout(autoSaveTimer)
    autoSaveTimer = setTimeout(() => {
      if (editorStore.currentTab) {
        editorStore.saveTab(editorStore.currentTab)
      }
    }, delay)
  }

  /**
   * Immediately flush any pending auto-save.
   */
  async function flushSave() {
    if (autoSaveTimer) {
      clearTimeout(autoSaveTimer)
      autoSaveTimer = null
    }
    const key = editorStore.currentTab
    if (key && editorStore.dirtyTabs[key]) {
      await editorStore.saveTab(key)
    }
  }

  // Cleanup on unmount (issue 6.4)
  onBeforeUnmount(() => {
    if (autoSaveTimer) {
      clearTimeout(autoSaveTimer)
      autoSaveTimer = null
    }
  })

  return {
    scheduleAutoSave,
    flushSave
  }
}
