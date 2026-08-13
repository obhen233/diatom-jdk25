<template>
  <div class="ai-diff-panel" v-if="changes.length > 0">
    <!-- Header bar with stats -->
    <div class="ai-diff-header" @click="expanded = !expanded">
      <span class="ai-diff-title">
        <span class="ai-diff-icon">📄</span>
        Changes ({{ changes.length }} file{{ changes.length > 1 ? 's' : '' }})
      </span>
      <span class="ai-diff-stats">
        <span class="stat-added">+{{ totalAdded }}</span>
        <span class="stat-removed">-{{ totalRemoved }}</span>
      </span>
      <span class="ai-diff-back" @click.stop="$emit('scroll-to-banner')" title="Back to banner">↑</span>
      <span class="ai-diff-toggle">{{ expanded ? '▼' : '▶' }}</span>
    </div>

    <!-- Expanded content -->
    <div v-if="expanded" class="ai-diff-body">
      <!-- File diffs -->
      <div v-for="(file, fi) in changes" :key="file.filePath || fi" class="ai-diff-file">
        <div class="ai-diff-file-header" @click.stop="toggleFile(file, fi)">
          <span class="file-chevron">{{ isFileExpanded(file, fi) ? '▼' : '▶' }}</span>
          <span class="file-operation-badge" :class="opClass(file.operation)">
            {{ file.operation }}
          </span>
          <span class="file-path ai-diff-filepath-clickable" :title="file.filePath" @click.stop="$emit('open-file', file.filePath)">{{ file.filePath }}</span>
        </div>
        <div v-if="isFileExpanded(file, fi)" class="ai-diff-file-body">
          <pre class="ai-diff-pre"><span v-for="(line, li) in parsedDiff(file)" :key="li"
            :class="'ai-diff-line ' + lineClass(line)"><span class="ai-diff-line-num">{{ li + 1 }}</span><span class="ai-diff-marker">{{ line.charAt(0) }}</span><span class="ai-diff-text">{{ line.substring(1) }}</span></span></pre>
        </div>
      </div>
    </div>

    <!-- Feedback input (sticky at bottom, outside scrollable body) -->
    <div v-if="expanded" class="ai-diff-feedback">
      <input
        v-model="feedbackText"
        class="ai-feedback-input"
        :placeholder="t('aiDiffFeedbackPlaceholder')"
        @keydown.enter="sendFeedback"
        :disabled="!sessionId"
      />
      <button
        class="ai-feedback-btn"
        @click="sendFeedback"
        :disabled="!feedbackText.trim() || !sessionId"
      >{{ t('aiDiffSendBtn') }}</button>
    </div>
  </div>
</template>

<script>
import { t } from '../i18n.js'
export default {
  name: 'AiDiffPanel',
  props: {
    changes: { type: Array, default: () => [] },
    sessionId: { type: String, default: '' }
  },
  emits: ['send-feedback', 'scroll-to-banner', 'open-file'],
  data() {
    return {
      expanded: true,
      feedbackText: '',
      fileExpandedState: {}
    }
  },
  computed: {
    totalAdded() {
      let count = 0
      for (const file of this.changes) {
        count += this.parsedDiff(file).filter(line => line.startsWith('+') && !line.startsWith('+++ ')).length
      }
      return count
    },
    totalRemoved() {
      let count = 0
      for (const file of this.changes) {
        count += this.parsedDiff(file).filter(line => line.startsWith('-') && !line.startsWith('--- ')).length
      }
      return count
    }
  },
  methods: {
    t,
    toggleFile(file, fi) {
      const key = file.filePath || String(fi)
      this.fileExpandedState[key] = !this.isFileExpanded(file, fi)
    },
    isFileExpanded(file, fi) {
      const key = file.filePath || String(fi)
      if (Object.prototype.hasOwnProperty.call(this.fileExpandedState, key)) {
        return this.fileExpandedState[key]
      }
      return file.expanded !== false
    },
    opClass(op) {
      if (op === 'CREATE') return 'op-created'
      if (op === 'MODIFY') return 'op-modified'
      if (op === 'DELETE') return 'op-deleted'
      return ''
    },
    parsedDiff(file) {
      if (!file.diff) return []
      // Split diff into lines, filter out the ---/+++ header lines
      const lines = file.diff.split('\n')
      return lines.filter(line => line.length > 0 && !line.startsWith('--- ') && !line.startsWith('+++ '))
    },
    lineClass(line) {
      if (line.startsWith('+')) return 'diff-added'
      if (line.startsWith('-')) return 'diff-removed'
      if (line.startsWith('@@')) return 'diff-hunk'
      return 'diff-context'
    },
    sendFeedback() {
      if (!this.feedbackText.trim() || !this.sessionId) return
      this.$emit('send-feedback', this.feedbackText.trim())
      this.feedbackText = ''
    }
  }
}
</script>

<style scoped>
.ai-diff-panel {
  border-top: 1px solid var(--border-color, #333);
  background: var(--bg-primary, #1e1e1e);
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  max-height: 50vh;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.ai-diff-header {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  cursor: pointer;
  background: var(--bg-secondary, #252526);
  border-bottom: 1px solid var(--border-color, #333);
  user-select: none;
  gap: 8px;
  flex-shrink: 0;
}

.ai-diff-title {
  flex: 1;
  font-weight: 600;
  color: var(--text-primary, #d4d4d4);
  font-size: 12px;
}

.ai-diff-icon {
  margin-right: 4px;
}

.ai-diff-stats {
  display: flex;
  gap: 8px;
  font-size: 11px;
  font-weight: 600;
}

.stat-added {
  color: #4ec9b0;
}

.stat-removed {
  color: #f44747;
}

.ai-diff-toggle {
  color: var(--text-secondary, #888);
  font-size: 10px;
}

.ai-diff-back {
  cursor: pointer;
  color: var(--text-secondary, #888);
  font-size: 14px;
  padding: 0 4px;
  margin-left: 4px;
  font-weight: 700;
}
.ai-diff-back:hover {
  color: #569cd6;
}

.ai-diff-body {
  padding: 4px 0;
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}

.ai-diff-file {
  border-bottom: 1px solid var(--border-color, #333);
}

.ai-diff-file:last-child {
  border-bottom: none;
}

.ai-diff-file-header {
  display: flex;
  align-items: center;
  padding: 4px 12px;
  cursor: pointer;
  gap: 6px;
  background: var(--bg-primary, #1e1e1e);
}

.file-chevron {
  color: var(--text-secondary, #888);
  font-size: 10px;
  width: 12px;
}

.file-operation-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 600;
}

.op-created {
  background: rgba(78, 201, 176, 0.2);
  color: #4ec9b0;
}

.op-modified {
  background: rgba(86, 156, 214, 0.2);
  color: #569cd6;
}

.op-deleted {
  background: rgba(244, 71, 71, 0.2);
  color: #f44747;
}

.file-path {
  color: var(--text-primary, #d4d4d4);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-diff-filepath-clickable {
  cursor: pointer;
}
.ai-diff-filepath-clickable:hover {
  color: #4da6ff !important;
  text-decoration: underline;
}

.ai-diff-file-body {
  overflow-x: auto;
}

.ai-diff-pre {
  margin: 0;
  padding: 4px 0;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre;
}

.ai-diff-line {
  display: block;
  padding: 0 12px;
}

.ai-diff-line.diff-added {
  background: rgba(78, 201, 176, 0.1);
}

.ai-diff-line.diff-removed {
  background: rgba(244, 71, 71, 0.1);
}

.ai-diff-line.diff-hunk {
  color: #569cd6;
  font-weight: 600;
}

.ai-diff-line.diff-context {
  color: var(--text-primary, #d4d4d4);
}

.ai-diff-line-num {
  display: inline-block;
  width: 32px;
  text-align: right;
  color: var(--text-secondary, #888);
  user-select: none;
  margin-right: 8px;
  font-size: 10px;
}

.ai-diff-marker {
  display: inline-block;
  width: 14px;
  text-align: center;
  user-select: none;
  font-weight: 600;
}

.diff-added .ai-diff-marker {
  color: #4ec9b0;
}

.diff-removed .ai-diff-marker {
  color: #f44747;
}

.ai-diff-text {
  color: var(--text-primary, #d4d4d4);
}

/* Feedback input */
.ai-diff-feedback {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  gap: 6px;
  border-top: 1px solid var(--border-color, #333);
  background: var(--bg-secondary, #252526);
  flex-shrink: 0;
}

.ai-feedback-input {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid var(--border-color, #333);
  border-radius: 4px;
  background: var(--bg-primary, #1e1e1e);
  color: var(--text-primary, #d4d4d4);
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  outline: none;
}

.ai-feedback-input:focus {
  border-color: #569cd6;
}

.ai-feedback-input::placeholder {
  color: var(--text-secondary, #888);
}

.ai-feedback-input:disabled {
  opacity: 0.5;
}

.ai-feedback-btn {
  padding: 6px 14px;
  border: none;
  border-radius: 4px;
  background: #569cd6;
  color: white;
  font-size: 12px;
  cursor: pointer;
  font-weight: 600;
}

.ai-feedback-btn:hover:not(:disabled) {
  background: #4a8bc2;
}

.ai-feedback-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
