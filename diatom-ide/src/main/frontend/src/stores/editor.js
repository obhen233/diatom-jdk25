/**
 * Editor Store - Manages open tabs, file contents, and dirty state.
 * Extracted from App.vue to address issue 6.2 (state management).
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import http from '../utils/http'

export const useEditorStore = defineStore('editor', () => {
  // === State ===
  const openTabs = ref([])
  const currentTab = ref('')
  const tabContents = ref({})
  const dirtyTabs = ref({})
  const activeFile = ref('')

  // === Getters ===
  const currentTabData = computed(() => {
    if (!currentTab.value) return null
    return openTabs.value.find(t => t.key === currentTab.value) || null
  })

  const isCurrentTabJava = computed(() => {
    const tab = currentTabData.value
    return tab && tab.label.endsWith('.java')
  })

  const currentDocUri = computed(() => {
    const tab = currentTabData.value
    if (!tab) return 'file:///workspace/Main.java'
    return 'file:///workspace/' + tab.project + '/' + tab.path
  })

  // === Actions ===
  function addTab(key, label, project, path, content) {
    if (openTabs.value.find(t => t.key === key)) return
    openTabs.value.push({ key, label, project, path, content })
    tabContents.value = { ...tabContents.value, [key]: content }
  }

  function removeTab(key) {
    const idx = openTabs.value.findIndex(t => t.key === key)
    if (idx === -1) return
    openTabs.value.splice(idx, 1)
    const newContents = { ...tabContents.value }
    delete newContents[key]
    tabContents.value = newContents
    const newDirty = { ...dirtyTabs.value }
    delete newDirty[key]
    dirtyTabs.value = newDirty
  }

  function setCurrentTab(key) {
    currentTab.value = key
    activeFile.value = key
  }

  function updateContent(key, content) {
    tabContents.value = { ...tabContents.value, [key]: content }
  }

  function markDirty(key, dirty = true) {
    dirtyTabs.value = { ...dirtyTabs.value, [key]: dirty }
  }

  async function saveTab(key) {
    const tab = openTabs.value.find(t => t.key === key)
    if (!tab) return false
    const content = tabContents.value[key]
    if (content === undefined) return false
    try {
      await http.put(`/workspace/projects/${tab.project}/file`, { path: tab.path, content })
      markDirty(key, false)
      return true
    } catch (e) {
      console.warn('Save failed:', key, e.message)
      return false
    }
  }

  function getNextTab(key) {
    const idx = openTabs.value.findIndex(t => t.key === key)
    if (openTabs.value.length <= 1) return ''
    if (idx < openTabs.value.length - 1) return openTabs.value[idx + 1].key
    return openTabs.value[idx - 1].key
  }

  return {
    openTabs, currentTab, tabContents, dirtyTabs, activeFile,
    currentTabData, isCurrentTabJava, currentDocUri,
    addTab, removeTab, setCurrentTab, updateContent, markDirty, saveTab, getNextTab
  }
})
