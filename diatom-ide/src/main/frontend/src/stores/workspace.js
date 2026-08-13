/**
 * Workspace Store - Manages projects, file trees, and libraries.
 * Extracted from App.vue to address issue 6.2 (state management).
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '../utils/http'

export const useWorkspaceStore = defineStore('workspace', () => {
  // === State ===
  const projects = ref([])
  const activeProject = ref('')
  const expandedProjects = ref({})
  const projectTrees = ref({})
  const treeVersion = ref(0)
  const expandedLibs = ref({})
  const projectLibs = ref({})
  const revealPath = ref('')

  // === Request deduplication ===
  const pendingRequests = new Set()

  // === Actions ===
  async function loadProjects() {
    if (pendingRequests.has('loadProjects')) return
    pendingRequests.add('loadProjects')
    try {
      const { data } = await http.get('/workspace/projects')
      projects.value = data.projects || []
    } catch (e) {
      console.error('[loadProjects] failed', e)
    } finally {
      pendingRequests.delete('loadProjects')
    }
  }

  async function toggleProjectExpand(name) {
    if (expandedProjects.value[name]) {
      expandedProjects.value = { ...expandedProjects.value, [name]: false }
      return
    }
    const reqKey = 'tree:' + name
    if (pendingRequests.has(reqKey)) return
    pendingRequests.add(reqKey)
    try {
      const { data } = await http.get(`/workspace/projects/${name}/tree`)
      if (data.success && data.tree && data.tree.children) {
        annotatePaths(data.tree.children, '')
        projectTrees.value = { ...projectTrees.value, [name]: data.tree.children }
      }
      expandedProjects.value = { ...expandedProjects.value, [name]: true }
    } catch (e) {
      console.error('Failed to load tree', e)
    } finally {
      pendingRequests.delete(reqKey)
    }
  }

  async function refreshTree(project) {
    const reqKey = 'refreshTree:' + project
    if (pendingRequests.has(reqKey)) return
    pendingRequests.add(reqKey)
    try {
      await loadProjects()
      const { data } = await http.get(`/workspace/projects/${project}/tree`)
      if (data.success && data.tree && data.tree.children) {
        annotatePaths(data.tree.children, '')
        projectTrees.value = { ...projectTrees.value, [project]: data.tree.children }
        treeVersion.value++
      }
      expandedProjects.value = { ...expandedProjects.value, [project]: true }
    } catch (e) {
      console.error('Failed to refresh tree', e)
    } finally {
      pendingRequests.delete(reqKey)
    }
    if (expandedLibs.value[project]) await loadLibs(project)
  }

  async function loadLibs(name) {
    const reqKey = 'libs:' + name
    if (pendingRequests.has(reqKey)) return
    pendingRequests.add(reqKey)
    try {
      const { data } = await http.get(`/workspace/projects/${name}/libs`)
      if (data.success) {
        projectLibs.value = { ...projectLibs.value, [name]: data }
      }
    } catch (e) {
      console.error('Failed to load libs', e)
    } finally {
      pendingRequests.delete(reqKey)
    }
  }

  async function toggleLibExpand(name) {
    if (expandedLibs.value[name]) {
      expandedLibs.value = { ...expandedLibs.value, [name]: false }
      return
    }
    await loadLibs(name)
    expandedLibs.value = { ...expandedLibs.value, [name]: true }
  }

  function setActiveProject(name) {
    activeProject.value = name
  }

  function revealInTree(key) {
    revealPath.value = key
  }

  return {
    projects, activeProject, expandedProjects, projectTrees, treeVersion,
    expandedLibs, projectLibs, revealPath,
    loadProjects, toggleProjectExpand, refreshTree, loadLibs, toggleLibExpand,
    setActiveProject, revealInTree
  }
})

// === Utility ===
function annotatePaths(nodes, parentPath) {
  for (const node of nodes) {
    node._path = parentPath ? parentPath + '/' + node.name : node.name
    if (node.children) annotatePaths(node.children, node._path)
  }
}
