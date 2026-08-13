<template>
  <div v-if="visible" class="search-overlay" @click.self="$emit('close')" role="dialog" aria-modal="true" aria-label="Search">
    <div class="search-dialog" @keydown.escape="$emit('close')">
      <div class="search-header">
        <div class="search-tabs" role="tablist">
          <button v-for="tab in searchTabs" :key="tab.id"
                  class="search-tab"
                  :class="{ active: searchType === tab.id }"
                  role="tab"
                  :aria-selected="searchType === tab.id"
                  @click="$emit('change-type', tab.id)">
            {{ tab.label }}
          </button>
        </div>
      </div>
      <div class="search-input-row">
        <input ref="searchInput"
               class="search-input"
               :value="query"
               @input="$emit('update:query', $event.target.value)"
               :placeholder="placeholder"
               aria-label="Search query"
               autocomplete="off"
               @keydown.down.prevent="$emit('select-next')"
               @keydown.up.prevent="$emit('select-prev')"
               @keydown.enter="$emit('open-selected')" />
      </div>
      <div class="search-results" ref="searchResults" role="listbox" aria-label="Search results">
        <div v-if="loading" class="search-hint">{{ t('searching') }}</div>
        <div v-else-if="results.length === 0 && query" class="search-hint">{{ t('noMatch') }}</div>
        <div v-else-if="!query" class="search-hint">{{ t('typeToSearch') }}</div>
        <div v-for="(item, i) in results" :key="i"
             class="search-result-item"
             :class="{ selected: selectedIndex === i }"
             role="option"
             :aria-selected="selectedIndex === i"
             @click="$emit('open-result', item)">
          <span class="result-icon" aria-hidden="true">{{ item.icon }}</span>
          <span class="result-name">{{ item.name }}</span>
          <span class="result-detail" v-if="item.detail">{{ item.detail }}</span>
          <span class="result-path">{{ item.path }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { t } from '../i18n.js'

const props = defineProps({
  visible: Boolean,
  query: { type: String, default: '' },
  searchType: { type: String, default: 'all' },
  results: { type: Array, default: () => [] },
  selectedIndex: { type: Number, default: 0 },
  loading: Boolean,
  placeholder: { type: String, default: '' }
})

defineEmits(['close', 'update:query', 'change-type', 'open-result', 'select-next', 'select-prev', 'open-selected'])

const searchInput = ref(null)

const searchTabs = [
  { id: 'all', label: t('allType') },
  { id: 'file', label: t('fileType') },
  { id: 'symbol', label: t('symbolType') }
]

// Auto-focus input when dialog opens
watch(() => props.visible, (val) => {
  if (val) {
    nextTick(() => {
      if (searchInput.value) searchInput.value.focus()
    })
  }
})
</script>
