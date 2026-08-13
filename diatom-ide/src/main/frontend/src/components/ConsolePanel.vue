<template>
  <div class="console-panel" :style="{ height: height + 'px' }" role="region" aria-label="Output Panel">
    <!-- Resize handle -->
    <div class="console-resize-handle"
         @mousedown="$emit('resize-start', $event)"
         role="separator"
         aria-orientation="horizontal"
         aria-label="Resize output panel"></div>

    <!-- Tab bar -->
    <div class="console-tabs" role="tablist" aria-label="Output tabs">
      <button v-for="tab in availableTabs" :key="tab.id"
              class="console-tab"
              :class="{ active: activeTab === tab.id }"
              role="tab"
              :aria-selected="activeTab === tab.id"
              :aria-label="tab.label"
              @click="$emit('switch-tab', tab.id)">
        {{ tab.label }}
      </button>
      <div class="console-tab-spacer"></div>
      <button class="console-action-btn"
              :title="t('clear')"
              :aria-label="t('clear')"
              @click="$emit('clear')">
        🗑
      </button>
    </div>

    <!-- Console output -->
    <div v-show="activeTab === 'console'"
         class="console-body"
         ref="consoleBody"
         role="log"
         aria-live="polite"
         aria-label="Console output">
      <div v-if="lines.length === 0" class="console-hint">{{ t('runCodeHint') }}</div>
      <div v-for="(line, i) in lines" :key="i"
           class="console-line"
           :class="line.type">
        {{ line.text }}
      </div>
    </div>

    <!-- Problems panel -->
    <div v-show="activeTab === 'problems'"
         class="problems-body"
         role="table"
         aria-label="Problems">
      <div v-if="problems.length === 0" class="console-hint">{{ t('noProblems') }}</div>
      <div v-for="(p, i) in problems" :key="i"
           class="problem-item"
           role="row"
           tabindex="0"
           @click="$emit('go-to-problem', p)"
           @keydown.enter="$emit('go-to-problem', p)">
        <span class="problem-icon" aria-hidden="true">{{ p.severity === 'error' ? '❌' : '⚠️' }}</span>
        <span class="problem-desc">{{ p.message }}</span>
        <span class="problem-file">{{ p.resource }}</span>
        <span class="problem-line">{{ t('line') }} {{ p.line }}</span>
      </div>
    </div>

    <!-- Terminal -->
    <div v-show="activeTab === 'terminal'"
         class="terminal-body"
         role="region"
         aria-label="Terminal">
      <slot name="terminal"></slot>
    </div>

    <!-- Git -->
    <div v-show="activeTab === 'git'"
         class="git-body"
         role="region"
         aria-label="Git">
      <slot name="git"></slot>
    </div>
  </div>
</template>

<script setup>
import { t } from '../i18n.js'

defineProps({
  height: { type: Number, default: 200 },
  activeTab: { type: String, default: 'console' },
  lines: { type: Array, default: () => [] },
  problems: { type: Array, default: () => [] }
})

defineEmits(['switch-tab', 'clear', 'go-to-problem', 'resize-start'])

const availableTabs = [
  { id: 'console', label: 'Console' },
  { id: 'problems', label: 'Problems' },
  { id: 'terminal', label: 'Terminal' },
  { id: 'git', label: 'Git' }
]
</script>
