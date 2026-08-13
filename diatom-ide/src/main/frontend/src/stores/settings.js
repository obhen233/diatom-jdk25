/**
 * Settings Store - Manages IDE settings, theme, and auth state.
 * Extracted from App.vue to address issue 6.2 (state management).
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import http from '../utils/http'

export const useSettingsStore = defineStore('settings', () => {
  // === Auth State ===
  const authToken = ref(sessionStorage.getItem('ide-auth-token') || '')
  const authUser = ref(sessionStorage.getItem('ide-auth-user') || '')
  const showLogin = ref(!authToken.value)

  // === Theme ===
  const isDark = ref(true)
  const themeClass = computed(() => isDark.value ? 'theme-dark' : 'theme-light')

  // === IDE Settings ===
  const ideSettings = ref({
    theme: 'dark',
    language: 'en',
    jdkVersion: 25,
    javaHome: '',
    mavenHome: '',
    mavenUserSettings: '',
    mavenLocalRepository: '',
    gradleUserHome: '',
    gitPath: '',
    svnPath: '',
    aiApiUrl: '',
    aiApiToken: '',
    aiModel: '',
    aiEnabled: false
  })

  // === Actions ===
  function setAuth(token, user) {
    authToken.value = token
    authUser.value = user
    showLogin.value = false
    // Issue 6.10: Use sessionStorage instead of localStorage for tokens
    sessionStorage.setItem('ide-auth-token', token)
    sessionStorage.setItem('ide-auth-user', user)
  }

  function clearAuth() {
    authToken.value = ''
    authUser.value = ''
    showLogin.value = true
    sessionStorage.removeItem('ide-auth-token')
    sessionStorage.removeItem('ide-auth-user')
  }

  function toggleTheme() {
    isDark.value = !isDark.value
    ideSettings.value.theme = isDark.value ? 'dark' : 'light'
  }

  async function loadSettings() {
    try {
      const { data } = await http.get('/settings')
      if (data && typeof data === 'object') {
        Object.assign(ideSettings.value, data)
        isDark.value = ideSettings.value.theme !== 'light'
      }
    } catch (e) {
      console.warn('Failed to load settings:', e.message)
    }
  }

  async function saveSettings() {
    try {
      await http.post('/settings', ideSettings.value)
      return true
    } catch (e) {
      console.error('Failed to save settings:', e.message)
      return false
    }
  }

  return {
    authToken, authUser, showLogin,
    isDark, themeClass,
    ideSettings,
    setAuth, clearAuth, toggleTheme, loadSettings, saveSettings
  }
})
