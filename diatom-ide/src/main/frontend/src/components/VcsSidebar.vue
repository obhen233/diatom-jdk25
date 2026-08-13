<template>
  <div class="vcs-sidebar" v-if="visible">
    <!-- Header -->
    <div class="vcs-header">
      <div class="vcs-header-left">
        <span class="vcs-title">🔀 {{ t('vcsSidebar.title') }}</span>
        <span class="vcs-branch" v-if="vcsType === 'git' && gitInfo.branch" @click="$emit('show-branch-dialog')">
          Git · {{ gitInfo.branch }}
        </span>
        <span class="vcs-branch" v-else-if="vcsType === 'svn'">SVN Working Copy</span>
        <span class="vcs-branch" v-else>No VCS</span>
      </div>
      <div class="vcs-header-right">
        <button class="vcs-action-btn" @click="refresh" :title="t('vcsSidebar.actions.refresh')">🔄</button>
        <button class="vcs-action-btn" @click="close" :title="t('close')">✕</button>
      </div>
    </div>

    <div v-if="vcsType === 'none'" class="vcs-empty-state">
      <div class="vcs-empty-title">No version control detected</div>
      <div class="vcs-empty-desc">This project is not a Git or SVN working copy.</div>
    </div>

    <!-- Toolbar -->
    <div v-if="vcsType !== 'none'" class="vcs-toolbar">
      <div class="vcs-toolbar-left">
        <button class="toolbar-btn" @click="selectAll" :title="t('vcsSidebar.actions.selectAll')">☑</button>
        <button class="toolbar-btn" @click="deselectAll" :title="t('vcsSidebar.actions.deselectAll')">☐</button>
      </div>
      <div class="vcs-toolbar-right">
        <button
          v-if="canStage"
          class="toolbar-btn action"
          @click="handleStage"
          :title="t('vcsSidebar.actions.stage')"
        >{{ vcsType === 'svn' ? 'Add' : t('vcsSidebar.actions.stage') }}</button>
        <button
          v-if="vcsType !== 'svn' && checkedFiles.size > 0"
          class="toolbar-btn action"
          @click="handleUnstage"
          :title="t('vcsSidebar.actions.unstage')"
        >{{ t('vcsSidebar.actions.unstage') }}</button>
        <button
          v-if="vcsType !== 'svn' && checkedFiles.size > 0"
          class="toolbar-btn action danger"
          @click="handleDiscard"
          :title="t('vcsSidebar.actions.discard')"
        >{{ t('vcsSidebar.actions.discard') }}</button>
      </div>
    </div>

    <div v-if="vcsType === 'svn' && currentVcsInfo.message" class="vcs-error-state">
      <div class="vcs-empty-title">SVN command failed</div>
      <div class="vcs-empty-desc">{{ currentVcsInfo.message }}</div>
      <div class="vcs-empty-desc">Please check SVN Path in Settings.</div>
    </div>

    <!-- Main content: split view -->
    <div v-if="vcsType !== 'none'" class="vcs-content">
      <!-- File list (left pane) -->
      <div class="vcs-file-pane" :style="{ width: splitPosition + '%' }">
        <VcsFileList
          :staged-files="stagedFiles"
          :unstaged-files="unstagedFiles"
          :conflicting-files="conflictingFiles"
          :selected-file="selectedFile"
          :checked-files="checkedFiles"
          :vcs-type="vcsType"
          @select-file="onSelectFile"
          @file-dblclick="onFileDoubleClick"
          @check-file="onCheckFile"
          @uncheck-file="onUncheckFile"
        />
      </div>

      <!-- Resize handle -->
      <div class="vcs-split-handle" @mousedown="startResize"></div>

      <!-- Diff preview (right pane) -->
      <div class="vcs-diff-pane" :style="{ width: (100 - splitPosition) + '%' }">
        <VcsDiffViewer
          :selected-file="selectedFile"
          :diff-content="diffContent"
          :old-content="diffOldContent"
          :new-content="diffNewContent"
          :file-status="selectedFileStatus"
          :loading="diffLoading"
          :empty-message="diffEmptyMessage"
        />
      </div>
    </div>

    <!-- Bottom action bar -->
    <div class="vcs-actions-bar">
      <button
        class="action-bar-btn primary"
        :disabled="committableFiles.length === 0"
        @click="$emit('commit')"
      >{{ t('vcsSidebar.actions.commit') }}</button>
      <button
        v-if="vcsType !== 'svn'"
        class="action-bar-btn"
        :disabled="!gitInfo.branch"
        @click="$emit('push')"
      >{{ t('vcsSidebar.actions.push') }}</button>
      <button
        class="action-bar-btn"
        @click="$emit('pull')"
      >{{ vcsType === 'svn' ? 'Update' : t('vcsSidebar.actions.pull') }}</button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { t } from '../i18n.js'
import http from '../utils/http'
import VcsFileList from './VcsFileList.vue'
import VcsDiffViewer from './VcsDiffViewer.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  gitInfo: { type: Object, default: () => ({}) },
  svnInfo: { type: Object, default: () => ({}) },
  vcsType: { type: String, default: 'git' },
  project: { type: String, default: null }
})

const emit = defineEmits([
  'refresh',
  'stage',
  'unstage',
  'discard',
  'commit',
  'push',
  'pull',
  'show-branch-dialog',
  'close'
])

// State
const checkedFiles = reactive(new Set())
const selectedFile = ref(null)
const diffContent = ref('')
const diffOldContent = ref('')
const diffNewContent = ref('')
const diffLoading = ref(false)
const splitPosition = ref(35)

const currentVcsInfo = computed(() => props.vcsType === 'svn' ? props.svnInfo : props.gitInfo)

// Computed - staged files from current VCS info
// 'added' in git status = staged files; SVN added files are commit-ready
const stagedFiles = computed(() => {
  if (!currentVcsInfo.value.initialized) return []
  return (currentVcsInfo.value.added || []).map(f => ({ path: f, status: 'staged', changeType: 'ADDED' }))
})

const unstagedFiles = computed(() => {
  if (!currentVcsInfo.value.initialized) return []
  const files = []
  ;(currentVcsInfo.value.modified || []).forEach(f => files.push({ path: f, status: 'modified', changeType: 'MODIFIED' }))
  ;(currentVcsInfo.value.untracked || []).forEach(f => files.push({ path: f, status: 'untracked', changeType: 'UNTRACKED' }))
  ;(currentVcsInfo.value.removed || []).forEach(f => files.push({ path: f, status: 'removed', changeType: 'DELETED' }))
  return files
})

const conflictingFiles = computed(() => {
  if (!currentVcsInfo.value.initialized) return []
  return (currentVcsInfo.value.conflicting || []).map(f => ({ path: f, status: 'conflicting', changeType: 'CONFLICTING' }))
})

const committableFiles = computed(() => {
  if (props.vcsType === 'svn') return [...stagedFiles.value, ...unstagedFiles.value]
  return stagedFiles.value
})

const canStage = computed(() => {
  if (checkedFiles.size === 0) return false
  if (props.vcsType !== 'svn') return true
  const checked = new Set([...checkedFiles])
  return unstagedFiles.value.some(f => f.status === 'untracked' && checked.has(f.path))
})

const selectedFileStatus = computed(() => {
  if (!selectedFile.value) return ''
  const allFiles = [...stagedFiles.value, ...unstagedFiles.value, ...conflictingFiles.value]
  const file = allFiles.find(f => f.path === selectedFile.value)
  return file ? file.changeType : ''
})

const diffEmptyMessage = computed(() => {
  if (!selectedFile.value) return 'Select a changed file to view diff'
  if (selectedFileStatus.value === 'UNTRACKED') return 'No diff available for unversioned files. Add the file first.'
  return 'No diff available for this file.'
})

// File selection
function onSelectFile(file) {
  selectedFile.value = file.path
  loadDiff(file.path)
}

function onFileDoubleClick(file) {
  // Could open in editor in future
}

function onCheckFile(path) {
  checkedFiles.add(path)
}

function onUncheckFile(path) {
  checkedFiles.delete(path)
}

function selectAll() {
  const allFiles = props.vcsType === 'svn'
    ? unstagedFiles.value.filter(f => f.status === 'untracked')
    : [...unstagedFiles.value, ...conflictingFiles.value]
  allFiles.forEach(f => checkedFiles.add(f.path))
}

function deselectAll() {
  checkedFiles.clear()
}

// Stage/Unstage/Discard
function handleStage() {
  if (props.vcsType === 'svn') {
    const untracked = new Set(unstagedFiles.value.filter(f => f.status === 'untracked').map(f => f.path))
    emit('stage', [...checkedFiles].filter(path => untracked.has(path)))
  } else {
    emit('stage', [...checkedFiles])
  }
  checkedFiles.clear()
}

function handleUnstage() {
  emit('unstage', [...checkedFiles])
  checkedFiles.clear()
}

function handleDiscard() {
  if (confirm(t('confirmDiscard'))) {
    emit('discard', [...checkedFiles])
    checkedFiles.clear()
  }
}

// Load diff using the file-diff endpoint
async function loadDiff(filePath) {
  if (!filePath || !props.project) {
    diffContent.value = ''
    return
  }
  diffLoading.value = true
  diffContent.value = ''
  try {
    const endpoint = props.vcsType === 'svn' ? 'svn' : 'git'
    const { data } = await http.get(`/workspace/projects/${props.project}/vcs/${endpoint}/file-diff`, {
      params: { file: filePath }
    })
    diffContent.value = data.diff || ''
    diffOldContent.value = data.oldContent || ''
    diffNewContent.value = data.newContent || ''
  } catch (e) {
    console.warn('Failed to load diff', e)
  }
  diffLoading.value = false
}

// Refresh
function refresh() {
  emit('refresh')
  checkedFiles.clear()
  selectedFile.value = null
  diffContent.value = ''
  diffOldContent.value = ''
  diffNewContent.value = ''
}

function close() {
  emit('close')
}

// Resize split pane
function startResize(e) {
  const startX = e.clientX
  const startPos = splitPosition.value
  const container = e.target.parentElement

  function onMouseMove(e) {
    const dx = e.clientX - startX
    const containerWidth = container.offsetWidth
    splitPosition.value = Math.max(20, Math.min(80, startPos + (dx / containerWidth) * 100))
  }

  function onMouseUp() {
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
  }

  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
}
</script>

<style scoped>
.vcs-sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
  border-left: 1px solid var(--border-color);
}

.vcs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.vcs-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.vcs-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
}

.vcs-branch {
  font-size: 12px;
  cursor: pointer;
  color: var(--text-primary);
}

.vcs-branch:hover {
  color: var(--accent-color);
}

.vcs-action-btn {
  padding: 4px 8px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  opacity: 0.7;
}

.vcs-action-btn:hover {
  opacity: 1;
}

.vcs-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.vcs-toolbar-left,
.vcs-toolbar-right {
  display: flex;
  gap: 4px;
}

.toolbar-btn {
  padding: 4px 8px;
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-secondary);
  border-radius: 3px;
}

.toolbar-btn:hover {
  background: var(--hover-bg);
  color: var(--text-primary);
}

.toolbar-btn.action {
  background: var(--accent-bg);
  color: var(--accent-color);
}

.toolbar-btn.action:hover {
  background: var(--accent-color);
  color: white;
}

.toolbar-btn.danger {
  color: #e74c3c;
}

.toolbar-btn.danger:hover {
  background: #e74c3c;
  color: white;
}

.vcs-content {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.vcs-file-pane {
  overflow: hidden;
  min-width: 150px;
}

.vcs-split-handle {
  width: 4px;
  cursor: col-resize;
  background: var(--border-color);
}

.vcs-split-handle:hover {
  background: var(--accent-color);
}

.vcs-diff-pane {
  overflow: hidden;
  min-width: 150px;
}

.vcs-actions-bar {
  display: flex;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.action-bar-btn {
  padding: 6px 16px;
  border: 1px solid var(--border-color);
  background: var(--bg-tertiary);
  color: var(--text-primary);
  font-size: 12px;
  cursor: pointer;
  border-radius: 4px;
}

.action-bar-btn:hover:not(:disabled) {
  background: var(--hover-bg);
}

.action-bar-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-bar-btn.primary {
  background: var(--accent-color);
  color: white;
  border-color: var(--accent-color);
}

.action-bar-btn.primary:hover:not(:disabled) {
  background: var(--accent-color-darken, #2980b9);
}
</style>
