<template>
  <div class="vcs-file-list">
    <!-- Search -->
    <div class="file-search">
      <input
        type="text"
        v-model="searchQuery"
        :placeholder="t('vcsSidebar.fileList.search')"
        class="file-search-input"
      />
    </div>

    <!-- File list -->
    <div class="file-tree">
      <div v-if="allFiles.length === 0" class="file-empty">
        {{ t('vcsSidebar.fileList.noFiles') }}
      </div>

      <!-- Staged files section -->
      <div v-if="stagedFiles.length > 0" class="file-section">
        <div class="file-section-header" @click="stagedExpanded = !stagedExpanded">
          <span class="section-toggle">{{ stagedExpanded ? '▼' : '▶' }}</span>
          <span class="section-title">{{ vcsType === 'svn' ? 'Added' : t('vcsSidebar.fileList.staged') }}</span>
          <span class="section-count">{{ stagedFiles.length }}</span>
        </div>
        <div v-show="stagedExpanded" class="file-section-content">
          <div
            v-for="file in stagedFiles"
            :key="file.path"
            class="file-item"
            :class="{ selected: selectedFile === file.path, checked: checkedFiles.has(file.path) }"
            @click="selectFile(file)"
            @dblclick="$emit('file-dblclick', file)"
          >
            <input
              type="checkbox"
              :checked="checkedFiles.has(file.path)"
              @click.stop="toggleCheck(file.path)"
              class="file-checkbox"
            />
            <span class="file-icon" :class="getFileStatusClass(file)">{{ getFileStatusIcon(file) }}</span>
            <span class="file-name" :title="file.path">{{ getFileName(file.path) }}</span>
          </div>
        </div>
      </div>

      <!-- Unstaged files section -->
      <div v-if="unstagedFiles.length > 0" class="file-section">
        <div class="file-section-header" @click="unstagedExpanded = !unstagedExpanded">
          <span class="section-toggle">{{ unstagedExpanded ? '▼' : '▶' }}</span>
          <span class="section-title">{{ vcsType === 'svn' ? 'Changes' : t('vcsSidebar.fileList.unstaged') }}</span>
          <span class="section-count">{{ unstagedFiles.length }}</span>
        </div>
        <div v-show="unstagedExpanded" class="file-section-content">
          <div
            v-for="file in unstagedFiles"
            :key="file.path"
            class="file-item"
            :class="{ selected: selectedFile === file.path, checked: checkedFiles.has(file.path) }"
            @click="selectFile(file)"
            @dblclick="$emit('file-dblclick', file)"
          >
            <input
              type="checkbox"
              :checked="checkedFiles.has(file.path)"
              @click.stop="toggleCheck(file.path)"
              class="file-checkbox"
            />
            <span class="file-icon" :class="getFileStatusClass(file)">{{ getFileStatusIcon(file) }}</span>
            <span class="file-name" :title="file.path">{{ getFileName(file.path) }}</span>
          </div>
        </div>
      </div>

      <!-- Conflicting files section -->
      <div v-if="conflictingFiles.length > 0" class="file-section">
        <div class="file-section-header conflict" @click="conflictingExpanded = !conflictingExpanded">
          <span class="section-toggle">{{ conflictingExpanded ? '▼' : '▶' }}</span>
          <span class="section-title">{{ t('vcsSidebar.fileList.conflicting') }}</span>
          <span class="section-count conflict">{{ conflictingFiles.length }}</span>
        </div>
        <div v-show="conflictingExpanded" class="file-section-content">
          <div
            v-for="file in conflictingFiles"
            :key="file.path"
            class="file-item conflict"
            :class="{ selected: selectedFile === file.path, checked: checkedFiles.has(file.path) }"
            @click="selectFile(file)"
            @dblclick="$emit('file-dblclick', file)"
          >
            <input
              type="checkbox"
              :checked="checkedFiles.has(file.path)"
              @click.stop="toggleCheck(file.path)"
              class="file-checkbox"
            />
            <span class="file-icon conflict">⚠</span>
            <span class="file-name" :title="file.path">{{ getFileName(file.path) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Filters -->
    <div class="file-filters">
      <div class="filter-title">{{ t('vcsSidebar.fileList.modified') }}:</div>
      <label class="filter-item">
        <input type="checkbox" v-model="filters.modified" />
        <span class="filter-icon modified">●</span>
        {{ t('vcsSidebar.fileList.modified') }}
      </label>
      <label class="filter-item">
        <input type="checkbox" v-model="filters.added" />
        <span class="filter-icon added">●</span>
        {{ t('vcsSidebar.fileList.added') }}
      </label>
      <label class="filter-item">
        <input type="checkbox" v-model="filters.untracked" />
        <span class="filter-icon untracked">○</span>
        {{ t('vcsSidebar.fileList.untracked') }}
      </label>
      <label class="filter-item">
        <input type="checkbox" v-model="filters.deleted" />
        <span class="filter-icon deleted">▲</span>
        {{ t('vcsSidebar.fileList.deleted') }}
      </label>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, reactive } from 'vue'
import { t } from '../i18n.js'

const props = defineProps({
  stagedFiles: { type: Array, default: () => [] },
  unstagedFiles: { type: Array, default: () => [] },
  conflictingFiles: { type: Array, default: () => [] },
  selectedFile: { type: String, default: null },
  checkedFiles: { type: Set, default: () => new Set() },
  vcsType: { type: String, default: 'git' }
})

const emit = defineEmits(['select-file', 'file-dblclick', 'check-file', 'uncheck-file'])

const searchQuery = ref('')
const stagedExpanded = ref(true)
const unstagedExpanded = ref(true)
const conflictingExpanded = ref(true)

const filters = reactive({
  modified: true,
  added: true,
  untracked: true,
  deleted: true
})

const allFiles = computed(() => {
  let files = []
  files = files.concat(props.stagedFiles.map(f => ({ ...f, _staged: true })))
  files = files.concat(props.unstagedFiles.map(f => ({ ...f, _staged: false })))
  files = files.concat(props.conflictingFiles.map(f => ({ ...f, _staged: false, _conflicting: true })))
  return files
})

const stagedFiles = computed(() => {
  return props.stagedFiles.filter(f => matchesFilter(f) && matchesSearch(f))
})

const unstagedFiles = computed(() => {
  return props.unstagedFiles.filter(f => matchesFilter(f) && matchesSearch(f))
})

const conflictingFiles = computed(() => {
  return props.conflictingFiles.filter(f => matchesSearch(f))
})

function matchesFilter(file) {
  if (file._conflicting) return true
  const status = file.status || file.changeType || ''
  if (status.toLowerCase().includes('mod') && !filters.modified) return false
  if ((status.toLowerCase().includes('add') || status === 'ADD' || status === 'A') && !filters.added) return false
  if ((status.toLowerCase().includes('untrack') || status === 'UNTRACKED' || status === '?') && !filters.untracked) return false
  if ((status.toLowerCase().includes('remov') || status === 'DELETE' || status === 'D' || status === 'REMOVED') && !filters.deleted) return false
  return true
}

function matchesSearch(file) {
  if (!searchQuery.value) return true
  return file.path.toLowerCase().includes(searchQuery.value.toLowerCase())
}

function getFileName(path) {
  if (!path) return ''
  const parts = path.split('/')
  return parts[parts.length - 1]
}

function getFileStatusIcon(file) {
  if (file._conflicting) return '⚠'
  const status = file.status || file.changeType || ''
  if (status.toLowerCase().includes('mod') || status === 'M' || status === 'MODIFIED') return 'M'
  if (status.toLowerCase().includes('add') || status === 'ADD' || status === 'A') return 'A'
  if (status.toLowerCase().includes('untrack') || status === 'UNTRACKED' || status === '?') return '?'
  if (status.toLowerCase().includes('remov') || status === 'DELETE' || status === 'D' || status === 'REMOVED') return 'D'
  return '?'
}

function getFileStatusClass(file) {
  if (file._conflicting) return 'conflict'
  const status = file.status || file.changeType || ''
  if (status.toLowerCase().includes('mod') || status === 'M' || status === 'MODIFIED') return 'modified'
  if (status.toLowerCase().includes('add') || status === 'ADD' || status === 'A') return 'added'
  if (status.toLowerCase().includes('untrack') || status === 'UNTRACKED' || status === '?') return 'untracked'
  if (status.toLowerCase().includes('remov') || status === 'DELETE' || status === 'D' || status === 'REMOVED') return 'deleted'
  return ''
}

function selectFile(file) {
  emit('select-file', file)
}

function toggleCheck(path) {
  if (props.checkedFiles.has(path)) {
    emit('uncheck-file', path)
  } else {
    emit('check-file', path)
  }
}
</script>

<style scoped>
.vcs-file-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.file-search {
  padding: 8px;
  border-bottom: 1px solid var(--border-color);
}

.file-search-input {
  width: 100%;
  padding: 4px 8px;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  background: var(--bg-secondary);
  color: var(--text-primary);
  font-size: 12px;
}

.file-search-input:focus {
  outline: none;
  border-color: var(--accent-color);
}

.file-tree {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}

.file-empty {
  padding: 16px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 12px;
}

.file-section {
  margin-bottom: 4px;
}

.file-section-header {
  display: flex;
  align-items: center;
  padding: 4px 8px;
  cursor: pointer;
  user-select: none;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-secondary);
}

.file-section-header:hover {
  background: var(--hover-bg);
}

.file-section-header.conflict {
  color: #e67e22;
}

.section-toggle {
  width: 12px;
  font-size: 10px;
}

.section-title {
  flex: 1;
}

.section-count {
  background: var(--bg-tertiary);
  padding: 0 6px;
  border-radius: 10px;
  font-size: 10px;
}

.section-count.conflict {
  background: #e67e22;
  color: white;
}

.file-section-content {
  padding-left: 8px;
}

.file-item {
  display: flex;
  align-items: center;
  padding: 3px 8px;
  cursor: pointer;
  font-size: 12px;
  border-radius: 2px;
  margin: 1px 4px;
}

.file-item:hover {
  background: var(--hover-bg);
}

.file-item.selected {
  background: var(--accent-bg);
}

.file-item.checked {
  background: var(--accent-bg);
}

.file-item.conflict {
  color: #e67e22;
}

.file-checkbox {
  margin-right: 6px;
  cursor: pointer;
}

.file-icon {
  width: 16px;
  margin-right: 4px;
  font-size: 11px;
  font-weight: bold;
  text-align: center;
}

.file-icon.modified { color: #3498db; }
.file-icon.added { color: #27ae60; }
.file-icon.untracked { color: #95a5a6; }
.file-icon.deleted { color: #e74c3c; }
.file-icon.conflict { color: #e67e22; }

.file-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-filters {
  padding: 8px;
  border-top: 1px solid var(--border-color);
  font-size: 11px;
}

.filter-title {
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-weight: 600;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 0;
  cursor: pointer;
  color: var(--text-primary);
}

.filter-item input {
  cursor: pointer;
}

.filter-icon {
  font-size: 10px;
}

.filter-icon.modified { color: #3498db; }
.filter-icon.added { color: #27ae60; }
.filter-icon.untracked { color: #95a5a6; }
.filter-icon.deleted { color: #e74c3c; }
</style>
