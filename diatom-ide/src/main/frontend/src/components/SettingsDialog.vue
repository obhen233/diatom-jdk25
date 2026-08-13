<template>
  <div v-if="visible" class="dialog-overlay" @click.self="$emit('close')" role="dialog" aria-modal="true" :aria-label="t('settingsTitle')">
    <div class="settings-panel" @keydown.escape="$emit('close')">
      <div class="settings-header">
        <h2>{{ t('settingsTitle') }}</h2>
        <button class="settings-close" @click="$emit('close')" :aria-label="t('close')">✕</button>
      </div>
      <div class="settings-body">
        <!-- Appearance -->
        <section class="settings-section" aria-labelledby="settings-appearance">
          <h3 id="settings-appearance">{{ t('appearance') }}</h3>
          <div class="settings-row">
            <label for="setting-theme">{{ t('theme') }}</label>
            <select id="setting-theme" :value="settings.theme" @change="updateSetting('theme', $event.target.value)">
              <option value="dark">{{ t('themeDark') }}</option>
              <option value="light">{{ t('themeLight') }}</option>
            </select>
          </div>
        </section>

        <!-- Compiler -->
        <section class="settings-section" aria-labelledby="settings-compiler">
          <h3 id="settings-compiler">{{ t('compiler') }}</h3>
          <div class="settings-row">
            <label for="setting-jdk">{{ t('outputJdkVersion') }}</label>
            <select id="setting-jdk" :value="settings.jdkVersion" @change="updateSetting('jdkVersion', parseInt($event.target.value))">
              <option v-for="v in jdkVersions" :key="v" :value="v">JDK {{ v }}</option>
            </select>
          </div>
        </section>

        <!-- Environment -->
        <section class="settings-section" aria-labelledby="settings-env">
          <h3 id="settings-env">{{ t('envConfig') }}</h3>
          <div class="settings-row">
            <label for="setting-java-home">{{ t('javaHome') }}</label>
            <input id="setting-java-home" :value="settings.javaHome" @input="updateSetting('javaHome', $event.target.value)" :placeholder="t('javaHomeHint')" />
          </div>
          <div class="settings-row">
            <label for="setting-maven-home">{{ t('mavenHome') }}</label>
            <input id="setting-maven-home" :value="settings.mavenHome" @input="updateSetting('mavenHome', $event.target.value)" :placeholder="t('mavenHomeHint')" />
          </div>
          <div class="settings-row">
            <label for="setting-maven-settings">{{ t('mavenUserSettings') }}</label>
            <input id="setting-maven-settings" :value="settings.mavenUserSettings" @input="updateSetting('mavenUserSettings', $event.target.value)" :placeholder="t('mavenSettingsHint')" />
          </div>
          <div class="settings-row">
            <label for="setting-maven-repo">{{ t('localRepository') }}</label>
            <input id="setting-maven-repo" :value="settings.mavenLocalRepository" @input="updateSetting('mavenLocalRepository', $event.target.value)" :placeholder="t('mavenRepoHint')" />
          </div>
          <div class="settings-row">
            <label for="setting-gradle-home">{{ t('gradleUserHome') }}</label>
            <input id="setting-gradle-home" :value="settings.gradleUserHome" @input="updateSetting('gradleUserHome', $event.target.value)" :placeholder="t('gradleHomeHint')" />
          </div>
        </section>

        <!-- Version Control -->
        <section class="settings-section" aria-labelledby="settings-vcs">
          <h3 id="settings-vcs">{{ t('versionControl') }}</h3>
          <div class="settings-row">
            <label for="setting-git">{{ t('gitPath') }}</label>
            <input id="setting-git" :value="settings.gitPath" @input="updateSetting('gitPath', $event.target.value)" :placeholder="t('pathAutoHint')" />
          </div>
          <div class="settings-row">
            <label for="setting-svn">{{ t('svnPath') }}</label>
            <input id="setting-svn" :value="settings.svnPath" @input="updateSetting('svnPath', $event.target.value)" :placeholder="t('pathAutoHint')" />
          </div>
        </section>

        <!-- AI Assistant -->
        <section class="settings-section" aria-labelledby="settings-ai">
          <h3 id="settings-ai">{{ t('aiAssistant') }}</h3>
          <div class="settings-row">
            <label for="setting-ai-enabled">{{ t('enableAi') }}</label>
            <select id="setting-ai-enabled" :value="settings.aiEnabled ? 'true' : 'false'" @change="updateSetting('aiEnabled', $event.target.value === 'true')">
              <option value="true">{{ t('enabled') }}</option>
              <option value="false">{{ t('disabled') }}</option>
            </select>
          </div>
          <div class="settings-row">
            <label for="setting-ai-url">{{ t('aiApiUrl') }}</label>
            <input id="setting-ai-url" :value="settings.aiApiUrl" @input="updateSetting('aiApiUrl', $event.target.value)" :placeholder="t('aiApiUrlHint')" />
          </div>
          <div class="settings-row">
            <label for="setting-ai-token">{{ t('apiToken') }}</label>
            <input id="setting-ai-token" type="password" :value="settings.aiApiToken" @input="updateSetting('aiApiToken', $event.target.value)" />
          </div>
          <div class="settings-row">
            <label for="setting-ai-model">{{ t('aiModel') }}</label>
            <input id="setting-ai-model" :value="settings.aiModel" @input="updateSetting('aiModel', $event.target.value)" :placeholder="t('aiModelHintFull')" />
          </div>
        </section>
      </div>
      <div class="settings-footer">
        <button class="dialog-btn primary" @click="$emit('save')">{{ t('saveBtn') }}</button>
        <button class="dialog-btn" @click="$emit('close')">{{ t('cancel') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { t } from '../i18n.js'

const props = defineProps({
  visible: Boolean,
  settings: { type: Object, required: true }
})

const emit = defineEmits(['close', 'save', 'update:settings'])

const jdkVersions = Array.from({ length: 21 }, (_, i) => i + 5)

function updateSetting(key, value) {
  emit('update:settings', { ...props.settings, [key]: value })
}
</script>
