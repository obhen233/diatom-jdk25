<template>
  <div class="xterm-wrapper">
    <div v-if="aiProgress" class="ai-progress-bar" :class="{ 'ai-progress-dark': isDark }" @click="toggleProgressDetail">
      <span class="ai-progress-label">[AI]</span>
      <span v-if="aiProgress.current" class="ai-progress-current">
        {{ getProgressStatusText(aiProgress.current.status) }}{{ aiProgress.current.target ? ': ' + shortPath(aiProgress.current.target) : '' }}
      </span>
      <span v-else-if="aiProgress.history && aiProgress.history.length > 0" class="ai-progress-current">
        {{ getProgressStatusText(aiProgress.history[aiProgress.history.length - 1].status) }}{{ aiProgress.history[aiProgress.history.length - 1].target ? ': ' + shortPath(aiProgress.history[aiProgress.history.length - 1].target) : '' }}
      </span>
      <span v-if="aiProgress.history && aiProgress.history.length > 0" class="ai-progress-history">
        <span v-for="(item, idx) in aiProgress.history.slice(-3)" :key="idx" class="ai-progress-item">
          <span class="ai-progress-icon">{{ getStatusIcon(item.status) }}</span>
          <span v-if="item.target" class="ai-progress-item-target ai-progress-target-clickable" @click.stop="openFileFromTarget(item.target)" :title="item.target">{{ shortPath(item.target) }}</span>
        </span>
        <span v-if="aiProgress.history.length > 3" class="ai-progress-more">
          +{{ aiProgress.history.length - 3 }}
        </span>
      </span>
      <span class="ai-progress-changes" v-if="projectFileChanges.length > 0" @click.stop="toggleDiffPanel" title="View file changes">
        <span class="changes-badge">{{ projectFileChanges.length }}</span> Changes
      </span>
      <span v-if="hasHelperFiles" class="ai-progress-helper-toggle" @click.stop="aiHelperFileFilter = !aiHelperFileFilter" :title="aiHelperFileFilter ? 'Show helper files' : 'Hide helper files'">
        <span class="helper-toggle-icon">{{ aiHelperFileFilter ? '⊘' : '⚙' }}</span>
      </span>
      <span class="ai-progress-toggle">{{ aiProgressExpanded ? '▲' : '▼' }}</span>
    </div>
    <!-- Standalone Changes badge (visible even when AI progress bar is hidden) -->
    <div v-if="!aiProgress && fileChangesForDisplay.length > 0" class="ai-standalone-changes" :class="{ 'ai-progress-dark': isDark }" @click="scrollToDiff">
      <span class="ai-standalone-changes-label">{{ t('aiProgress') }}</span>
      <span class="changes-badge">{{ projectFileChanges.length }}</span> Changes
      <span class="ai-progress-toggle">▼</span>
    </div>
    <!-- AI Progress Detail Panel (expandable) -->
    <div v-if="aiProgress && aiProgressExpanded" class="ai-progress-detail" :class="{ 'ai-progress-dark': isDark }">
      <div class="ai-progress-detail-header">
        <span class="ai-progress-detail-title">{{ t('aiProgress') }} ({{ progressHistory.length }})</span>
      </div>
      <div class="ai-progress-detail-list">
        <div v-for="(item, idx) in progressHistory" :key="idx" class="ai-progress-detail-item">
          <span class="ai-progress-detail-icon">{{ getStatusIcon(item.status) }}</span>
          <span class="ai-progress-detail-tool">{{ getProgressStatusText(item.status) }}</span>
          <span class="ai-progress-detail-target ai-progress-target-clickable" @click.stop="openFileFromTarget(item.target)" :title="item.target">{{ item.target }}</span>
          <span v-if="item.detail" class="ai-progress-detail-extra">{{ item.detail }}</span>
        </div>
        <div v-if="aiProgress.current" class="ai-progress-detail-item current">
          <span class="ai-progress-detail-icon spinning">◌</span>
          <span class="ai-progress-detail-tool">{{ getProgressStatusText(aiProgress.current.status) }}</span>
          <span class="ai-progress-detail-target ai-progress-target-clickable" @click.stop="openFileFromTarget(aiProgress.current.target)" :title="aiProgress.current.target">{{ aiProgress.current.target }}</span>
          <span v-if="aiProgress.current.detail" class="ai-progress-detail-extra">{{ aiProgress.current.detail }}</span>
        </div>
      </div>
    </div>
    <!-- SCP upload progress bar -->
    <div v-if="scpProgress" class="scp-progress-bar" :class="{ 'scp-progress-dark': isDark }">
      <span class="scp-progress-label">[SCP]</span>
      <span class="scp-progress-step">{{ scpProgress.stepName }}</span>
      <span class="scp-progress-bar-wrap">
        <span class="scp-progress-fill" :style="{ width: scpProgress.percent + '%' }"></span>
      </span>
      <span class="scp-progress-percent">{{ scpProgress.percent }}%</span>
      <span class="scp-progress-size">{{ formatBytes(scpProgress.current) }} / {{ formatBytes(scpProgress.total) }}</span>
      <span class="scp-progress-speed">{{ formatSpeed(scpProgress.speedBps) }}</span>
    </div>
    <div v-if="fileChangesForDisplay.length > 0" class="ai-diff-wrapper" ref="aiDiffWrapper">
      <AiDiffPanel
        ref="aiDiffPanel"
        :changes="fileChangesForDisplay"
        :session-id="sessionId"
        @send-feedback="handleSendFeedback"
        @scroll-to-banner="scrollToBanner"
        @open-file="handleOpenFile"
      />
    </div>
    <div ref="terminalContainer" class="terminal-container"></div>
  </div>
</template>

<script>
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import http from '../utils/http'
import { t } from '../i18n.js'
import AiDiffPanel from './AiDiffPanel.vue'

const DARK_THEME = {
  background: '#1e1e1e',
  foreground: '#d4d4d4',
  cursor: '#d4d4d4',
  selectionBackground: '#264f78',
  black: '#000000',
  red: '#cd3131',
  green: '#0dbc79',
  yellow: '#e5e510',
  blue: '#2472c8',
  magenta: '#bc3fbc',
  cyan: '#11a8cd',
  white: '#e5e5e5',
  brightBlack: '#666666',
  brightRed: '#f14c4c',
  brightGreen: '#23d18b',
  brightYellow: '#f5f543',
  brightBlue: '#3b8eea',
  brightMagenta: '#d670d6',
  brightCyan: '#29b8db',
  brightWhite: '#e5e5e5'
}

const LIGHT_THEME = {
  background: '#ffffff',
  foreground: '#333333',
  cursor: '#333333',
  selectionBackground: '#add6ff',
  black: '#000000',
  red: '#a31515',
  green: '#008000',
  yellow: '#795e26',
  blue: '#0451a5',
  magenta: '#bc3fbc',
  cyan: '#0598bc',
  white: '#333333',
  brightBlack: '#666666',
  brightRed: '#a31515',
  brightGreen: '#008000',
  brightYellow: '#795e26',
  brightBlue: '#0451a5',
  brightMagenta: '#bc3fbc',
  brightCyan: '#0598bc',
  brightWhite: '#555555'
}

// Tab completion candidates for terminal commands — obsolete, path completion is now live

export default {
  name: 'XtermTerminal',
  props: {
    projectName: { type: String, default: '' },
    activeFile: { type: String, default: '' },
    isDark: { type: Boolean, default: true },
    authToken: { type: String, default: '' }
  },
  emits: ['resize-needed', 'apply-code', 'insert-code', 'refresh-project', 'refresh-editor', 'active-ai-task', 'open-file'],
  components: { AiDiffPanel },
  data() {
    return {
      terminal: null,
      fitAddon: null,
      cwd: '',
      commandHistory: [],
      aiCommandHistory: [],
      historyIdx: -1,
      currentInput: '',
      cursorPos: 0,
      dbHistory: [],
      dbHistoryLoaded: false,
      running: false,
      // Old AI state (keep for backward compat)
      lastAiCode: '',
      pendingAiOps: [],
      // New AI mode state
      aiMode: false,
      sessionId: '',
      pendingConfirm: null,     // { action, tool, sessionId } when waiting for confirm
      aiStreamActive: false,    // SSE stream in progress
      _aiFirstEvent: false,     // tracks if first SSE event received for clearing "Thinking..."
      _aiPromptShown: false,    // tracks if prompt was shown after stream (prevents double prompt)
      _aiStreamedText: '',      // text already rendered from think events
      _aiDisplayedAssistantText: '', // assistant text rendered as confirm context
      // WebSocket state
      ws: null,
      wsConnected: false,
      wsFallback: false,
      reconnectAttempts: 0,
      maxReconnectAttempts: 10,
      reconnectTimer: null,
      _pendingResolve: null,    // resolve function for pending command
      _pendingReject: null,    // reject function for pending command
      _execTimeoutId: null,    // timeout for exec command inactivity
      // Tab completion state
      tabCompletionCandidates: [],
      tabCompletionIndex: 0,
      _lastTabPrefix: '',       // tracks the prefix when tab was first pressed
      _pendingCompleteResolve: null,  // resolve for async path completion
      _tabCompletionPending: false,   // waiting for y/n response
      _tabCompletionListShown: false, // list was already shown (>10 case)
      // Terminal write lock to prevent concurrent writes
      _terminalBusy: false,
      // AI progress tracking
      aiProgress: null,  // { current: {tool, target, status}, history: [...] }
      aiProgressExpanded: false,
      // SCP upload progress tracking
      scpProgress: null, // { stepName, current, total, percent, speedBps }
      // Per-project AI state cache (key = projectName)
      projectsAiState: {},
      // AI file changes for diff display
      aiFileChanges: [],
      // Filter AI helper files (e.g. temp scripts outside src/ tree)
      aiHelperFileFilter: true,
      // Track diff panel visibility
      aiDiffExpanded: false,
      // Track where "Changes" button should scroll to next
      _scrollTarget: 'diff', // scroll direction tracking (unused, kept for backward compat)
      // Deploy pipeline state
      hasDeployYaml: false,
      deployRunning: false,
      pendingDeployConfirm: false,
      _fitTerminalScheduled: false
    }
  },
  computed: {
    progressHistory() {
      return this.aiProgress && Array.isArray(this.aiProgress.history) ? this.aiProgress.history : []
    },
    // Filter out AI helper/temp files (files outside src/test directories)
    projectFileChanges() {
      return this.aiFileChanges.filter(c => !this.isHelperFile(c.filePath))
    },
    // Show all changes or only project changes based on filter toggle
    fileChangesForDisplay() {
      return this.aiHelperFileFilter ? this.projectFileChanges : this.aiFileChanges
    },
    // Whether any helper files exist (controls visibility of the toggle button)
    hasHelperFiles() {
      return this.aiFileChanges.some(c => this.isHelperFile(c.filePath))
    }
  },
  watch: {
    projectName(newProjectName, oldProjectName) {
      if (newProjectName === oldProjectName) return
      this.cwd = ''
      this.commandHistory = []
      this.historyIdx = -1
      this.currentInput = ''
      // Save current AI state before switching
      this.saveAiState(oldProjectName)
      // Restore new project's AI state
      this.restoreAiState(newProjectName)
      // Clear terminal, but don't exit AI mode
      if (this.terminal) {
        this.terminal.clear()
      }
      // Replay buffered messages for the new project
      this.replayMessageBuffer(newProjectName)
      this.showPrompt()
      // After page refresh, in-memory cache is empty.
      // Re-query backend to check for active AI tasks on this project.
      if (this.wsConnected) {
        this.sendWs({
          type: 'query_active_ai',
          projectName: newProjectName
        })
      }
      // Check for deploy.yaml when switching projects
      this.detectDeployYaml()
    },
    isDark() {
      this.updateTheme()
    },
    authToken(newToken, oldToken) {
      if (newToken === oldToken) return
      this.disconnectWebSocket()
      this.wsFallback = false
      this.reconnectAttempts = 0
      this.connectWebSocket()
    }
  },
  mounted() {
    this.initTerminal()
    this._resizeObserver = new ResizeObserver(() => this.scheduleTerminalFit())
    if (this.$refs.terminalContainer) {
      this._resizeObserver.observe(this.$refs.terminalContainer)
    }
    // Observe the xterm-wrapper itself for layout changes (progress bar show/hide)
    if (this.$el) {
      this._resizeObserver.observe(this.$el)
    }
    // Also observe the parent wrapper for panel resize
    const wrapper = this.$el && this.$el.parentElement
    if (wrapper && wrapper !== this.$el) {
      this._resizeObserver.observe(wrapper)
    }
    this.$nextTick(() => this.scheduleTerminalFit())
    // Also retry after a short delay to handle late layout settles
    setTimeout(() => this.scheduleTerminalFit(), 300)
    window.addEventListener('resize', this.scheduleTerminalFit)
    // Connect WebSocket after App has validated auth token
    this.connectWebSocket()
  },
  beforeUnmount() {
    this.disconnectWebSocket()
    if (this._resizeObserver) {
      this._resizeObserver.disconnect()
      this._resizeObserver = null
    }
    window.removeEventListener('resize', this.scheduleTerminalFit)
    if (this.terminal) {
      if (this._onCopy) {
        this.terminal.element.removeEventListener('copy', this._onCopy)
        this._onCopy = null
      }
      if (this._onPaste) {
        this.terminal.element.removeEventListener('paste', this._onPaste, true)
        this._onPaste = null
      }
      this.terminal.dispose()
      this.terminal = null
    }
  },
  methods: {
    t,

    // ==================== WebSocket Connection ====================

    connectWebSocket() {
      if (this.wsFallback) return
      if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) return
      if (!this.authToken) return

      const token = this.authToken
      const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
      const host = window.location.host
      const url = `${protocol}://${host}/terminal-ws?_token=${encodeURIComponent(token)}`

      try {
        this.ws = new WebSocket(url)
        this.ws.onopen = () => {
          this.wsConnected = true
          this.reconnectAttempts = 0
          this.wsFallback = false
          // Query for active AI task on reconnect (for current project)
          this.sendWs({
            type: 'query_active_ai',
            projectName: this.projectName || undefined
          })
          // Check for deploy.yaml on reconnect
          this.detectDeployYaml()
        }

        this.ws.onmessage = (event) => {
          try {
            const msg = JSON.parse(event.data)
            this.handleWsMessage(msg)
          } catch (e) {
            console.warn('[Terminal] Failed to parse WebSocket message:', e)
          }
        }

        this.ws.onclose = (event) => {
          this.wsConnected = false
          if (!this.wsFallback) {
            this.scheduleReconnect()
          }
        }

        this.ws.onerror = () => {
          this.wsConnected = false
          // onerror will be followed by onclose, so we just let onclose handle reconnection
        }
      } catch (e) {
        console.warn('[Terminal] WebSocket connection failed, falling back to HTTP:', e)
        this.wsFallback = true
      }
    },

    scheduleReconnect() {
      if (this.reconnectAttempts >= this.maxReconnectAttempts) {
        console.warn('[Terminal] Max reconnect attempts reached, falling back to HTTP')
        this.wsFallback = true
        return
      }
      const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000)
      this.reconnectAttempts++
      this.reconnectTimer = setTimeout(() => {
        if (!this.wsConnected) {
          this.connectWebSocket()
        }
      }, delay)
    },

    disconnectWebSocket() {
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer)
        this.reconnectTimer = null
      }
      if (this.ws) {
        this.ws.onclose = null
        this.ws.onmessage = null
        this.ws.onerror = null
        if (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING) {
          this.ws.close()
        }
        this.ws = null
      }
      this.wsConnected = false
    },

    sendWs(msg) {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify(msg))
        return true
      }
      return false
    },

    // ==================== WebSocket Message Router ====================

    handleWsMessage(msg) {
      // Resolve effective sessionId: top-level for most messages, nested in data for progress
      let effectiveSessionId = msg.sessionId
      if (!effectiveSessionId && msg.type === 'progress' && msg.data && msg.data.sessionId) {
        effectiveSessionId = msg.data.sessionId
      }
      // If message has a sessionId that doesn't match current project's session,
      // buffer it for when the user switches back
      if (effectiveSessionId && effectiveSessionId !== this.sessionId) {
        this.bufferMessageForProject(msg, effectiveSessionId)
        return
      }
      const type = msg.type
      switch (type) {
        case 'stdout':
          this.handleWsStdout(msg)
          break
        case 'exit':
          this.handleWsExit(msg)
          break
        case 'error':
          this.handleWsError(msg)
          break
        case 'think':
          this.handleWsThink(msg)
          break
        case 'confirm':
          this.handleWsConfirm(msg)
          break
        case 'done':
          this.handleWsDone(msg)
          break
        case 'complete':
          this.handleWsComplete(msg)
          break
        case 'cancelled':
          this.handleWsCancelled(msg)
          break
        case 'active_ai':
          this.handleWsActiveAi(msg)
          break
        case 'progress':
          this.handleWsProgress(msg)
          break
        case 'file_change':
          this.handleFileChange(msg)
          break
        case 'deploy_detect_result':
          this.handleDeployDetectResult(msg)
          break
        case 'scp_progress':
          this.handleWsScpProgress(msg)
          break
        default:
          console.warn('[Terminal] Unknown message type:', type)
      }
    },

    handleWsStdout(msg) {
      const data = msg.data || msg.text || ''
      const normalized = data
        .replace(/\r\n/g, '\n')
        .replace(/\r/g, '')
        .replace(/\n/g, '\r\n')
      const output = normalized.startsWith('\r\n') ? normalized : '\r\n' + normalized
      if (this.aiMode) {
        this.writeAbovePrompt(output)
      } else {
        this.write(output)
      }
      // Reset inactivity timeout while command is still producing output
      if (this._execTimeoutId) {
        this._startExecTimeout()
      }
    },

    handleWsExit(msg) {
      this._clearExecTimeout()
      this.running = false
      const code = msg.code || 0
      if (code !== 0 && code !== undefined) {
        this.write(`\r\n\x1b[1;31m(exit code: ${code})\x1b[0m`)
      }
      if (msg.cwd) {
        this.cwd = msg.cwd
      }
      // Deploy pipeline completed
      if (this.deployRunning) {
        this.deployRunning = false
        if (code === 0) {
          this.write(`\r\n\x1b[1;32m=== Deploy Pipeline Completed ===\x1b[0m`)
        } else {
          this.write(`\r\n\x1b[1;31m=== Deploy Pipeline Failed ===\x1b[0m`)
        }
      }
      // Resolve pending promise if any
      if (this._pendingResolve) {
        this._pendingResolve({ exitCode: code, cwd: msg.cwd })
        this._pendingResolve = null
        this._pendingReject = null
      }
    },

    handleWsError(msg) {
      this._clearExecTimeout()
      this.running = false
      this.aiStreamActive = false
      this._aiPromptShown = true
      const message = msg.message || msg.text || 'Error'
      if (message === 'Cancelled') {
        console.log(`[AI] ${message}`)
      } else if (this.aiMode) {
        this.writeAbovePrompt(`\x1b[1;31m${message}\x1b[0m`)
      } else {
        this.write(`\r\n\x1b[1;31m${message}\x1b[0m`)
        this.showPrompt()
      }
      this.pendingConfirm = null
      if (this._pendingResolve) {
        this._pendingResolve({ exitCode: -1, error: message })
        this._pendingResolve = null
        this._pendingReject = null
      }
    },

    handleWsCancelled(msg) {
      console.log('[AI] Cancelled by user')
      this.running = false
      this.aiStreamActive = false
      this._aiPromptShown = true
      this.writeAbovePrompt('\x1b[33m[AI] Cancelled\x1b[0m')
      this.pendingConfirm = null
      if (this._pendingResolve) {
        this._pendingResolve({ exitCode: -1, error: 'Cancelled' })
        this._pendingResolve = null
        this._pendingReject = null
      }
    },

    handleWsActiveAi(msg) {
      if (msg.hasActive) {
        const projectName = msg.projectName
        if (projectName) {
          // Always update the cache (overwrite any stale entry)
          this.projectsAiState[projectName] = {
            sessionId: msg.sessionId,
            aiMode: true,
            aiStreamActive: true,
            pendingConfirm: null,
            _aiFirstEvent: false,
            _aiPromptShown: false,
            aiProgress: null,
            aiCommandHistory: [],
            lastAiCode: '',
            messageBuffer: [],
            aiStreamContent: ''
          }
          // If this project is the currently active one, restore AI mode immediately
          if (projectName === this.projectName && !this.aiMode) {
            this.aiMode = true
            this.sessionId = msg.sessionId
            this.aiStreamActive = true
            this.pendingConfirm = null
            this.write('\r\n\x1b[1;36m────────────────────────────────\x1b[0m')
            this.write('\r\n\x1b[1;36m  Diatom AI Mode \x1b[0m\x1b[2m(reconnected)\x1b[0m')
            this.write('\r\n  \x1b[1;33m/exit\x1b[0m ' + t('aiModeBannerExit') + '  |  \x1b[1;33m/ai help\x1b[0m ' + t('aiModeBannerHelp'))
            this.write('\r\n\x1b[1;36m────────────────────────────────\x1b[0m')
            this.showPrompt()
          }
        }
        // Notify parent component about active AI task
        this.$emit('active-ai-task', {
          sessionId: msg.sessionId,
          projectName: projectName
        })
      }
    },

    /**
     * Save current AI state to per-project cache.
     */
    saveAiState(projectName) {
      if (!projectName) return
      this.projectsAiState[projectName] = {
        sessionId: this.sessionId,
        aiMode: this.aiMode,
        aiStreamActive: this.aiStreamActive,
        pendingConfirm: this.pendingConfirm,
        aiProgress: this.aiProgress,
        _aiFirstEvent: this._aiFirstEvent,
        _aiPromptShown: this._aiPromptShown,
        aiCommandHistory: [...this.aiCommandHistory],
        lastAiCode: this.lastAiCode,
        messageBuffer: this.projectsAiState[projectName]?.messageBuffer || [],
        aiStreamContent: ''
      }
    },

    /**
     * Restore AI state from per-project cache.
     */
    restoreAiState(projectName) {
      const saved = this.projectsAiState[projectName]
      if (saved) {
        this.sessionId = saved.sessionId
        this.aiMode = saved.aiMode
        this.aiStreamActive = saved.aiStreamActive
        this.pendingConfirm = saved.pendingConfirm
        this.aiProgress = saved.aiProgress
        this._aiFirstEvent = saved._aiFirstEvent
        this._aiPromptShown = saved._aiPromptShown
        this.aiCommandHistory = saved.aiCommandHistory || []
        this.lastAiCode = saved.lastAiCode || ''
      } else {
        // No cache = this project has no AI task
        this.aiMode = false
        this.sessionId = ''
        this.aiStreamActive = false
        this.pendingConfirm = null
        this.aiProgress = null
        this._aiFirstEvent = false
        this._aiPromptShown = false
        this.aiCommandHistory = []
        this.lastAiCode = ''
      }
    },

    /**
     * Buffer a WebSocket message for a project that is not currently active.
     */
    bufferMessageForProject(msg, effectiveSessionId) {
      for (const [projectName, state] of Object.entries(this.projectsAiState)) {
        if (state.sessionId === effectiveSessionId) {
          if (!state.messageBuffer) state.messageBuffer = []
          state.messageBuffer.push(msg)
          return
        }
      }
    },

    /**
     * Replay buffered messages when switching back to a project.
     */
    replayMessageBuffer(projectName) {
      const state = this.projectsAiState[projectName]
      if (!state || !state.messageBuffer || state.messageBuffer.length === 0) return

      const buffer = state.messageBuffer
      state.messageBuffer = []  // Clear after replay

      for (const msg of buffer) {
        switch (msg.type) {
          case 'progress':
            this.handleWsProgress(msg)
            break
          case 'confirm':
            this.handleWsConfirm(msg)
            break
          case 'think':
            this.handleWsThink(msg)
            break
          case 'done':
            this.handleWsDone(msg)
            break
          case 'error':
            this.handleWsError(msg)
            break
          case 'cancelled':
            this.handleWsCancelled(msg)
            break
        }
      }
    },

    handleWsProgress(msg) {
      const data = msg.data || {}
      if (data.done) {
        // Task completed, clear progress
        this.aiProgress = null
        return
      }
      // Update progress state
      this.aiProgress = {
        current: data.current || null,
        history: data.history || []
      }
      // AI operation in progress, reset command inactivity timeout
      if (this._execTimeoutId) {
        this._startExecTimeout()
      }
    },

    handleFileChange(msg) {
      const data = msg.data || {}
      if (!data.filePath) return
      // Append to accumulated changes (deduplicate by path: replace the last entry for same file)
      const existingIdx = this.aiFileChanges.findIndex(c => c.filePath === data.filePath)
      const entry = {
        filePath: data.filePath,
        operation: data.operation || 'MODIFY',
        diff: data.diff || '',
        category: data.category || 'HELPER_SCRIPT',
        timestamp: data.timestamp || Date.now()
      }
      if (existingIdx >= 0) {
        this.aiFileChanges[existingIdx] = entry
      } else {
        this.aiFileChanges.push(entry)
      }
      // Re-fit terminal after DOM update since container size changed
      this.$nextTick(() => this.scheduleTerminalFit())
    },

    handleSendFeedback(text) {
      if (!text || !this.sessionId) return
      if (this.ws && this.wsConnected) {
        this.sendWs({
          type: 'ai_feedback',
          sessionId: this.sessionId,
          text: text
        })
      }
    },

    // ==================== Deploy Pipeline ====================

    detectDeployYaml() {
      if (!this.wsConnected || !this.projectName) {
        this.hasDeployYaml = false
        return
      }
      this.sendWs({
        type: 'deploy_detect',
        projectName: this.projectName
      })
    },

    handleDeployDetectResult(msg) {
      // Only handle results for the current project
      if (msg.projectName && msg.projectName !== this.projectName) return
      this.hasDeployYaml = msg.hasDeploy === true
    },

    handleWsScpProgress(msg) {
      const current = msg.current || 0
      const total = msg.total || 0
      const percent = total > 0 ? Math.floor((current * 100) / total) : 0
      this.scpProgress = {
        stepName: msg.stepName || '',
        current,
        total,
        percent,
        speedBps: msg.speedBps || 0
      }
      // Upload in progress, reset command inactivity timeout so large files don't time out
      if (this._execTimeoutId) {
        this._startExecTimeout()
      }
      if (current >= total && total > 0) {
        setTimeout(() => { this.scpProgress = null }, 1500)
      }
    },

    formatBytes(bytes) {
      if (!bytes) return '0 B'
      if (bytes < 1024) return bytes + ' B'
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
      if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
      return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB'
    },

    formatSpeed(bps) {
      return this.formatBytes(bps) + '/s'
    },

    startDeploy() {
      if (this.deployRunning || !this.projectName) return
      this.deployRunning = true
      // Write deploy start message to terminal
      this.write('\r\n\x1b[1;36m=== Starting Deploy Pipeline ===\x1b[0m\r\n')
      this.sendWs({
        type: 'deploy',
        projectName: this.projectName
      })
    },

    /**
     * Handle SSE events from deploy reconnect stream.
     * Called by App.vue doDeployReconnect() when reconnecting to a running deploy.
     */
    deployReconnectSseEvent(eventName, data) {
      switch (eventName) {
        case 'stdout':
          this.deployRunning = true
          this.handleWsStdout({ data })
          break
        case 'scp_progress':
          try {
            const parsed = JSON.parse(data)
            this.handleWsScpProgress(parsed)
          } catch (e) {
            console.warn('[Terminal] Failed to parse scp_progress:', e)
          }
          break
        case 'exit':
          try {
            const parsed = JSON.parse(data)
            const code = parsed.code || 0
            this.deployRunning = false
            if (code === 0) {
              this.write('\r\n\x1b[1;32m=== Deploy Pipeline Completed ===\x1b[0m')
            } else {
              this.write('\r\n\x1b[1;31m=== Deploy Pipeline Failed ===\x1b[0m')
            }
          } catch (e) {
            this.deployRunning = false
          }
          break
        case 'error':
          this.write('\r\n\x1b[1;31m' + data + '\x1b[0m')
          break
        case 'ping':
          // Keepalive, no action needed
          break
      }
    },

    getProgressStatusText(status) {
      const statusMap = {
        'reading': t('aiProgressReading'),
        'writing': t('aiProgressWriting'),
        'searching': t('aiProgressSearching'),
        'generating': t('aiProgressGenerating'),
        'completed': t('aiProgressCompleted')
      }
      return statusMap[status] || status
    },

    getStatusIcon(status) {
      const iconMap = {
        'reading': '📖',
        'writing': '✏️',
        'searching': '🔍',
        'generating': '⚙️',
        'completed': '✅'
      }
      return iconMap[status] || '◌'
    },

    toggleProgressDetail() {
      this.aiProgressExpanded = !this.aiProgressExpanded
      this.scheduleTerminalFit()
    },

    toggleDiffPanel() {
      this.$nextTick(() => {
        const el = this.$refs.aiDiffWrapper
        if (el) {
          el.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
        } else {
          console.warn('[Terminal] aiDiffWrapper ref not found, diff may not be rendered yet')
        }
      })
    },

    scrollToBanner() {
      this.$nextTick(() => {
        const el = this.$el?.querySelector('.ai-progress-bar')
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
      })
    },

    scrollToDiff() {
      this.$nextTick(() => {
        const el = this.$refs.aiDiffWrapper
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
      })
    },

    /** Emit open-file event to open the given target path in the editor */
    openFileFromTarget(target) {
      if (!target || !this.projectName) return
      // Backend sends target as "projectName/relative/path" — strip project prefix
      let filePath = target
      const prefix = this.projectName + '/'
      if (filePath.startsWith(prefix)) {
        filePath = filePath.substring(prefix.length)
      }
      const parts = filePath.split('/')
      const name = parts[parts.length - 1] || filePath
      this.$emit('open-file', {
        project: this.projectName,
        path: filePath,
        name: name
      })
    },

    /** Handle open-file event from AiDiffPanel */
    handleOpenFile(filePath) {
      if (!filePath || !this.projectName) return
      // Backend sends filePath as "projectName/relative/path" — strip project prefix
      let path = filePath
      const prefix = this.projectName + '/'
      if (path.startsWith(prefix)) {
        path = path.substring(prefix.length)
      }
      const parts = path.split('/')
      const name = parts[parts.length - 1] || path
      this.$emit('open-file', {
        project: this.projectName,
        path: path,
        name: name
      })
    },

    shortPath(path) {
      if (!path) return ''
      // Shorten path for display, keep last 2 segments
      const parts = path.split('/')
      if (parts.length > 2) {
        return '...' + parts.slice(-2).join('/')
      }
      return path
    },

    // Classify a file as "helper/temp" based on backend's FileCategory
    // Returns true for HELPER_SCRIPT, BUILD_ARTIFACT, AI_TEMP
    // Returns false for PROJECT_SOURCE, PROJECT_CONFIG
    isHelperFile(filePath) {
      if (!filePath) return false
      // Try to find the entry's category from aiFileChanges
      const entry = this.aiFileChanges.find(c => c.filePath === filePath)
      if (entry && entry.category) {
        return entry.category === 'HELPER_SCRIPT'
            || entry.category === 'BUILD_ARTIFACT'
            || entry.category === 'AI_TEMP'
      }
      // Fallback: path-based heuristic (keep existing logic as-is for now)
      const path = filePath.replace(/\\/g, '/')
      const parts = path.split('/')
      const basename = parts[parts.length - 1]

      if (parts.some(p => p.startsWith('.'))) return true
      const projectRootFiles = ['pom.xml', 'build.gradle', 'build.gradle.kts', 'settings.gradle',
        'settings.gradle.kts', 'package.json', 'package-lock.json', 'yarn.lock',
        'Cargo.toml', 'README.md', 'LICENSE', 'CHANGELOG.md',
        '.gitignore', 'Dockerfile', 'docker-compose.yml', 'docker-compose.yaml',
        'Makefile', 'CMakeLists.txt', 'mvnw', 'mvnw.cmd',
        '.editorconfig', '.env', '.env.example',
        'tsconfig.json', 'vite.config.ts', 'vite.config.js',
        'sonar-project.properties', 'requirements.txt']
      if (projectRootFiles.includes(basename)) return false
      if (path.includes('/src/') || path.includes('/test/') || path.includes('/resources/')) return false
      return true
    },

    renderMarkdown(text) {
      if (!text) return text
      let result = text

      // === Block-level: process whole text ===

      // Code blocks: ``` ... ``` — render with indent and dim border
      const codeBlocks = []
      result = result.replace(/```(\w*)\n?([\s\S]*?)```/g, (_m, lang, code) => {
        const key = '\x00CB' + codeBlocks.length + '\x00'
        codeBlocks.push({ lang, code: code.replace(/\n+$/, '') })
        return key
      })

      // Horizontal rules
      result = result.replace(/^[-*_]{3,}\s*$/gm, '\x1b[2m' + '─'.repeat(40) + '\x1b[0m')

      // === Table blocks: render as box-drawing tables ===
      // Must be done before per-line processing
      const TABLE_LINE = /^\|.+\|$/
      const TABLE_SEP = /^\|[\s\-:|]+\|$/
      const tableLines = result.split('\n')
      const tableRendered = []
      for (let ti = 0; ti < tableLines.length; ti++) {
        if (TABLE_LINE.test(tableLines[ti]) && ti + 1 < tableLines.length && TABLE_SEP.test(tableLines[ti + 1])) {
          const chunk = []
          while (ti < tableLines.length && TABLE_LINE.test(tableLines[ti])) {
            chunk.push(tableLines[ti])
            ti++
          }
          tableRendered.push(this.renderTableBlock(chunk))
          ti-- // compensate for loop increment
        } else {
          tableRendered.push(tableLines[ti])
        }
      }
      result = tableRendered.join('\n')

      // === Per-line processing ===
      const lines = result.split('\n')
      const processed = lines.map((line) => {
        let l = line

        // Headers: # ~ ######
        l = l.replace(/^#{1,6}\s+(.*)$/, '\x1b[1;33m$1\x1b[0m')

        // Blockquotes
        l = l.replace(/^>\s+(.*)$/, '\x1b[2m│ $1\x1b[0m')

        // Unordered list: - * +
        l = l.replace(/^(\s*)[-*+]\s+(.*)$/, '$1\x1b[33m•\x1b[0m $2')

        // Ordered list: 1. 2.
        l = l.replace(/^(\s*)(\d+\.\s+)(.*)$/, '$1\x1b[33m$2\x1b[0m$3')

        // Inline elements (within each line)

        // Inline code: `code`
        l = l.replace(/`([^`]+)`/g, '\x1b[36m$1\x1b[0m')

        // Images: ![alt](url)
        l = l.replace(/!\[([^\]]*)\]\([^)]+\)/g, '\x1b[3m[$1]\x1b[23m')

        // Links: [text](url)
        l = l.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '\x1b[4;34m$1\x1b[0m')

        // Bold: **text**  (must precede italic)
        l = l.replace(/\*\*([^*]+)\*\*/g, '\x1b[1m$1\x1b[22m')

        // Italic: *text*
        l = l.replace(/\*([^*]+)\*/g, '\x1b[3m$1\x1b[23m')

        // Strikethrough: ~~text~~
        l = l.replace(/~~([^~]+)~~/g, '\x1b[9m$1\x1b[29m')

        return l
      })
      result = processed.join('\n')

      // Restore code blocks
      result = result.replace(/\x00CB(\d+)\x00/g, (_m, idx) => {
        const b = codeBlocks[parseInt(idx)]
        let out = ''
        if (b.lang) out += '\x1b[2m[' + b.lang + ']\x1b[0m\n'
        out += b.code.split('\n').map(cl => '  \x1b[2m│\x1b[0m ' + cl).join('\n')
        return '\n' + out + '\n'
      })

      // Collapse multiple blank lines
      result = result.replace(/\n{3,}/g, '\n\n')
      return result
    },

    // Render a markdown table block as a box-drawing table
    renderTableBlock(tableBlock) {
      if (tableBlock.length < 2) return tableBlock.join('\n')
      const sepRow = tableBlock[1]
      const sepRowCells = sepRow.split('|')
      sepRowCells.shift() // remove leading empty
      sepRowCells.pop()   // remove trailing empty
      const sepParts = sepRowCells
      const cols = sepParts.length
      if (cols === 0) return tableBlock.join('\n')

      // Parse cells preserving empty middle cells (slice(1,-1) strips leading/trailing empties only)
      const parseCells = (line) => {
        const cells = line.split('|')
        cells.shift()
        cells.pop()
        return cells.map(c => c.trim())
      }

      const headerRow = tableBlock[0]
      const dataRows = tableBlock.slice(2)

      // Determine alignments from separator row colons
      const alignments = sepParts.map(part => {
        const t = part.trim()
        if (t.startsWith(':') && t.endsWith(':')) return 'center'
        if (t.endsWith(':')) return 'right'
        return 'left'
      })

      // Visual width of a string: CJK chars + emoji count as 2, others as 1
      const visualWidth = (s) => {
        if (!s) return 0
        let w = 0
        for (let i = 0; i < s.length; ) {
          const code = s.codePointAt(i)
          const isSurrogate = code > 0xFFFF
          if ((code >= 0x2E80 && code <= 0x2FFF) ||   // CJK Radicals
              (code >= 0x3000 && code <= 0x303F) ||   // CJK Symbols & Punctuation
              (code >= 0x4E00 && code <= 0x9FFF) ||   // CJK Unified Ideographs
              (code >= 0xF900 && code <= 0xFAFF) ||   // CJK Compatibility Ideographs
              (code >= 0xFF00 && code <= 0xFFEF) ||   // Fullwidth Forms
              (code >= 0x20000 && code <= 0x2FFFF) || // CJK Extension B+
              (code >= 0x2600 && code <= 0x27BF) ||   // Misc Symbols + Dingbats (☀★✅)
              (code >= 0x1F000 && code <= 0x1FAFF)) { // Mahjong/Emoji (🚀😊🩰)
            w += 2
          } else {
            w += 1
          }
          i += isSurrogate ? 2 : 1
        }
        return w
      }

      // Calculate column widths from all rows (using visual width)
      const allRows = [parseCells(headerRow), ...dataRows.map(dr => parseCells(dr))]
      const colWidths = Array(cols).fill(1)
      for (const row of allRows) {
        for (let c = 0; c < cols && c < row.length; c++) {
          colWidths[c] = Math.max(colWidths[c], visualWidth(row[c]))
        }
      }

      // Pad a cell value to target visual width
      const padCell = (text, width, align) => {
        if (!text) text = ''
        const textW = visualWidth(text)
        const totalPad = width - textW + 1 // +1 right margin
        switch (align) {
          case 'right': return ' '.repeat(totalPad) + text + ' '
          case 'center': {
            const l = Math.floor(totalPad / 2)
            return ' '.repeat(l) + text + ' '.repeat(totalPad - l) + ' '
          }
          default: return ' ' + text + ' '.repeat(totalPad)
        }
      }

      const border = (ch) => '┌' + colWidths.map(w => ch.repeat(w + 2)).join('┬') + '┐'
      const dim = s => '\x1b[2m' + s + '\x1b[0m'

      const lines = []
      lines.push(dim(border('─')))

      // Header row — bold cyan
      const hCells = parseCells(headerRow)
      const hPad = hCells.map((c, i) => padCell(c, colWidths[i], alignments[i]))
      lines.push('\x1b[1;36m│' + hPad.join('│') + '\x1b[1;36m│\x1b[0m')
      lines.push(dim('╞' + colWidths.map(w => '═'.repeat(w + 2)).join('╪') + '╡'))

      // Data rows
      for (let di = 0; di < dataRows.length; di++) {
        const rawCells = parseCells(dataRows[di])
        const cells = rawCells.map((c, ci) => padCell(c, colWidths[ci] || 1, alignments[ci] || 'left'))
        while (cells.length < cols) cells.push(' '.repeat((colWidths[cells.length] || 1) + 1))
        lines.push('│' + cells.join('│') + '│')
        if (di < dataRows.length - 1) {
          lines.push(dim('├' + colWidths.map(w => '─'.repeat(w + 2)).join('┼') + '┤'))
        }
      }

      lines.push(dim('└' + colWidths.map(w => '─'.repeat(w + 2)).join('┴') + '┘'))
      return lines.join('\n')
    },

    // Strip markdown to plain text (for internal comparisons in confirm handler)
    stripMarkdown(text) {
      if (!text) return text

      // Step 1: Normalize line endings and escape sequences
      let result = text
        .replace(/\\r\\n/g, '\n')
        .replace(/\\r/g, '\n')
        .replace(/\\n/g, '\n')
        .replace(/\\t/g, '    ')

      // Step 2: Handle fenced code blocks — keep content, remove backticks and language tags
      const codeBlocks = []
      result = result.replace(/```(\w*)\n?([\s\S]*?)```/g, (_m, lang, code) => {
        const key = '\x00CB' + codeBlocks.length + '\x00'
        codeBlocks.push(code.replace(/\n+$/, ''))
        return key
      })

      // Step 3: Handle tables — parse cells and join with spaces instead of discarding
      const TABLE_LINE = /^\|.+\|$/
      const TABLE_SEP = /^\|[\s\-:|]+\|$/
      const lines = result.split('\n')
      const processedLines = []
      for (let i = 0; i < lines.length; i++) {
        if (TABLE_LINE.test(lines[i]) && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1])) {
          // Collect table rows
          const tableRows = []
          while (i < lines.length && TABLE_LINE.test(lines[i])) {
            if (!TABLE_SEP.test(lines[i])) {
              // Parse cells: remove leading/trailing |, split
              const row = lines[i].substring(1, lines[i].length - 1)
              const cells = row.split('|').map(c => c.trim())
              // Filter empty cells at edges
              while (cells.length > 0 && cells[cells.length - 1] === '') cells.pop()
              tableRows.push(cells.join('  '))
            }
            i++
          }
          i-- // compensate for loop increment
          if (tableRows.length > 0) {
            processedLines.push(tableRows.join(' | '))
          }
        } else {
          processedLines.push(lines[i])
        }
      }
      result = processedLines.join('\n')

      // Step 4: Strip horizontal rules (must be after table step to avoid interference)
      result = result.replace(/^[-*_]{3,}\s*$/gm, '')

      // Step 5: Strip headings — keep text, remove # markers
      result = result.replace(/^#{1,6}\s+/gm, '')

      // Step 6: Strip blockquotes — keep content
      result = result.replace(/^>\s+/gm, '')

      // Step 7: Strip list markers — keep text with indent
      // Unordered: - * +
      result = result.replace(/^(\s*)[-*+]\s+/gm, '$1')
      // Ordered: 1. 2.
      result = result.replace(/^(\s*)\d+\.\s+/gm, '$1')

      // Step 8: Inline formatting — strip markers, keep text
      // Bold: **text**
      result = result.replace(/\*\*([^*]+)\*\*/g, '$1')
      // Italic: *text* (careful: don't eat bare asterisks used as bullets or emphasis)
      result = result.replace(/(?<!\*)\*([^*]+)\*(?!\*)/g, '$1')
      // Bold: __text__
      result = result.replace(/__([^_]+)__/g, '$1')
        // Italic: _text_
      result = result.replace(/(?<!_)_([^_]+)_(?!_)/g, '$1')
      // Strikethrough: ~~text~~
      result = result.replace(/~~([^~]+)~~/g, '$1')
      // Inline code: `code`
      result = result.replace(/`([^`]+)`/g, '$1')

      // Step 9: Links and images — keep alt/text, discard URL
      // Images: ![alt](url)
      result = result.replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
      // Links: [text](url)
      result = result.replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')

      // Step 10: Restore code blocks
      result = result.replace(/\x00CB(\d+)\x00/g, (_m, idx) => {
        return codeBlocks[parseInt(idx)]
      })

      // Step 11: Clean up
      // Remove empty lines that resulted from stripping
      result = result.replace(/^[ \t]+$/gm, '')
      // Collapse multiple blank lines
      result = result.replace(/\n{3,}/g, '\n\n')
      // Collapse multiple spaces
      result = result.replace(/[ \t]{3,}/g, '  ')
      return result.trim()
    },

    handleWsThink(msg) {
      // Ignore think messages from other sessions (e.g., stale responses after reconnect)
      if (msg.sessionId && msg.sessionId !== this.sessionId) {
        return
      }
      const text = msg.text || ''
      this._aiStreamedText += text
      // Render markdown to ANSI for better terminal readability
      const formatted = this.renderMarkdown(text)
      // Normalize \n to \r\n for xterm: xterm needs carriage return to go to column 0
      const normalized = formatted.replace(/\r\n/g, '\n').replace(/\r/g, '').replace(/\n/g, '\r\n')
      // Write above prompt, keeping prompt fixed at the bottom
      this.writeAbovePrompt(normalized)
    },

    handleWsConfirm(msg) {
      const assistantText = msg.assistantText || ''
      if (assistantText) {
        const formatted = this.renderMarkdown(assistantText)
          .replace(/\r\n/g, '\n').replace(/\r/g, '').replace(/\n/g, '\r\n')
        this.writeAbovePrompt(formatted)
      }
      this.pendingConfirm = {
        action: msg.action,
        tool: msg.tool,
        readableName: msg.readableName,
        operationDescription: msg.operationDescription,
        sessionId: msg.sessionId
      }
      // Erase prompt line and show confirm question in its place
      this.write('\r\x1b[K')
      const displayName = msg.readableName || msg.tool || ''
      const operationDesc = msg.operationDescription || msg.action || ''
      if (displayName || operationDesc) {
        const operationText = operationDesc ? `: ${operationDesc}` : ''
        this.write(`\x1b[1;33m${t('aiConfirmTitle')}${displayName}${operationText}\x1b[0m`)
      }
      this.write(`\r\n${t('aiConfirmOptions')}`)
      this.write(`\r\n\x1b[1;33m>\x1b[0m `)
      this.currentInput = ''
      this.cursorPos = 0
    },

    handleWsDone(msg) {
      const doneContent = msg.content || ''
      if (doneContent && !this._aiStreamedText) {
        const content = this.renderMarkdown(doneContent).replace(/\r\n/g, '\n').replace(/\r/g, '').replace(/\n/g, '\r\n')
        this.writeAbovePrompt(content)
      }
      this.pendingConfirm = null
      this._aiPromptShown = true
      this.aiStreamActive = false
      this.$emit('refresh-editor')
    },

    handleWsComplete(msg) {
      const candidates = (msg.candidates || []).filter(c => c && typeof c === 'string')
      if (this._pendingCompleteResolve) {
        this._pendingCompleteResolve(candidates)
        this._pendingCompleteResolve = null
      }
    },

    // ==================== Terminal Init ====================

    initTerminal() {
      this.fitAddon = new FitAddon()
      this.terminal = new Terminal({
        cursorBlink: true,
        cursorStyle: 'block',
        fontSize: 13,
        fontFamily: "'Consolas', 'Courier New', 'Segoe UI Emoji', 'Apple Color Emoji', 'Noto Color Emoji', monospace",
        lineHeight: 1.0,
        letterSpacing: 0,
        theme: this.isDark ? DARK_THEME : LIGHT_THEME,
        allowProposedApi: true,
        cols: 80,
        rows: 20
      })

      // Register Unicode 6 width provider for proper emoji/CJK rendering
      // xterm v6 defaults to Unicode 5 where emoji is treated as width=1,
      // causing half-displayed emoji. Unicode 6 properly handles emoji as width=2.
      try {
        // Shared wcwidth used by both wcwidth() and charProperties()
        const wcwidth6 = (codepoint) => {
          // 0 = null/combining, 1 = narrow, 2 = wide
          // CJK and emoji ranges: treat as width=2
          if (codepoint >= 0x1100 && codepoint <= 0x115F) return 2; // Hangul Jamo
          if (codepoint >= 0x2E80 && codepoint <= 0x33FF) return 2; // CJK Radicals/Symbols
          if (codepoint >= 0x3400 && codepoint <= 0x4DBF) return 2; // CJK Ext A
          if (codepoint >= 0x4E00 && codepoint <= 0xA4CF) return 2; // CJK Unified
          if (codepoint >= 0xA960 && codepoint <= 0xA97F) return 2; // Hangul Jamo EA
          if (codepoint >= 0xAC00 && codepoint <= 0xD7AF) return 2; // Hangul Syllables
          if (codepoint >= 0xD7B0 && codepoint <= 0xD7FF) return 2; // Hangul Jamo EB
          if (codepoint >= 0xF900 && codepoint <= 0xFAFF) return 2; // CJK Compat
          if (codepoint >= 0xFE10 && codepoint <= 0xFE1F) return 2; // Vertical Forms
          if (codepoint >= 0xFE30 && codepoint <= 0xFE6F) return 2; // CJK Compat Forms
          if (codepoint >= 0xFF01 && codepoint <= 0xFF60) return 2; // Fullwidth Forms
          if (codepoint >= 0xFFE0 && codepoint <= 0xFFE6) return 2; // Fullwidth Signs
          if (codepoint >= 0x1B000 && codepoint <= 0x1B12F) return 2; // Kana Supplement
          if (codepoint >= 0x1F000 && codepoint <= 0x1FAFF) return 2; // Mahjong/Emoji
          if (codepoint >= 0x2600 && codepoint <= 0x27BF) return 2; // Misc Symbols + Dingbats (☀★✅)
          if (codepoint >= 0x20000 && codepoint <= 0x2FFFF) return 2; // CJK Ext B+
          if (codepoint >= 0x30000 && codepoint <= 0x3FFFF) return 2; // CJK Ext G,H
          return 1;
        }

        this.terminal.unicode.register({
          version: '6',
          wcwidth: wcwidth6,
          // charProperties must encode width into property bits (bits 1-2)
          // xterm extracts width via: (value >> 1) & 3
          // For width=1: return 2, for width=2: return 4, for width=0: return 0
          charProperties: (codepoint, preceding) => {
            const w = wcwidth6(codepoint)
            // Encode width into bits 1-2, keep shouldJoin=false (bit 0 = 0)
            return (w & 3) << 1
          }
        })
        this.terminal.unicode.activeVersion = '6'
      } catch (e) {
        console.warn('[Terminal] Failed to register Unicode 6 width provider, emoji may display incorrectly:', e)
      }

      this.terminal.loadAddon(this.fitAddon)
      this.terminal.open(this.$refs.terminalContainer)

      // Set initial theme CSS variables
      this.applyThemeVars()

      // Handle copy event for proper newline preservation
      // This uses e.clipboardData which works reliably across browsers
      const onCopy = (e) => {
        const selection = this.terminal.getSelection()
        if (selection) {
          e.clipboardData.setData('text/plain', selection)
          e.preventDefault()
          setTimeout(() => this.terminal.clearSelection(), 0)
        }
      }
      this.terminal.element.addEventListener('copy', onCopy)
      this._onCopy = onCopy

      // Handle paste event — intercept in capture phase BEFORE xterm's textarea processes it.
      // xterm.js fires onData for each pasted character, which causes \r (Enter) to
      // prematurely trigger command execution for multi-line pastes.  By using capture
      // phase, we prevent xterm from seeing the paste at all and handle it ourselves.
      const onPaste = (e) => {
        const text = e.clipboardData ? e.clipboardData.getData('text/plain') :
                     (e.detail && e.detail.text ? e.detail.text : '')
        if (text) {
          e.preventDefault()
          e.stopPropagation()
          this._processPasteText(text)
        }
      }
      this.terminal.element.addEventListener('paste', onPaste, true) // capture phase!
      this._onPaste = onPaste

      // Enable Ctrl+C (copy) and Ctrl+V (paste) support
      this.terminal.attachCustomKeyEventHandler((e) => {
        // Ctrl+C - copy selection to clipboard (via copy event), or send SIGINT if no selection
        if (e.ctrlKey && e.key === 'c' && e.type === 'keydown') {
          const selection = this.terminal.getSelection()
          if (selection) {
            return false
          }
          // No selection - let xterm send \x03 (SIGINT)
          return true
        }
        // Ctrl+V - handled by keydown listener with clipboard API
        if (e.ctrlKey && e.key === 'v' && e.type === 'keydown') {
          return false
        }
        return true
      })

      // Handle Ctrl+V directly via clipboard API (more reliable than paste event)
      this.terminal.element.addEventListener('keydown', (e) => {
        if (e.ctrlKey && (e.key === 'v' || e.key === 'V') && !e.defaultPrevented) {
          e.preventDefault()
          e.stopPropagation()
          navigator.clipboard.readText().then(text => {
            if (text) this._processPasteText(text)
          }).catch(() => {
            // clipboard API not available - paste event fallback will handle it
          })
        }
      })

      this.writeln('\x1b[1;32mDiatom IDE Terminal\x1b[0m')
      this.writeln('Type commands and press Enter to execute. Type \x1b[1;33m/ai\x1b[0m to enter AI mode.\x1b[0m')
      this.showPrompt()

      this.terminal.onData(data => this.handleInput(data))
    },

    // Shared paste handler — used by both paste event and Ctrl+V key handler
    _processPasteText(text) {
      if (!text) return
      // In AI mode preserve newlines; in shell mode remove them (single-line input)
      const cleanText = this.aiMode ? text.replace(/\r?\n/g, '\r\n') : text.replace(/[\r\n]+/g, '')
      const before = this.currentInput.slice(0, this.cursorPos)
      const after = this.currentInput.slice(this.cursorPos)
      this.currentInput = before + cleanText + after
      this.cursorPos += cleanText.length
      // Write directly to terminal — xterm won't process it since we prevented default
      this.terminal.write(cleanText)
    },

    scheduleTerminalFit() {
      if (this._fitTerminalScheduled) return
      this._fitTerminalScheduled = true
      this.$nextTick(() => {
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            this._fitTerminalScheduled = false
            this.fitTerminal()
          })
        })
      })
    },

    fitTerminal() {
      if (!this.fitAddon || !this.terminal || !this.$refs.terminalContainer) return

      const container = this.$refs.terminalContainer
      const rect = container.getBoundingClientRect()
      if (rect.width < 20 || rect.height < 24 || container.offsetParent === null) return

      try {
        this.fitAddon.fit()
        if (this.terminal.rows > 0) {
          this.terminal.refresh(0, this.terminal.rows - 1)
        }
      } catch (e) {
        // ignore fit errors during layout transitions
      }
    },

    writeln(text) {
      if (this.terminal) this.terminal.writeln(text)
    },

    write(text) {
      if (this.terminal) this.terminal.write(text)
    },

    showPrompt() {
      if (this.aiMode) {
        this.write(`\r\n\x1b[1;33m>\x1b[0m `)
      } else {
        const prompt = this.cwd
          ? `\r\n\x1b[1;32m${this.getDirName(this.cwd)}\x1b[0m \x1b[1;34m$\x1b[0m `
          : `\r\n\x1b[1;32m${this.projectName || 'workspace'}\x1b[0m \x1b[1;34m$\x1b[0m `
        this.write(prompt)
      }
      this.currentInput = ''
      this.cursorPos = 0
    },

    /**
     * Redraw the prompt line in-place without adding a newline.
     * Writes the prompt prefix, current input, and positions the cursor.
     * Used by writeAbovePrompt() to keep the prompt fixed at the bottom.
     */
    redrawPrompt() {
      if (this.aiMode) {
        this.write(`\x1b[1;33m>\x1b[0m `)
      } else {
        const prompt = this.cwd
          ? `\x1b[1;32m${this.getDirName(this.cwd)}\x1b[0m \x1b[1;34m$\x1b[0m `
          : `\x1b[1;32m${this.projectName || 'workspace'}\x1b[0m \x1b[1;34m$\x1b[0m `
        this.write(prompt)
      }
      // Restore input buffer
      if (this.currentInput) {
        this.write(this.currentInput)
        const tailWidth = this.getVisualWidth(this.currentInput.substring(this.cursorPos))
        if (tailWidth > 0) {
          this.write(`\x1b[${tailWidth}D`)
        }
      }
    },

    /**
     * Write text above the current prompt line, then redraw the prompt at the bottom.
     * This keeps the prompt always visible at the bottom while output scrolls above.
     */
    writeAbovePrompt(text) {
      this.write('\r\x1b[K')  // Erase current line (prompt line)
      this.write(text)         // Write output text
      this.write('\r\n')       // Move to next line
      this.redrawPrompt()      // Redraw prompt at bottom
    },

    getDirName(path) {
      if (!path) return this.projectName || 'workspace'
      return path.replace(/\\/g, '/').split('/').filter(Boolean).pop() || path
    },

    // Check if character at given string index is wide (CJK or emoji)
    isWideChar(str, index) {
      const code = str.codePointAt(index)
      if (code === undefined) return false
      // CJK ranges
      if (code >= 0x3000 || (code >= 0x2000 && code <= 0x2FFF) ||
          (code >= 0x4E00 && code <= 0x9FFF) ||
          (code >= 0xF900 && code <= 0xFAFF) ||
          (code >= 0xFE10 && code <= 0xFE1F)) {
        return true
      }
      // Emoji/symbol ranges
      if ((code >= 0x2600 && code <= 0x27BF) ||
          (code >= 0x2300 && code <= 0x23FF) ||
          (code >= 0x25AA && code <= 0x25FF) ||
          (code >= 0x1F000 && code <= 0x1FFFF)) {
        return true
      }
      return false
    },

    // Calculate visual column width of a string (wide chars = 2, ASCII = 1)
    getVisualWidth(str) {
      let width = 0
      let i = 0
      while (i < str.length) {
        if (this.isWideChar(str, i)) {
          width += 2
        } else {
          width += 1
        }
        // Advance past the character (for surrogate pairs, skip 2 code units)
        const code = str.charCodeAt(i)
        i += (code >= 0xD800 && code <= 0xDBFF) ? 2 : 1
      }
      return width
    },

    // ==================== Input Handler ====================

    async handleInput(data) {
      // Block input during execution, but allow confirm responses and Ctrl+C during AI streaming
      if (this.running) return

      // During AI streaming (no pending confirm), allow typing to accumulate input
      // but don't submit on Enter until stream ends
      if (this.aiStreamActive && !this.pendingConfirm) {
        if (data === '\x03') {
          // Ctrl+C: cancel AI stream
          this.currentInput = ''
          this.cursorPos = 0
          this.sendWs({ type: 'cancel', sessionId: this.sessionId })
          this.aiStreamActive = false
          this.writeAbovePrompt('\x1b[33m[AI] Cancelled\x1b[0m')
          return
        }
        if (data === '\r') {
          // Enter during streaming: do nothing, input stays in currentInput
          return
        }
        if (data === '\x7f' || data === '\x08') {
          // Backspace
          if (this.cursorPos <= 0) return
          const charToDelete = this.currentInput.charAt(this.cursorPos - 1)
          if (charToDelete === '\n') {
            // Handle newline deletion (multi-line AI input)
            const newlineIndex = this.cursorPos - 1
            const before = this.currentInput.slice(0, newlineIndex)
            const after = this.currentInput.slice(this.cursorPos)
            this.currentInput = before + after
            this.cursorPos = newlineIndex
          } else {
            const tail = this.currentInput.slice(this.cursorPos)
            this.currentInput = this.currentInput.slice(0, this.cursorPos - 1) + tail
            this.cursorPos--
          }
          this.write('\r\x1b[K')
          this.redrawPrompt()
          return
        }
        if (data === '\x1b[A' || data === '\x1b[B') {
          // Arrow up/down: cycle command history
          if (data === '\x1b[A') {
            if (this.historyIdx > 0) {
              this.historyIdx--
              this.replaceInput(this.aiCommandHistory[this.historyIdx] || '')
            }
          } else {
            if (this.historyIdx < this.aiCommandHistory.length - 1) {
              this.historyIdx++
              this.replaceInput(this.aiCommandHistory[this.historyIdx] || '')
            } else {
              this.historyIdx = this.aiCommandHistory.length
              this.replaceInput('')
            }
          }
          return
        }
        if (data === '\x1b[D' || data === '\x1b[C') {
          // Left/right arrow
          if (data === '\x1b[D' && this.cursorPos > 0) {
            this.cursorPos--
          } else if (data === '\x1b[C' && this.cursorPos < this.currentInput.length) {
            this.cursorPos++
          }
          this.write('\r\x1b[K')
          this.redrawPrompt()
          return
        }
        // Regular character input
        if (data.length > 0 && data.charCodeAt(0) >= 0x20) {
          this.currentInput = this.currentInput.slice(0, this.cursorPos) + data + this.currentInput.slice(this.cursorPos)
          this.cursorPos += data.length
          this.write('\r\x1b[K')
          this.redrawPrompt()
          return
        }
        return
      }

      // Ignore Ctrl+V control character (\x16/SYN) - paste is handled via paste event
      if (data === '\x16') return

      // Handle "y or n" response when tab completion has many matches
      if (this._tabCompletionPending) {
        const savedInput = this.currentInput
        const savedCursorPos = this.cursorPos
        if (data === 'y' || data === 'Y') {
          this._tabCompletionPending = false
          this.write('\r\n')
          this.write(this.tabCompletionCandidates.join('  '))
          this.showPrompt()
          this.currentInput = savedInput
          this.cursorPos = savedCursorPos
          this.terminal.write(this.currentInput)
          this.terminal.write(`\x1b[${this.getVisualWidth(this.currentInput.substring(this.cursorPos))}D`)
        } else if (data === 'n' || data === 'N' || data === '\x03') {
          this._tabCompletionPending = false
          this._tabCompletionListShown = false
          this.write('\r\n')
          this.showPrompt()
          this.currentInput = savedInput
          this.cursorPos = savedCursorPos
          this.terminal.write(this.currentInput)
          this.terminal.write(`\x1b[${this.getVisualWidth(this.currentInput.substring(this.cursorPos))}D`)
        }
        return
      }

      if (data === '\r') {
        const cmd = this.currentInput.trim()
        if (this.aiMode) {
          this.aiCommandHistory.push(cmd)
          this.historyIdx = this.aiCommandHistory.length
        } else {
          this.commandHistory.push(cmd)
          this.historyIdx = this.commandHistory.length
        }
        // Reset tab completion state
        this._lastTabPrefix = ''
        this.tabCompletionCandidates = []
        this.tabCompletionIndex = 0
        this._tabCompletionPending = false
        this._tabCompletionListShown = false

        if (cmd) {
          if (this.aiMode) {
            this.write('\r\n')
          }
          const needsPrompt = await this.executeCommand(cmd)
          if (!this.aiMode && needsPrompt) {
            this.showPrompt()
          }
        }
        return
      }

      if (data === '\x7f' || data === '\x08') {
        // Ignore backspace if command is running
        if (this.running) return
        if (this.cursorPos <= 0) return

        const charToDelete = this.currentInput.charAt(this.cursorPos - 1)

        // In AI mode, handle backspace across newline (merging two displayed lines)
        if (this.aiMode && charToDelete === '\n') {
          const newlineIndex = this.cursorPos - 1
          const before = this.currentInput.slice(0, newlineIndex)
          const after = this.currentInput.slice(this.cursorPos)
          const prevLineStart = before.lastIndexOf('\n') + 1
          const prevLineText = before.slice(prevLineStart)
          const afterText = after

          this.currentInput = before + after
          this.cursorPos = newlineIndex

          // Move to the previous line and position at its actual end, not the terminal wrap edge
          this.terminal.write('\x1b[F')
          this.terminal.write('\r')
          this.terminal.write('\x1b[K')
          this.terminal.write(prevLineText + afterText)
          const tailVisualWidth = this.getVisualWidth(afterText)
          if (tailVisualWidth > 0) {
            this.terminal.write(`\x1b[${tailVisualWidth}D`)
          }
          this.terminal.write('\x1b[s')
          this.terminal.write('\x1b[J')
          this.terminal.write('\x1b[u')
          return
        }

        const charCode = charToDelete.charCodeAt(0)
        const isSurrogate = charCode >= 0xD800 && charCode <= 0xDBFF
        const charsToDelete = isSurrogate ? 2 : 1
        const isDoubleWidth = this.isWideChar(this.currentInput, this.cursorPos - 1)
        const moveBack = isDoubleWidth ? 2 : 1

        const tail = this.currentInput.slice(this.cursorPos)
        this.currentInput = this.currentInput.slice(0, this.cursorPos - charsToDelete) + this.currentInput.slice(this.cursorPos)
        // Use synchronous writes - xterm.js handles buffering internally
        this.terminal.write('\x1b[D'.repeat(moveBack))
        this.terminal.write(tail)
        this.terminal.write('\x1b[K')
        if (tail.length > 0) {
          const tailVisualWidth = this.getVisualWidth(tail)
          this.terminal.write(`\x1b[${tailVisualWidth}D`)
        }
        this.cursorPos -= charsToDelete
        return
      }

      if (data === '\t') {
        this.handleTabCompletion()
        return
      }

      if (data === '\x03') {
        this.currentInput = ''
        this._lastTabPrefix = ''
        this.tabCompletionCandidates = []
        this.tabCompletionIndex = 0
        this._tabCompletionPending = false
        this._tabCompletionListShown = false
        this.write('^C')

        // If in AI mode and stream is active, send cancel to backend
        if (this.aiMode && this.aiStreamActive) {
          this.sendWs({
            type: 'cancel',
            sessionId: this.sessionId
          })
          this.aiStreamActive = false
        }

        this.showPrompt()
        return
      }

      if (data === '\x1b[A') {
        if (this.aiMode) {
          // AI mode: navigate aiCommandHistory first, then dbHistory
          if (this.historyIdx > 0) {
            // Within aiCommandHistory (most recent commands)
            this.historyIdx--
            this.replaceInput(this.aiCommandHistory[this.historyIdx] || '')
          } else if (this.historyIdx === 0) {
            // At the start of aiCommandHistory, transition to dbHistory
            this.historyIdx = -1
            if (!this.dbHistoryLoaded) {
              await this.loadDbHistory()
            }
            if (this.dbHistory.length > 0) {
              this.replaceInput(this.dbHistory[0])
            } else {
              // dbHistory is empty, stay at current position
              this.historyIdx = 0
            }
          } else {
            // Navigating in dbHistory (historyIdx < 0)
            const dbIdx = -(this.historyIdx + 1)
            if (dbIdx < this.dbHistory.length - 1) {
              this.historyIdx--
              this.replaceInput(this.dbHistory[dbIdx + 1])
            }
          }
        } else {
          if (this.historyIdx > 0) {
            this.historyIdx--
            this.replaceInput(this.commandHistory[this.historyIdx] || '')
          }
        }
        return
      }

      if (data === '\x1b[B') {
        if (this.aiMode) {
          if (this.historyIdx < 0) {
            // In dbHistory, navigate forward
            const dbIdx = -(this.historyIdx + 1)
            if (dbIdx > 0) {
              this.historyIdx++
              this.replaceInput(this.dbHistory[dbIdx - 1])
            } else {
              // At the boundary: back to aiCommandHistory most recent
              this.historyIdx = 0
              if (this.aiCommandHistory.length > 0) {
                this.replaceInput(this.aiCommandHistory[this.aiCommandHistory.length - 1] || '')
              } else {
                this.replaceInput('')
              }
            }
          } else if (this.historyIdx < this.aiCommandHistory.length - 1) {
            this.historyIdx++
            this.replaceInput(this.aiCommandHistory[this.historyIdx] || '')
          } else {
            this.historyIdx = this.aiCommandHistory.length
            this.replaceInput('')
          }
        } else if (this.historyIdx < this.commandHistory.length - 1) {
          this.historyIdx++
          this.replaceInput(this.commandHistory[this.historyIdx] || '')
        } else {
          this.historyIdx = this.commandHistory.length
          this.replaceInput('')
        }
        return
      }

      if (data === '\x1b[D') {
        if (this.cursorPos > 0) {
          // In AI mode with multi-line content, handle newline boundary: from column 0 of one line to end of previous line
          if (this.aiMode && this.currentInput[this.cursorPos - 1] === '\n') {
            this.cursorPos--
            const prevNewline = this.currentInput.lastIndexOf('\n', this.cursorPos - 1)
            const lineStart = prevNewline >= 0 ? prevNewline + 1 : 0
            const lineText = this.currentInput.substring(lineStart, this.cursorPos)
            // Move to beginning of previous line, then right to end of that line
            this.terminal.write('\x1b[F')
            const lineVisualWidth = this.getVisualWidth(lineText)
            if (lineVisualWidth > 0) {
              this.terminal.write(`\x1b[${lineVisualWidth}C`)
            }
          } else {
            // Check if char before cursor is double-width (CJK or emoji)
            const prevCode = this.currentInput.charCodeAt(this.cursorPos - 1)
            const isLowSurrogate = prevCode >= 0xDC00 && prevCode <= 0xDFFF
            const isWide = isLowSurrogate || this.isWideChar(this.currentInput, this.cursorPos - 1)
            const moveSteps = isLowSurrogate ? 2 : 1
            this.cursorPos -= moveSteps
            this.terminal.write(isWide ? '\x1b[D\x1b[D' : '\x1b[D')
          }
        }
        return
      }

      if (data === '\x1b[C') {
        if (this.cursorPos < this.currentInput.length) {
          // In AI mode with multi-line content, handle newline boundary: from end of one line to start of next line
          if (this.aiMode && this.currentInput[this.cursorPos] === '\n') {
            this.cursorPos++
            this.terminal.write('\x1b[E')
          } else {
            // Check if char at cursor is double-width (CJK or emoji)
            const curCode = this.currentInput.charCodeAt(this.cursorPos)
            const isHighSurrogate = curCode >= 0xD800 && curCode <= 0xDBFF
            const isWide = isHighSurrogate || this.isWideChar(this.currentInput, this.cursorPos)
            const moveSteps = isHighSurrogate ? 2 : 1
            this.cursorPos += moveSteps
            this.terminal.write(isWide ? '\x1b[C\x1b[C' : '\x1b[C')
          }
        }
        return
      }

      if (data.length > 0 && data.charCodeAt(0) >= 0x20) {
        this.currentInput = this.currentInput.slice(0, this.cursorPos) + data + this.currentInput.slice(this.cursorPos)
        const tail = this.currentInput.slice(this.cursorPos + data.length)
        this.terminal.write(data)
        if (tail.length > 0) {
          this.terminal.write(tail)
          const tailVisualWidth = this.getVisualWidth(tail)
          this.terminal.write(`\x1b[${tailVisualWidth}D`)
        }
        this.cursorPos += data.length
      }
    },

    replaceInput(newInput) {
      // For multi-line content, move to first line and clear from there
      if (this.currentInput.includes('\n')) {
        const newlineCount = (this.currentInput.match(/\n/g) || []).length
        for (let i = 0; i < newlineCount; i++) {
          this.terminal.write('\x1b[F')
        }
        this.terminal.write('\r')
        this.terminal.write('\x1b[J')
      } else {
        const visualLen = this.getVisualWidth(this.currentInput)
        for (let i = 0; i < visualLen; i++) this.terminal.write('\b \b')
      }
      this.currentInput = newInput
      this.cursorPos = newInput.length
      this.terminal.write(newInput)
    },

    // ==================== Tab Completion ====================

    async handleTabCompletion() {
      // Get the word being completed (from cursor position going back)
      const beforeCursor = this.currentInput ? this.currentInput.substring(0, this.cursorPos) : ''
      const lastWordMatch = beforeCursor.match(/(\S+)\s*$/)
      const prefix = lastWordMatch ? lastWordMatch[1] : ''

      // Request path completions from backend
      const candidates = (await this.requestPathCompletions(prefix)).filter(c => c && typeof c === 'string')
      if (!candidates || candidates.length === 0) return

      if (candidates.length === 1) {
        this.applyCompletion(beforeCursor, prefix, candidates[0])
      } else {
        this.cycleCompletion(beforeCursor, prefix, candidates)
      }
    },

    async requestPathCompletions(prefix) {
      if (!this.sendWs({
        type: 'complete',
        prefix: prefix,
        cwd: this.cwd,
        projectName: this.projectName
      })) {
        return []
      }

      // Wait for the complete response with a 2s timeout
      return new Promise((resolve) => {
        this._pendingCompleteResolve = resolve
        setTimeout(() => {
          if (this._pendingCompleteResolve) {
            this._pendingCompleteResolve = null
            resolve([])
          }
        }, 2000)
      })
    },

    applyCompletion(beforeCursor, prefix, completion) {
      const wordStart = beforeCursor.lastIndexOf(prefix)
      const newBeforeCursor = beforeCursor.substring(0, wordStart) + completion
      const afterCursor = this.currentInput.substring(this.cursorPos)
      const newInput = newBeforeCursor + afterCursor
      this.replaceInput(newInput)
      this.cursorPos = newBeforeCursor.length
    },

    cycleCompletion(beforeCursor, prefix, matches) {
      // If this is a new prefix, start from beginning
      if (prefix !== this._lastTabPrefix) {
        this._lastTabPrefix = prefix
        this.tabCompletionCandidates = matches
        this.tabCompletionIndex = 0
      } else {
        // Cycle to next match
        this.tabCompletionIndex = (this.tabCompletionIndex + 1) % this.tabCompletionCandidates.length
      }

      const completion = this.tabCompletionCandidates[this.tabCompletionIndex]
      const wordStart = beforeCursor.lastIndexOf(prefix)
      const newBeforeCursor = beforeCursor.substring(0, wordStart) + completion
      const afterCursor = this.currentInput.substring(this.cursorPos)
      const newInput = newBeforeCursor + afterCursor
      this.replaceInput(newInput)
      this.cursorPos = newBeforeCursor.length

      // Only show completion list on second tab press, and only if <= 10 candidates
      if (this.tabCompletionCandidates.length > 1) {
        if (this.tabCompletionCandidates.length > 10 && !this._tabCompletionListShown) {
          // Linux-style prompt for many matches
          this.write('\r\n')
          this.write(`Display all ${this.tabCompletionCandidates.length} possibilities? (y or n) `)
          this._tabCompletionPending = true
        } else {
          // Show list (if <=10) or cycle silently (if >10 and already shown)
          if (this.tabCompletionCandidates.length <= 10) {
            this.write('\r\n')
            this.write(this.tabCompletionCandidates.join('  '))
          }
          // Show prompt without clearing input
          const savedInput = this.currentInput
          const savedCursorPos = this.cursorPos
          this.showPrompt()
          this.currentInput = savedInput
          this.cursorPos = savedCursorPos
          this.terminal.write(this.currentInput)
          this.terminal.write(`\x1b[${this.getVisualWidth(this.currentInput.substring(this.cursorPos))}D`)
          // Mark that list has been shown so subsequent tabs won't show it again
          this._tabCompletionListShown = true
        }
      }
    },

    async loadDbHistory() {
      if (this.dbHistoryLoaded) return
      try {
        const params = { limit: 50 }
        if (this.projectName) {
          params.projectName = this.projectName
        }
        const { data } = await http.get('/workspace/ai/history', { params })
        if (data.success && Array.isArray(data.history)) {
          this.dbHistory = data.history
        }
      } catch (e) {
        console.debug('[Terminal] Failed to load DB history:', e.message)
      }
      this.dbHistoryLoaded = true
    },

    async executeCommand(cmd) {
      // === AI mode commands ===
      if (this.aiMode) {
        await this.executeAiModeCommand(cmd)
        return false // AI mode handles its own prompt
      }

      // === Normal mode: AI entry ===
      if (cmd === 'ai') {
        this.enterAiMode()
        return false // enterAiMode calls showPrompt
      }
      if (cmd === 'ai help') {
        this.enterAiMode()
        await this.showAiModeHelp()
        return false // showAiModeHelp calls showPrompt
      }
      if (cmd === 'help') {
        this.showHelp()
        return false // showHelp calls showPrompt
      }

      // Old "ai " prefix as fallback
      if (cmd.toLowerCase().startsWith('ai ')) {
        await this.handleAiCommand(cmd)
        return false // handleAiCommand handles its own prompt
      }

      // === 本地处理的命令（无需后端请求） ===
      if (cmd.toLowerCase() === 'cls' || cmd.toLowerCase() === 'clear') {
        this.currentInput = ''
        this.cursorPos = 0
        this.terminal.clear()
        this.showPrompt()
        return false // showPrompt already called
      }

      // Execute as shell command
      this.running = true

      // Try WebSocket first, fallback to HTTP
      if (this.wsConnected && !this.wsFallback) {
        await this.execViaWebSocket(cmd)
      } else {
        await this.execViaHttp(cmd)
      }
      this.running = false
      return true // command executed, need to show prompt in handleInput
    },

    // ==================== WebSocket Command Execution ====================

    execViaWebSocket(cmd) {
      return new Promise((resolve, reject) => {
        this._pendingResolve = resolve
        this._pendingReject = reject

        this.sendWs({
          type: 'exec',
          command: cmd,
          projectName: this.projectName || '',
          cwd: this.cwd || ''
        })


        // Timeout after 60 seconds of inactivity. Reset on stdout so long-running
        // commands that keep streaming output (deploy, scp, etc.) are not cut off.
        this._startExecTimeout()
      })
    },

    _startExecTimeout() {
      this._clearExecTimeout()
      this._execTimeoutId = setTimeout(() => {
        if (this._pendingResolve) {
          this._pendingResolve({ exitCode: -1 })
          this._pendingResolve = null
          this._pendingReject = null
          this._execTimeoutId = null
          this.write(`\r\n\x1b[1;31m(command timeout)\x1b[0m`)
        }
      }, 60000)
    },

    _clearExecTimeout() {
      if (this._execTimeoutId) {
        clearTimeout(this._execTimeoutId)
        this._execTimeoutId = null
      }
    },

    // ==================== HTTP Fallback ====================

    async execViaHttp(cmd) {
      try {
        const { data } = await http.post('/workspace/terminal', {
          command: cmd,
          projectName: this.projectName || '',
          cwd: this.cwd || ''
        })
        if (data.success) {
          if (data.output && data.output.trim()) {
            const normalized = data.output.trimEnd()
              .replace(/\r\n/g, '\n')
              .replace(/\r/g, '')
              .replace(/\n/g, '\r\n')
            this.write('\r\n' + normalized)
          }
          if (data.exitCode !== 0) {
            this.write(`\r\n\x1b[1;31m(exit code: ${data.exitCode})\x1b[0m`)
          }
          if (data.cwd) {
            this.cwd = data.cwd
          }
        } else {
          this.write(`\r\n\x1b[1;31m${data.message || 'Execution failed'}\x1b[0m`)
        }
      } catch (e) {
        this.write(`\r\n\x1b[1;31mRequest failed: ${e.message || e}\x1b[0m`)
      }
    },

    // ==================== AI Mode ====================

    enterAiMode() {
      this.aiMode = true
      this.aiCommandHistory = []
      this.dbHistoryLoaded = false
      this.sessionId = Date.now().toString(36) + Math.random().toString(36).substr(2, 4)
      this.pendingConfirm = null
      this.aiFileChanges = []
      this.write('\r\n\x1b[1;36m────────────────────────────────\x1b[0m')
      this.write('\r\n\x1b[1;36m  Diatom AI Mode\x1b[0m')
      this.write('\r\n  \x1b[1;33mexit\x1b[0m ' + t('aiModeBannerExit') + '  |  \x1b[1;33mai help\x1b[0m ' + t('aiModeBannerHelp'))
      this.write('\r\n\x1b[1;36m────────────────────────────────\x1b[0m')
      this.showPrompt()
    },

    exitAiMode() {
      if (this.aiMode) {
        this.aiMode = false
        this.sessionId = ''
        this.pendingConfirm = null
        this.lastAiCode = ''
        this.pendingAiOps = []
        this.aiFileChanges = []
        this.write('\r\n\x1b[1;33mExited AI mode.\x1b[0m')
        this.showPrompt()
      }
    },

    /**
     * Cancel current AI task (local only — does NOT send cancel to backend).
     * The backend AI task continues running so it can be resumed later.
     */
    cancelAiTask() {
      // Do NOT send cancel WS message — AI task continues in background
      this.aiStreamActive = false
      this.aiMode = false
      this.sessionId = ''
      this.pendingConfirm = null
      this._aiPromptShown = true
    },

    async executeAiModeCommand(cmd) {
      const lower = cmd.toLowerCase()

      // Handle confirm responses (y/a/n) when confirm is pending
      if (this.pendingConfirm) {
        if (lower === 'y' || lower === 'a' || lower === 'n') {
          await this.sendConfirmDecision(lower)
          return
        }
      }

      // AI mode meta commands
      if (lower === 'exit') {
        this.exitAiMode()
        return
      }
      if (lower === 'ai reset') {
        await this.resetAiSession()
        this.write('\r\n\x1b[1;33mAI session reset.\x1b[0m')
        this.showPrompt()
        return
      }
      if (lower === 'ai status') {
        await this.showAiStatus()
        return
      }
      if (lower === 'help' || lower === 'ai help') {
        await this.showAiModeHelp()
        return
      }

      // deploy command: has config → y/r choice, no config → auto generate
      if (lower === 'deploy') {
        if (this.hasDeployYaml) {
          this.write('\r\n\x1b[1;33mdeploy.yaml already exists.\x1b[0m')
          this.write('\r\n  \x1b[1;32my\x1b[0m - Execute deploy')
          this.write('\r\n  \x1b[1;33mr\x1b[0m - Regenerate deploy.yaml')
          this.pendingDeployConfirm = true
          this.showPrompt()
        } else {
          this.sendAiPrompt(
            'Please generate a deploy.yaml for this project. ' +
            'Analyze the project structure first, then ask me for: server info (single/cluster, host IP, SSH user and port), ' +
            'cluster strategy (all/rolling/canary), health check config (http/tcp/command/none with port), and environment variables.'
          )
        }
        return
      }

      // y/r selection when pendingDeployConfirm is active
      if (this.pendingDeployConfirm && (lower === 'y' || lower === 'r')) {
        this.pendingDeployConfirm = false
        if (lower === 'y') {
          this.write('\r\n\x1b[1;36m=== Starting Deploy ===\x1b[0m\r\n')
          this.sendWs({ type: 'deploy', projectName: this.projectName })
        } else {
          this.sendAiPrompt('Please regenerate deploy.yaml. Delete the old .diatom/deploy.yaml first, then re-analyze the project and re-collect server information, generating a new configuration.')
        }
        return
      }

      // All other input → send as AI prompt
      await this.sendAiPrompt(cmd)
    },

    async resetAiSession() {
      if (this.wsConnected && !this.wsFallback) {
        this.sendWs({ type: 'reset', sessionId: this.sessionId })
      } else {
        try {
          await http.post('/workspace/ai/reset', { sessionId: this.sessionId })
        } catch (e) { /* ignore */ }
      }
    },

    async showAiModeHelp() {
      this.write('\r\n\x1b[1;36m' + t('aiModeHelpTitle') + '\x1b[0m')
      this.write('\r\n  \x1b[1;33m<text>\x1b[0m          ' + t('aiModeSendDesc'))
      this.write('\r\n  \x1b[1;33mexit\x1b[0m           ' + t('aiModeExitDesc'))
      this.write('\r\n  \x1b[1;33mai reset\x1b[0m       ' + t('aiModeResetDesc'))
      this.write('\r\n  \x1b[1;33mai status\x1b[0m      ' + t('aiModeStatusDesc'))
      this.write('\r\n  \x1b[1;33mai help\x1b[0m        ' + t('aiModeHelpDesc'))
      this.write('\r\n  \x1b[1;33my\x1b[0m               ' + t('aiModeConfirmDesc'))
      this.write('\r\n  \x1b[1;33ma\x1b[0m               ' + t('aiModeAutoDesc'))
      this.write('\r\n  \x1b[1;33mn\x1b[0m               ' + t('aiModeSkipDesc'))
      // Fetch and show core commands help from backend
      try {
        const lang = localStorage.getItem('ide-lang') || 'zh'
        const { data } = await http.get('/core/help', { params: { lang } })
        if (data && typeof data === 'string' && data.trim()) {
          this.write('\r\n')
          const lines = data.split('\n')
          for (const line of lines) {
            const trimmed = line.trim()
            if (!trimmed) {
              this.write('\r\n')
              continue
            }
            // Section headings
            if (trimmed.startsWith('===') || trimmed.endsWith(':')) {
              this.write('\r\n\x1b[1;36m' + trimmed + '\x1b[0m')
            } else {
              const cmdEnd = trimmed.search(/\s{2,}/)
              if (cmdEnd > 0) {
                const cmd = trimmed.substring(0, cmdEnd).trim()
                const desc = trimmed.substring(cmdEnd).trim()
                this.write('\r\n  \x1b[1;33m' + cmd + '\x1b[0m' + '  '.repeat(Math.max(1, Math.ceil((24 - cmd.length) / 2))) + desc)
              } else {
                this.write('\r\n  ' + trimmed)
              }
            }
          }
        }
      } catch (e) {
        console.debug('[showAiModeHelp] core help unavailable:', e.message)
      }
      this.showPrompt()
    },

    async showAiStatus() {
      try {
        this.write(`\r\n\x1b[1;36mSession:\x1b[0m ${this.sessionId}`)
        this.write(`\r\n\x1b[1;36mAuto-approve:\x1b[0m ${this.pendingConfirm ? 'waiting' : 'disabled'}`)
        this.write(`\r\n\x1b[1;36mProject:\x1b[0m ${this.projectName || '(none)'}`)
      } finally {
        this.showPrompt()
      }
    },

    // ==================== AI Chat ====================

    async sendAiPrompt(prompt) {
      this.aiStreamActive = true
      this._aiFirstEvent = false
      this._aiPromptShown = false
      this._aiStreamedText = ''
      this._aiDisplayedAssistantText = ''
      this.write('\r\n\x1b[1;33mThinking...\x1b[0m')

      if (this.wsConnected && !this.wsFallback) {
        await this.aiViaWebSocket(prompt)
      } else {
        await this.aiViaSse(prompt)
      }
    },

    async aiViaWebSocket(prompt) {
      this.sendWs({
        type: 'ai',
        prompt: prompt,
        projectName: this.projectName || '',
        sessionId: this.sessionId
      })
      // Wait for the stream to end. Stream end is detected when aiStreamActive is set to false
      // in handleWsDone/handleWsError/handleWsCancelled.
      await new Promise((resolve) => {
        const checkDone = () => {
          if (!this.aiStreamActive || this._aiPromptShown) {
            resolve()
          } else {
            setTimeout(checkDone, 100)
          }
        }
        checkDone()
      })
    },

    async aiViaSse(prompt) {
      try {
        const token = this.authToken
        if (!token) throw new Error('Not authenticated')
        const response = await fetch('/workspace/ai/chat?_token=' + encodeURIComponent(token), {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-Auth-Token': token },
          body: JSON.stringify({
            prompt,
            projectName: this.projectName || '',
            activeFile: this.activeFile || '',
            sessionId: this.sessionId
          })
        })

        if (!response.ok) {
          this.writeAbovePrompt(`\x1b[1;31mAI request failed (HTTP ${response.status})\x1b[0m`)
          this.aiStreamActive = false
          this._aiPromptShown = true
          return
        }

        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''

        while (true) {
          const { done, value } = await reader.read()
          if (done) break

          buffer += decoder.decode(value, { stream: true })
          const events = buffer.split('\n\n')
          buffer = events.pop() || ''

          for (const event of events) {
            this.processSseEvent(event)
          }
        }

        // Process remaining buffer
        if (buffer.trim()) {
          this.processSseEvent(buffer)
        }

      } catch (e) {
        this.writeAbovePrompt(`\x1b[1;31mAI request failed: ${e.message}\x1b[0m`)
        this._aiPromptShown = true
      } finally {
        this.aiStreamActive = false
        if (!this._aiPromptShown && this.aiMode) {
          this.showPrompt()
        }
      }
    },

    processSseEvent(raw) {
      const lines = raw.split('\n')
      let eventType = ''
      let data = ''

      for (const line of lines) {
        if (line.startsWith('event: ')) {
          eventType = line.substring(7).trim()
        } else if (line.startsWith('data:')) {
          data = line.substring(5).trim()
        }
      }

      if (!eventType && !data) return

      try {
        const parsed = data.startsWith('{') ? JSON.parse(data) : { text: data }

        switch (eventType) {
          case 'think':
            this._aiStreamedText += parsed.text || ''
            const thinkFormatted = this.renderMarkdown(parsed.text || '').replace(/\r\n/g, '\n').replace(/\r/g, '').replace(/\n/g, '\r\n')
            this.writeAbovePrompt(thinkFormatted)
            break

          case 'confirm':
            this.pendingConfirm = {
              action: parsed.action,
              tool: parsed.tool,
              sessionId: parsed.sessionId
            }
            this.write('\r\x1b[K')
            this.write(`\x1b[1;33m确认执行?\x1b[0m (\x1b[1;32my\x1b[0m=确认, \x1b[1;33ma\x1b[0m=本次自动, \x1b[1;31mn\x1b[0m=取消)`)
            this.write(`\r\n\x1b[1;33m>\x1b[0m `)
            this.currentInput = ''
            this.cursorPos = 0
            break

          case 'done':
            if (!this._aiStreamedText && parsed.content) {
              const doneContent = this.renderMarkdown(parsed.content).replace(/\r\n/g, '\n').replace(/\r/g, '').replace(/\n/g, '\r\n')
              this.writeAbovePrompt(doneContent)
            }
            this.pendingConfirm = null
            this._aiPromptShown = true
            this.$emit('refresh-editor')
            break

          case 'error':
            let sseErrorMsg = parsed.message || parsed.text || t('commonUnknownError')
            let sseErrorExtra = ''
            if (parsed.message) {
              if (parsed.message.includes('timeout') || parsed.message.includes('Timeout')) {
                sseErrorExtra = t('aiErrorTimeout')
              } else if (parsed.message.includes('rate limit') || parsed.message.includes('429')) {
                sseErrorExtra = t('aiErrorRateLimit')
              } else if (parsed.message.includes('circuit breaker')) {
                sseErrorExtra = t('aiErrorCircuitBreaker')
              }
            }
            this.writeAbovePrompt(`\x1b[1;31mError: ${sseErrorMsg}\x1b[0m${sseErrorExtra ? `\r\n\x1b[33m${sseErrorExtra}\x1b[0m` : ''}`)
            this.pendingConfirm = null
            this.aiStreamActive = false
            this._aiPromptShown = true
            break
        }
      } catch (e) {
        // Plain text data
        if (eventType === 'think') {
          this._aiStreamedText += data
          const plainFormatted = this.renderMarkdown(data).replace(/\r\n/g, '\n').replace(/\r/g, '').replace(/\n/g, '\r\n')
          this.writeAbovePrompt(plainFormatted)
        } else if (eventType === 'done') {
          this.pendingConfirm = null
          this._aiPromptShown = true
          this.$emit('refresh-editor')
        } else if (eventType === 'error') {
          let plainErrMsg = data || t('commonUnknownError')
          let plainErrExtra = ''
          if (plainErrMsg.includes('timeout') || plainErrMsg.includes('Timeout')) {
            plainErrExtra = t('aiErrorTimeout')
          } else if (plainErrMsg.includes('rate limit') || plainErrMsg.includes('429')) {
            plainErrExtra = t('aiErrorRateLimit')
          } else if (plainErrMsg.includes('circuit breaker')) {
            plainErrExtra = t('aiErrorCircuitBreaker')
          }
          this.writeAbovePrompt(`\x1b[1;31mError: ${plainErrMsg}\x1b[0m${plainErrExtra ? `\r\n\x1b[33m${plainErrExtra}\x1b[0m` : ''}`)
          this.pendingConfirm = null
          this.aiStreamActive = false
          this._aiPromptShown = true
        }
      }
    },

    async sendConfirmDecision(decision) {
      if (!this.pendingConfirm) return

      this.running = true
      // Log confirmation to console only, not terminal
      const confirmMsg = decision === 'y' ? t('aiConfirmed') : decision === 'a' ? t('aiAutoApproved') : decision === 'n' ? 'Cancelled' : ''
      console.log(`[AI Confirm] ${confirmMsg}`)

      if (this.wsConnected && !this.wsFallback) {
        this.sendWs({
          type: 'confirm',
          sessionId: this.pendingConfirm.sessionId,
          decision: decision
        })
        this.pendingConfirm = null
        this.running = false
      } else {
        try {
          await http.post('/workspace/ai/confirm-decision', {
            sessionId: this.pendingConfirm.sessionId,
            decision: decision
          })
          this.pendingConfirm = null
        } catch (e) {
          this.write(`\r\n\x1b[1;31mConfirm failed: ${e.message}\x1b[0m`)
          this.pendingConfirm = null
          this.showPrompt()
        } finally {
          this.running = false
        }
      }
    },

    // ==================== Legacy AI Commands (backward compat) ====================

    async handleAiCommand(cmd) {
      const lower = cmd.toLowerCase()

      if (lower === 'ai' || lower === 'ai help') {
        this.showAiHelp()
        return
      }
      if (lower === 'ai apply') {
        if (!this.lastAiCode) {
          this.write('\r\n\x1b[1;31mNo AI code to apply. Use "ai <prompt>" first.\x1b[0m')
          return
        }
        this.$emit('apply-code', this.lastAiCode)
        this.write('\r\n\x1b[1;32mCode applied to editor.\x1b[0m')
        return
      }
      if (lower === 'ai insert') {
        if (!this.lastAiCode) {
          this.write('\r\n\x1b[1;31mNo AI code to insert. Use "ai <prompt>" first.\x1b[0m')
          return
        }
        this.$emit('insert-code', this.lastAiCode)
        this.write('\r\n\x1b[1;32mCode inserted at cursor.\x1b[0m')
        return
      }
      if (lower === 'ai no') {
        if (this.pendingAiOps.length === 0) {
          this.write('\r\n\x1b[1;33mNo pending file operations.\x1b[0m')
          return
        }
        this.pendingAiOps = []
        this.write('\r\n\x1b[1;33mAll pending file operations skipped.\x1b[0m')
        return
      }
      if (lower.startsWith('ai yes')) {
        if (this.pendingAiOps.length === 0) {
          this.write('\r\n\x1b[1;33mNo pending file operations.\x1b[0m')
          return
        }
        const arg = cmd.substring(6).trim()
        this.running = true
        if (arg && /^\d+$/.test(arg)) {
          const idx = parseInt(arg) - 1
          if (idx < 0 || idx >= this.pendingAiOps.length) {
            this.write(`\r\n\x1b[1;31mInvalid index. Choose 1-${this.pendingAiOps.length}\x1b[0m`)
          } else {
            await this.confirmAiOp(this.pendingAiOps[idx])
            this.pendingAiOps.splice(idx, 1)
          }
        } else {
          this.write('\r\n')
          for (const op of [...this.pendingAiOps]) {
            await this.confirmAiOp(op)
          }
          this.pendingAiOps = []
          this.$emit('refresh-project', this.projectName)
          this.write('\x1b[1;33mTip: You may need to update dependencies after file operations.\x1b[0m')
        }
        this.running = false
        return
      }

      // Legacy "ai <prompt>" - redirect to AI mode
      const prompt = cmd.substring(3).trim()
      if (!prompt) {
        this.write('\r\n\x1b[1;31mUsage: ai <your prompt>\x1b[0m')
        return
      }
      // Auto-enter AI mode
      if (!this.aiMode) {
        this.enterAiMode()
      }
      await this.sendAiPrompt(prompt)
    },

    showAiHelp() {
      this.write('\r\n\x1b[1;36mAI Commands:\x1b[0m')
      this.write('\r\n  \x1b[1;33m/ai\x1b[0m                Enter AI conversation mode')
      this.write('\r\n  \x1b[1;33mai <prompt>\x1b[0m        Legacy: AI single query')
      this.write('\r\n  \x1b[1;33mai apply\x1b[0m          Paste last AI code into editor')
      this.write('\r\n  \x1b[1;33mai yes\x1b[0m            Confirm file operations')
    },

    async confirmAiOp(op) {
      try {
        const { data } = await http.post('/workspace/ai/confirm', {
          projectName: op.projectName, path: op.path, content: op.content
        })
        if (data.success) {
          this.write(`\r\n  \x1b[1;32m\u2713\x1b[0m ${op.path}`)
          return true
        } else {
          this.write(`\r\n  \x1b[1;31m\u2717 ${op.path}: ${data.message}\x1b[0m`)
          return false
        }
      } catch (e) {
        this.write(`\r\n  \x1b[1;31m\u2717 ${op.path}: ${e.message}\x1b[0m`)
        return false
      }
    },

    async showHelp() {
      this.write('\r\n\x1b[1;36mIDE Commands:\x1b[0m')
      this.write('\r\n  \x1b[1;33m/ai\x1b[0m           Enter AI conversation mode')
      this.write('\r\n  \x1b[1;33m/ai help\x1b[0m      Enter AI mode and show help')
      this.write('\r\n  \x1b[1;33m/help\x1b[0m        Show this help')
      this.write('\r\n  \x1b[1;33mcls\x1b[0m           Clear terminal')
      this.write('\r\n  \x1b[1;33m<shell cmd>\x1b[0m   Execute shell command')
      // Fetch and show core commands (mcp, skills, etc.)
      try {
        const lang = localStorage.getItem('ide-lang') || 'zh'
        const { data } = await http.get('/core/help', { params: { lang } })
        if (data && typeof data === 'string' && data.trim()) {
          this.write('\r\n')
          const lines = data.split('\n')
          for (const line of lines) {
            const trimmed = line.trim()
            if (!trimmed) { this.write('\r\n'); continue }
            if (trimmed.endsWith(':') || trimmed.startsWith('===')) {
              this.write('\r\n\x1b[1;36m' + trimmed + '\x1b[0m')
            } else {
              const cmdEnd = trimmed.search(/\s{2,}/)
              if (cmdEnd > 0) {
                const cmd = trimmed.substring(0, cmdEnd).trim()
                const desc = trimmed.substring(cmdEnd).trim()
                this.write('\r\n  \x1b[1;33m' + cmd + '\x1b[0m' + '  '.repeat(Math.max(1, Math.ceil((24 - cmd.length) / 2))) + desc)
              } else {
                this.write('\r\n  ' + trimmed)
              }
            }
          }
        }
      } catch (e) {
        console.debug('[showHelp] core help unavailable:', e.message)
      }
      this.showPrompt()
    },

    clear() {
      if (this.terminal) {
        this.currentInput = ''
        this.cursorPos = 0
        this.terminal.clear()
        this.showPrompt()
      }
    },

    updateTheme() {
      if (!this.terminal) return
      this.terminal.options.theme = this.isDark ? DARK_THEME : LIGHT_THEME
      this.applyThemeVars()
      this.terminal.refresh(0, this.terminal.rows - 1)
    },

    applyThemeVars() {
      const wrapper = this.$refs.terminalContainer
      if (!wrapper) return
      if (this.isDark) {
        wrapper.style.background = '#1e1e1e'
        wrapper.style.setProperty('--xterm-scrollbar', '#424242')
        wrapper.style.setProperty('--xterm-viewport-bg', '#1e1e1e')
      } else {
        wrapper.style.background = '#ffffff'
        wrapper.style.setProperty('--xterm-scrollbar', '#c1c1c1')
        wrapper.style.setProperty('--xterm-viewport-bg', '#ffffff')
      }
    }
  }
}
</script>

<style scoped>
.xterm-wrapper {
  width: 100%;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.xterm-wrapper > .terminal-container {
  flex: 1;
  min-height: 48px;
  overflow: hidden;
  position: relative;
  background: var(--xterm-viewport-bg, #1e1e1e);
}
.xterm-wrapper :deep(.xterm) {
  width: 100%;
  height: 100%;
  padding: 0;
}
.xterm-wrapper :deep(.xterm-viewport) {
  scrollbar-width: thin;
  scrollbar-color: var(--xterm-scrollbar, #424242) transparent;
  background-color: var(--xterm-viewport-bg, #1e1e1e) !important;
}
.xterm-wrapper :deep(.xterm-viewport::-webkit-scrollbar) {
  width: 8px;
}
.xterm-wrapper :deep(.xterm-viewport::-webkit-scrollbar-thumb) {
  background: var(--xterm-scrollbar, #424242);
  border-radius: 4px;
}

/* AI Progress Bar */
.ai-progress-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  font-size: 12px;
  font-family: monospace;
  white-space: nowrap;
  overflow: hidden;
  border-bottom: 1px solid rgba(128, 128, 128, 0.3);
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
}
.ai-progress-bar:hover {
  opacity: 0.85;
}
.ai-progress-bar.ai-progress-dark {
  background: #2d2d2d;
  color: #d4d4d4;
}
.ai-progress-bar:not(.ai-progress-dark) {
  background: #f5f5f5;
  color: #333;
}
.ai-progress-label {
  color: #569cd6;
  font-weight: bold;
}
.ai-progress-current {
  color: #ce9178;
}
.ai-progress-history {
  display: flex;
  gap: 6px;
  color: #6a9955;
  margin-left: 8px;
}
.ai-progress-item {
  opacity: 0.8;
}
.ai-progress-more {
  color: #808080;
  font-style: italic;
}
/* Helper file toggle button */
.ai-progress-helper-toggle {
  cursor: pointer;
  font-size: 13px;
  color: #808080;
  padding: 0 2px;
  user-select: none;
  line-height: 1;
}
.ai-progress-helper-toggle:hover {
  color: #569cd6;
}
.helper-toggle-icon {
  display: inline-block;
}
.ai-progress-toggle {
  margin-left: auto;
  font-size: 10px;
  color: #808080;
}

/* SCP Progress Bar */
.scp-progress-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  font-size: 12px;
  font-family: monospace;
  white-space: nowrap;
  overflow: hidden;
  border-bottom: 1px solid rgba(128, 128, 128, 0.3);
  flex-shrink: 0;
}
.scp-progress-bar.scp-progress-dark {
  background: #252526;
  color: #d4d4d4;
}
.scp-progress-bar:not(.scp-progress-dark) {
  background: #f0f0f0;
  color: #333;
}
.scp-progress-label {
  color: #c586c0;
  font-weight: bold;
  flex-shrink: 0;
}
.scp-progress-step {
  color: #ce9178;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 0;
}
.scp-progress-bar-wrap {
  flex: 1;
  height: 8px;
  background: rgba(128, 128, 128, 0.25);
  border-radius: 4px;
  overflow: hidden;
  min-width: 60px;
}
.scp-progress-fill {
  display: block;
  height: 100%;
  background: #0dbc79;
  transition: width 0.2s ease;
}
.scp-progress-percent {
  color: #569cd6;
  font-weight: bold;
  min-width: 36px;
  text-align: right;
  flex-shrink: 0;
}
.scp-progress-size {
  color: #808080;
  flex-shrink: 0;
}
.scp-progress-speed {
  color: #6a9955;
  flex-shrink: 0;
}

/* Changes badge on progress bar */
.ai-progress-changes {
  cursor: pointer;
  font-size: 11px;
  color: #569cd6;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  border-radius: 3px;
  margin-left: auto;
}
.ai-progress-changes:hover {
  background: rgba(86, 156, 214, 0.15);
}
.changes-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border-radius: 9px;
  background: #569cd6;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
}

/* Diff panel wrapper */
.ai-diff-wrapper {
  flex-shrink: 0;
}
.ai-progress-icon {
  font-style: normal;
}
.ai-progress-item-target {
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
}

/* AI Progress Detail Panel */
.ai-progress-detail {
  font-family: monospace;
  font-size: 12px;
  border-bottom: 1px solid rgba(128, 128, 128, 0.3);
  flex-shrink: 0;
  max-height: 200px;
  overflow-y: auto;
}
.ai-progress-detail.ai-progress-dark {
  background: #252526;
  color: #d4d4d4;
}
.ai-progress-detail:not(.ai-progress-dark) {
  background: #f0f0f0;
  color: #333;
}
.ai-progress-detail-header {
  padding: 2px 12px;
  font-size: 11px;
  color: #888;
  border-bottom: 1px solid rgba(128, 128, 128, 0.15);
  position: sticky;
  top: 0;
  background: inherit;
}
.ai-progress-detail-list {
  padding: 2px 0;
}
.ai-progress-detail-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 12px;
  white-space: nowrap;
}
.ai-progress-detail-item:hover {
  background: rgba(128, 128, 128, 0.1);
}
.ai-progress-detail-item.current {
  background: rgba(86, 156, 214, 0.1);
}
.ai-progress-detail-icon {
  width: 18px;
  text-align: center;
  flex-shrink: 0;
}
.ai-progress-detail-icon.spinning {
  display: inline-block;
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.ai-progress-detail-tool {
  color: #569cd6;
  flex-shrink: 0;
  width: 70px;
}
.ai-progress-detail-target {
  overflow: hidden;
  text-overflow: ellipsis;
  color: #ce9178;
}
.ai-progress-detail-extra {
  color: #888;
  margin-left: 8px;
  font-size: 11px;
}

/* Clickable file target in progress detail / inline chips */
.ai-progress-target-clickable {
  cursor: pointer;
  text-decoration: none;
}
.ai-progress-target-clickable:hover {
  text-decoration: underline;
  color: #4da6ff !important;
}

/* Standalone Changes bar (visible when aiProgress is null) */
.ai-standalone-changes {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  font-family: monospace;
  font-size: 12px;
  border-bottom: 1px solid rgba(128, 128, 128, 0.3);
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
}
.ai-standalone-changes.ai-progress-dark {
  background: #252526;
  color: #d4d4d4;
}
.ai-standalone-changes:not(.ai-progress-dark) {
  background: #f0f0f0;
  color: #333;
}
.ai-standalone-changes .ai-standalone-changes-label {
  color: #569cd6;
  font-weight: 600;
  margin-right: 4px;
}
.ai-standalone-changes:hover {
  background: rgba(86, 156, 214, 0.1);
}
.ai-standalone-changes .ai-progress-toggle {
  margin-left: auto;
  color: #888;
  font-size: 10px;
}

</style>
