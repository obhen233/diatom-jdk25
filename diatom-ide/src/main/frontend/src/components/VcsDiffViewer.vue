<template>
  <div class="vcs-diff-viewer">
    <div v-if="!selectedFile" class="diff-empty">
      {{ t('vcsSidebar.diff.noSelection') }}
    </div>
    <div v-else class="diff-content">
      <!-- Header -->
      <div class="diff-header">
        <span class="diff-file-name" :title="selectedFile">{{ getFileName(selectedFile) }}</span>
        <span class="diff-badge" :class="fileStatusClass">{{ fileStatus }}</span>
        <span class="diff-stats" v-if="diffStats">
          <span class="stat-added">+{{ diffStats.added }}</span>
          <span class="stat-removed">-{{ diffStats.removed }}</span>
        </span>
      </div>

      <!-- Diff body: side by side -->
      <div class="diff-body" v-if="!loading && pairedLines.length > 0">
        <!-- Column headers -->
        <div class="diff-column-headers">
          <div class="diff-col-header old-col">{{ oldLabel }}</div>
          <div class="diff-col-header new-col">{{ newLabel }}</div>
        </div>
        <!-- Scrollable diff area -->
        <div class="diff-scroll-container" ref="scrollContainer">
          <div class="diff-columns" ref="diffColumns">
            <!-- Left column (old) -->
            <div class="diff-col old-col" ref="leftCol">
              <div v-for="(pair, idx) in pairedLines" :key="'l' + idx"
                   class="diff-line-row"
                   :class="getRowClass(pair)">
                <div class="diff-gutter old-gutter">
                  <span class="diff-line-num" v-if="pair.oldLineNum > 0">{{ pair.oldLineNum }}</span>
                </div>
                <div class="diff-line-content old-line-content"
                     :class="pair.type === 'modified' ? 'diff-content-modified' : ''"
                     v-text="pair.oldContent || ''">
                </div>
              </div>
            </div>
            <!-- Right column (new) -->
            <div class="diff-col new-col" ref="rightCol">
              <div v-for="(pair, idx) in pairedLines" :key="'r' + idx"
                   class="diff-line-row"
                   :class="getRowClass(pair)">
                <div class="diff-gutter new-gutter">
                  <span class="diff-line-num" v-if="pair.newLineNum > 0">{{ pair.newLineNum }}</span>
                </div>
                <div class="diff-line-content new-line-content"
                     :class="pair.type === 'modified' ? 'diff-content-modified' : ''"
                     v-text="pair.newContent || ''">
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Loading / Empty -->
      <div class="diff-body" v-else>
        <div v-if="loading" class="diff-loading">
          {{ t('loading') }}...
        </div>
        <div v-else class="diff-empty-content">
          {{ emptyMessage || t('vcsSidebar.diff.noSelection') }}
        </div>
      </div>
    </div>
  </div>
</template>

<script>
// Simple LCS-based diff algorithm for side-by-side comparison
function computeLcsDiff(oldLines, newLines) {
  const m = oldLines.length
  const n = newLines.length

  // Build full LCS table for backtracking
  const dp = Array(m + 1).fill(null).map(() => Array(n + 1).fill(0))
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      if (oldLines[i - 1] === newLines[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1] + 1
      } else {
        dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1])
      }
    }
  }

  // Backtrack to build diff ops
  let i = m, j = n
  const stack = []
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
      stack.push({ type: 'equal', oldLine: oldLines[i - 1], newLine: newLines[j - 1], oldIdx: i - 1, newIdx: j - 1 })
      i--; j--
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      stack.push({ type: 'add', oldLine: '', newLine: newLines[j - 1], oldIdx: -1, newIdx: j - 1 })
      j--
    } else if (i > 0) {
      stack.push({ type: 'remove', oldLine: oldLines[i - 1], newLine: '', oldIdx: i - 1, newIdx: -1 })
      i--
    } else {
      break
    }
  }

  return stack.reverse()
}
</script>

<script setup>
import { computed, ref } from 'vue'
import { t } from '../i18n.js'

const props = defineProps({
  selectedFile: { type: String, default: null },
  diffContent: { type: String, default: '' },
  oldContent: { type: String, default: '' },
  newContent: { type: String, default: '' },
  fileStatus: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  emptyMessage: { type: String, default: '' }
})

const scrollContainer = ref(null)
const leftCol = ref(null)
const rightCol = ref(null)

const oldLabel = computed(() => t('vcsSidebar.diff.staged'))
const newLabel = computed(() => t('vcsSidebar.diff.working'))

function getFileName(path) {
  if (!path) return ''
  const parts = path.split('/')
  return parts[parts.length - 1]
}

const fileStatusClass = computed(() => {
  const status = props.fileStatus.toLowerCase()
  if (status.includes('mod') || status === 'm') return 'modified'
  if (status.includes('add') || status === 'a') return 'added'
  if (status.includes('untrack') || status === '?') return 'untracked'
  if (status.includes('remov') || status === 'd') return 'deleted'
  if (status.includes('conflict')) return 'conflict'
  return ''
})

const diffStats = computed(() => {
  let added = 0, removed = 0
  for (const line of pairedLines.value) {
    if (line.type === 'added' || line.type === 'modified') added++
    if (line.type === 'removed' || line.type === 'modified') removed++
  }
  return (added > 0 || removed > 0) ? { added, removed } : null
})

/**
 * Compute side-by-side diff pairs from oldContent and newContent.
 * Falls back to parsing the unified diff when old/new content is empty.
 */
const pairedLines = computed(() => {
  if (!props.selectedFile) return []
  if (props.loading) return []

  const oldContent = props.oldContent || ''
  const newContent = props.newContent || ''

  // If both empty, parse from unified diff
  if (!oldContent && !newContent) {
    return parseUnifiedDiff(props.diffContent || '')
  }

  const oldLines = oldContent === '' ? [] : oldContent.split('\n')
  const newLines = newContent === '' ? [] : newContent.split('\n')

  // Handle trailing newline: if content ends with \n, the last empty line is from
  // the split. Remove it to avoid spurious blank line entries.
  if (oldContent.endsWith('\n') && oldLines[oldLines.length - 1] === '') oldLines.pop()
  if (newContent.endsWith('\n') && newLines[newLines.length - 1] === '') newLines.pop()

  // For untracked/new files: all lines are additions
  const isUntracked = !oldContent && newContent
  const isDeleted = oldContent && !newContent

  if (isUntracked) {
    return newLines.map((line, idx) => ({
      type: 'added',
      oldLineNum: -1,
      oldContent: '',
      newLineNum: idx + 1,
      newContent: line
    }))
  }

  if (isDeleted) {
    return oldLines.map((line, idx) => ({
      type: 'removed',
      oldLineNum: idx + 1,
      oldContent: line,
      newLineNum: -1,
      newContent: ''
    }))
  }

  // Full diff via LCS for modified files
  const diffs = computeLcsDiff(oldLines, newLines)

  let oldLineNum = 0
  let newLineNum = 0
  return diffs.map(d => {
    switch (d.type) {
      case 'equal':
        oldLineNum++
        newLineNum++
        return {
          type: 'equal',
          oldLineNum,
          oldContent: d.oldLine,
          newLineNum,
          newContent: d.oldLine
        }
      case 'remove':
        oldLineNum++
        return {
          type: 'removed',
          oldLineNum,
          oldContent: d.oldLine,
          newLineNum: -1,
          newContent: ''
        }
      case 'add':
        newLineNum++
        return {
          type: 'added',
          oldLineNum: -1,
          oldContent: '',
          newLineNum,
          newContent: d.newLine
        }
      default:
        return { type: 'equal', oldLineNum: -1, oldContent: '', newLineNum: -1, newContent: '' }
    }
  })
})

/**
 * Fallback: parse unified diff text when old/new content isn't available.
 */
function parseUnifiedDiff(diff) {
  if (!diff) return []

  const lines = diff.split('\n')
  const result = []

  // Skip header lines (---/+++)
  let i = 0
  while (i < lines.length && (!lines[i].startsWith('@@'))) {
    i++
  }

  let oldLineNum = 0
  let newLineNum = 0

  for (; i < lines.length; i++) {
    const line = lines[i]
    if (line === '') continue

    if (line.startsWith('@@')) {
      // hunk header: @@ -start,count +start,count @@
      const match = line.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/)
      if (match) {
        oldLineNum = parseInt(match[1]) - 1
        newLineNum = parseInt(match[2]) - 1
      }
      continue
    }

    if (line.startsWith('-')) {
      oldLineNum++
      result.push({
        type: 'removed',
        oldLineNum,
        oldContent: line.substring(1),
        newLineNum: -1,
        newContent: ''
      })
    } else if (line.startsWith('+')) {
      newLineNum++
      result.push({
        type: 'added',
        oldLineNum: -1,
        oldContent: '',
        newLineNum,
        newContent: line.substring(1)
      })
    } else {
      // Context line (starts with space or no prefix)
      oldLineNum++
      newLineNum++
      const content = line.startsWith(' ') ? line.substring(1) : line
      result.push({
        type: 'equal',
        oldLineNum,
        oldContent: content,
        newLineNum,
        newContent: content
      })
    }
  }

  return result
}

function getRowClass(pair) {
  if (!pair) return ''
  switch (pair.type) {
    case 'added': return 'row-added'
    case 'removed': return 'row-removed'
    case 'modified': return 'row-modified'
    default: return 'row-equal'
  }
}
</script>

<style scoped>
.vcs-diff-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: var(--bg-primary);
}

.diff-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary);
  font-size: 13px;
}

.diff-content {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.diff-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-secondary);
  flex-shrink: 0;
}

.diff-file-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.diff-badge {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 3px;
  font-weight: 600;
  flex-shrink: 0;
}

.diff-badge.modified { background: #3498db; color: white; }
.diff-badge.added { background: #27ae60; color: white; }
.diff-badge.untracked { background: #95a5a6; color: white; }
.diff-badge.deleted { background: #e74c3c; color: white; }
.diff-badge.conflict { background: #e67e22; color: white; }

.diff-stats {
  font-size: 11px;
  font-weight: 600;
  margin-left: auto;
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.stat-added { color: #27ae60; }
.stat-removed { color: #e74c3c; }

.diff-body {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* Column headers */
.diff-column-headers {
  display: flex;
  flex-shrink: 0;
  border-bottom: 1px solid var(--border-color);
  font-size: 11px;
  font-weight: 600;
}
.diff-col-header {
  width: 50%;
  padding: 4px 12px;
  background: var(--bg-tertiary, #f0f0f0);
  color: var(--text-secondary);
  user-select: none;
}

/* Scrollable diff area */
.diff-scroll-container {
  flex: 1;
  overflow: auto;
  position: relative;
}

.diff-columns {
  display: flex;
  min-height: 100%;
}

.diff-col {
  width: 50%;
  overflow: hidden;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.45;
}

.old-col {
  border-right: 1px solid var(--border-color);
}

/* Single diff row inside a column */
.diff-line-row {
  display: flex;
  min-height: 16px;
}

/* Gutter (line number area) */
.diff-gutter {
  width: 48px;
  min-width: 48px;
  text-align: right;
  padding-right: 6px;
  user-select: none;
  flex-shrink: 0;
}

.old-gutter {
  border-right: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.new-gutter {
  border-right: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.diff-line-num {
  color: var(--text-tertiary, #888);
  font-size: 11px;
}

/* Line content */
.diff-line-content {
  flex: 1;
  padding: 0 6px;
  white-space: pre;
  overflow: hidden;
  text-overflow: ellipsis;
  min-height: 16px;
}

.old-line-content {
  padding-left: 8px;
}

.new-line-content {
  padding-left: 8px;
}

/* Row states */
.row-equal {
  background: transparent;
}

.row-added .new-line-content {
  background: rgba(39, 174, 96, 0.12);
}
.row-added .new-gutter {
  background: rgba(39, 174, 96, 0.15);
}

.row-removed .old-line-content {
  background: rgba(231, 76, 60, 0.12);
}
.row-removed .old-gutter {
  background: rgba(231, 76, 60, 0.15);
}

/* Modified = left is removed, right is added (paired) */
.row-modified .old-line-content {
  background: rgba(231, 76, 60, 0.12);
}
.row-modified .new-line-content {
  background: rgba(39, 174, 96, 0.12);
}
.row-modified .old-gutter {
  background: rgba(231, 76, 60, 0.15);
}
.row-modified .new-gutter {
  background: rgba(39, 174, 96, 0.15);
}

.diff-content-modified {
  /* Intentionally empty - inherits from parent row class */
}

.diff-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 16px;
  color: var(--text-secondary);
  font-size: 12px;
}

.diff-empty-content {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 16px;
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
