<template>
  <div class="header-bar" :class="themeClass" role="toolbar" aria-label="IDE Toolbar">
    <span class="header-brand" aria-hidden="true">☕ {{ t('javaIde') }}</span>
    <div class="tool-sep" aria-hidden="true"></div>

    <button class="tool-btn" :title="t('saveTitle')" aria-label="Save file (Ctrl+S)" @click="$emit('save')">
      <span aria-hidden="true">💾</span>
    </button>
    <div class="tool-sep" aria-hidden="true"></div>

    <!-- Run button group -->
    <div class="run-group" role="group" aria-label="Run controls">
      <button class="tool-btn run-btn"
              :title="t('run') + ' (Ctrl+F11)'"
              :aria-label="t('run')"
              @click="$emit('run')"
              :disabled="loading"
              :aria-busy="loading">
        <span v-if="!loading" aria-hidden="true">▶</span>
        <span v-else class="spinner" aria-hidden="true"></span>
      </button>
      <button class="tool-btn run-config-btn"
              :title="t('runConfig')"
              :aria-label="t('runConfig')"
              aria-haspopup="true"
              @click="$emit('toggle-run-config')">
        ▾
      </button>
    </div>

    <button class="tool-btn stop-btn"
            :title="t('stop')"
            :aria-label="t('stop')"
            :disabled="!loading"
            @click="$emit('stop')">
      <span aria-hidden="true">⬛</span>
    </button>

    <button class="tool-btn rebuild-btn"
            :title="t('rebuild')"
            :aria-label="t('rebuild')"
            @click="$emit('rebuild')"
            :disabled="loading">
      <span aria-hidden="true">🔄</span>
    </button>
    <div class="tool-sep" aria-hidden="true"></div>

    <button class="tool-btn"
            :title="t('search') + ' (Ctrl+T)'"
            :aria-label="t('search')"
            @click="$emit('search')">
      <span aria-hidden="true">🔍</span>
    </button>
    <div class="tool-sep" aria-hidden="true"></div>

    <button class="tool-btn"
            :title="t('settings')"
            :aria-label="t('settings')"
            @click="$emit('open-settings')">
      <span aria-hidden="true">⚙</span>
    </button>

    <div class="header-spacer"></div>

    <!-- Run config summary -->
    <span class="run-config-label" v-if="mainClass"
          @click="$emit('toggle-run-config')"
          :title="t('clickEditRunConfig')"
          role="button"
          tabindex="0"
          @keydown.enter="$emit('toggle-run-config')">
      {{ mainClass.split('.').pop() }}
    </span>

    <button class="menu-item theme-toggle"
            @click="$emit('toggle-theme')"
            :title="isDark ? t('switchToLight') : t('switchToDark')"
            :aria-label="isDark ? t('switchToLight') : t('switchToDark')">
      {{ isDark ? '☀️' : '🌙' }}
    </button>

    <select class="lang-select"
            :value="getLang()"
            @change="setLang($event.target.value)"
            :title="t('languageLabel')"
            :aria-label="t('languageLabel')">
      <option value="en">EN</option>
      <option value="zh">中文</option>
    </select>

    <span class="menu-item user-info" v-if="username" :title="t('currentUser') + ': ' + username" aria-live="polite">
      👤 {{ username }}
    </span>

    <button class="menu-item logout-btn" v-if="username"
            @click="$emit('logout')"
            :title="t('logout')"
            :aria-label="t('logout')">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
        <polyline points="16 17 21 12 16 7"/>
        <line x1="21" y1="12" x2="9" y2="12"/>
      </svg>
    </button>
  </div>
</template>

<script setup>
import { t, setLang, getLang } from '../i18n.js'

defineProps({
  loading: Boolean,
  isDark: Boolean,
  themeClass: String,
  username: String,
  mainClass: String
})

defineEmits([
  'save', 'run', 'stop', 'rebuild', 'search',
  'open-settings', 'toggle-run-config', 'toggle-theme', 'logout'
])
</script>
