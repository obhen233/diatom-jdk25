/**
 * Resize Panel Composable
 * Handles drag-to-resize for sidebar and console panels.
 * Addresses issue 6.4: Proper cleanup of event listeners.
 */
import { ref, onBeforeUnmount } from 'vue'

export function useResizePanel(initialWidth, minWidth = 150, maxWidth = 600, direction = 'horizontal') {
  const size = ref(initialWidth)
  let isResizing = false
  let startPos = 0
  let startSize = 0

  function onMouseDown(e) {
    isResizing = true
    startPos = direction === 'horizontal' ? e.clientX : e.clientY
    startSize = size.value
    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
    document.body.style.cursor = direction === 'horizontal' ? 'col-resize' : 'row-resize'
    document.body.style.userSelect = 'none'
    e.preventDefault()
  }

  function onMouseMove(e) {
    if (!isResizing) return
    const currentPos = direction === 'horizontal' ? e.clientX : e.clientY
    const delta = currentPos - startPos
    const newSize = direction === 'horizontal'
      ? startSize + delta
      : startSize - delta // For console, dragging up increases height
    size.value = Math.max(minWidth, Math.min(maxWidth, newSize))
  }

  function onMouseUp() {
    isResizing = false
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }

  // Issue 6.4: Cleanup on unmount
  onBeforeUnmount(() => {
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
  })

  return {
    size,
    onMouseDown
  }
}
