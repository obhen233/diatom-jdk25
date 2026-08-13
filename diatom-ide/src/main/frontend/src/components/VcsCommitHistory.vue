<template>
  <div class="vcs-commit-history">
    <div v-if="!project" class="history-empty">
      {{ t('selectProject') }}
    </div>
    <div v-else-if="!initialized" class="history-empty">
      {{ t('gitNotInit') }}
    </div>
    <template v-else>
      <!-- Commit log header -->
      <div class="history-header">
        <span class="history-title">{{ t('commitHistory') }}</span>
        <span class="history-refresh" @click="$emit('refresh')" :title="t('vcsSidebar.actions.refresh')">🔄</span>
      </div>

      <!-- Commit list -->
      <div class="history-list" ref="historyListRef">
        <div v-if="commits.length === 0 && !loading" class="history-empty-list">
          {{ t('noCommits') }}
        </div>
        <div
          v-for="commit in commits"
          :key="commit.id"
          class="commit-item"
          @dblclick="$emit('view-diff', commit.id)"
        >
          <div class="commit-main">
            <span class="commit-id" :title="commit.id">{{ commit.shortId }}</span>
            <span class="commit-message">{{ commit.message.split('\n')[0] }}</span>
          </div>
          <div class="commit-meta">
            <span class="commit-author">{{ commit.author }}</span>
            <span class="commit-time">{{ commit.timeStr }}</span>
          </div>
          <div class="commit-actions">
            <span
              class="commit-action"
              :title="t('cherryPick')"
              @click.stop="$emit('cherry-pick', commit.id)"
            >🍒</span>
            <span
              class="commit-action"
              :title="t('viewDiff')"
              @click.stop="$emit('view-diff', commit.id)"
            >📄</span>
          </div>
        </div>
        <div v-if="loading" class="history-loading">
          {{ t('loading') }}...
        </div>
      </div>

      <!-- Load more -->
      <div v-if="hasMore" class="history-load-more" @click="$emit('load-more')">
        {{ t('loadMore') }}
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { t } from '../i18n.js'

const props = defineProps({
  project: { type: String, default: null },
  initialized: { type: Boolean, default: false },
  commits: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  hasMore: { type: Boolean, default: false }
})

const emit = defineEmits(['refresh', 'cherry-pick', 'view-diff', 'load-more'])

const historyListRef = ref(null)

// Setup infinite scroll
watch(() => props.commits, () => {
  if (!historyListRef.value) return
  historyListRef.value.onscroll = () => {
    if (props.loading) return
    const el = historyListRef.value
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 50 && props.hasMore) {
      emit('load-more')
    }
  }
})
</script>

<style scoped>
.vcs-commit-history {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.history-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary);
  font-size: 13px;
}

.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-secondary);
}

.history-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-primary);
}

.history-refresh {
  cursor: pointer;
  font-size: 12px;
  opacity: 0.7;
}

.history-refresh:hover {
  opacity: 1;
}

.history-list {
  flex: 1;
  overflow-y: auto;
}

.history-empty-list {
  padding: 24px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 12px;
}

.history-loading {
  padding: 12px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 12px;
}

.commit-item {
  padding: 8px 12px;
  border-bottom: 1px solid var(--border-color);
  cursor: pointer;
}

.commit-item:hover {
  background: var(--hover-bg);
}

.commit-main {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.commit-id {
  font-family: monospace;
  font-size: 11px;
  color: var(--accent-color);
  background: var(--accent-bg);
  padding: 1px 6px;
  border-radius: 3px;
}

.commit-message {
  font-size: 12px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.commit-meta {
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.commit-actions {
  display: flex;
  gap: 8px;
}

.commit-action {
  cursor: pointer;
  font-size: 12px;
  opacity: 0.6;
}

.commit-action:hover {
  opacity: 1;
}

.history-load-more {
  padding: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--accent-color);
  cursor: pointer;
}

.history-load-more:hover {
  background: var(--hover-bg);
}
</style>
