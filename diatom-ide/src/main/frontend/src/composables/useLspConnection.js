/**
 * LSP WebSocket Connection Composable
 * Addresses issue 6.3: WebSocket heartbeat, exponential backoff reconnection.
 * Addresses issue 6.4: Proper resource cleanup on unmount.
 */
import { ref, onBeforeUnmount } from 'vue'
import { listen } from 'vscode-ws-jsonrpc'

export function useLspConnection() {
  const lspStatus = ref('disconnected')
  const lspConnection = ref(null)

  let ws = null
  let heartbeatTimer = null
  let reconnectTimer = null
  let reconnectAttempts = 0
  let isDestroyed = false

  const MAX_RECONNECT_DELAY = 30000
  const BASE_RECONNECT_DELAY = 1000
  const HEARTBEAT_INTERVAL = 30000

  function getLspUrl() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    return `${protocol}//${host}/java-lsp`
  }

  function connect() {
    if (isDestroyed) return

    lspStatus.value = 'connecting'
    const url = getLspUrl()

    try {
      ws = new WebSocket(url)
    } catch (e) {
      console.error('[LSP] WebSocket creation failed:', e)
      scheduleReconnect()
      return
    }

    ws.onopen = () => {
      lspStatus.value = 'connected'
      reconnectAttempts = 0
      startHeartbeat()
    }

    ws.onclose = (event) => {
      lspStatus.value = 'disconnected'
      stopHeartbeat()
      lspConnection.value = null
      if (!isDestroyed && !event.wasClean) {
        scheduleReconnect()
      }
    }

    ws.onerror = (error) => {
      console.error('[LSP] WebSocket error:', error)
      lspStatus.value = 'error'
    }

    // Use vscode-ws-jsonrpc to create JSON-RPC connection
    try {
      listen({
        webSocket: ws,
        onConnection: (connection) => {
          lspConnection.value = connection
          connection.listen()

          // Initialize LSP
          connection.sendRequest('initialize', {
            processId: null,
            capabilities: {},
            rootUri: 'file:///workspace'
          }).then(() => {
            connection.sendNotification('initialized', {})
            lspStatus.value = 'ready'
          }).catch((err) => {
            console.error('[LSP] Initialize failed:', err)
            lspStatus.value = 'init-failed'
          })
        }
      })
    } catch (e) {
      console.error('[LSP] listen() failed:', e)
    }
  }

  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        // Send a lightweight notification as heartbeat
        try {
          ws.send(JSON.stringify({ jsonrpc: '2.0', method: '$/heartbeat' }))
        } catch (e) {
          // Connection might be dead
          stopHeartbeat()
          scheduleReconnect()
        }
      }
    }, HEARTBEAT_INTERVAL)
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function scheduleReconnect() {
    if (isDestroyed || reconnectTimer) return
    // Exponential backoff with jitter
    const delay = Math.min(
      BASE_RECONNECT_DELAY * Math.pow(2, reconnectAttempts) + Math.random() * 1000,
      MAX_RECONNECT_DELAY
    )
    reconnectAttempts++
    lspStatus.value = 'reconnecting'
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, delay)
  }

  function disconnect() {
    isDestroyed = true
    stopHeartbeat()
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      ws.close()
      ws = null
    }
    lspConnection.value = null
  }

  // Issue 6.4: Cleanup on component unmount
  onBeforeUnmount(() => {
    disconnect()
  })

  return {
    lspStatus,
    lspConnection,
    connect,
    disconnect
  }
}
