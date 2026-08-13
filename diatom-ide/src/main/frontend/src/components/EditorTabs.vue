<template>
  <div class="tab-bar" role="tablist" aria-label="Open files">
    <div v-for="tab in tabs" :key="tab.key"
         class="tab-item"
         :class="{ active: currentTab === tab.key, dirty: dirtyTabs[tab.key] }"
         role="tab"
         :aria-selected="currentTab === tab.key"
         :aria-label="tab.label + (dirtyTabs[tab.key] ? ' (unsaved)' : '')"
         tabindex="0"
         @click="$emit('switch-tab', tab.key)"
         @contextmenu.prevent="$emit('tab-context-menu', { event: $event, key: tab.key })"
         @keydown.enter="$emit('switch-tab', tab.key)">
      <span class="tab-icon" aria-hidden="true">☕</span>
      <span class="tab-label">{{ tab.label }}</span>
      <span class="tab-dirty" v-if="dirtyTabs[tab.key]" aria-hidden="true">●</span>
      <span class="tab-close"
            role="button"
            :aria-label="'Close ' + tab.label"
            tabindex="0"
            @click.stop="$emit('close-tab', tab.key)"
            @keydown.enter.stop="$emit('close-tab', tab.key)">✕</span>
    </div>
  </div>
</template>

<script setup>
defineProps({
  tabs: { type: Array, required: true },
  currentTab: { type: String, default: '' },
  dirtyTabs: { type: Object, default: () => ({}) }
})

defineEmits(['switch-tab', 'close-tab', 'tab-context-menu'])
</script>
