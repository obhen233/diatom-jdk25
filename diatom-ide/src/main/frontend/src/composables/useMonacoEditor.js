/**
 * Monaco Editor Composable
 * Addresses issue 6.9: Per-file models with independent undo history.
 * Addresses issue 6.4: Proper disposal of editor resources.
 */
import { ref, onBeforeUnmount } from 'vue'
import * as monaco from 'monaco-editor'

export function useMonacoEditor() {
  const editor = ref(null)
  const cursorLine = ref(1)
  const cursorCol = ref(1)

  // Per-file model cache: path -> monaco.editor.ITextModel
  const models = new Map()
  // Per-file view state cache: path -> editor.saveViewState()
  const viewStates = new Map()
  // Disposables for cleanup
  const disposables = []

  /**
   * Initialize the Monaco editor in the given container element.
   */
  function createEditor(container, options = {}) {
    if (editor.value) return editor.value

    editor.value = monaco.editor.create(container, {
      value: '',
      language: 'java',
      theme: options.dark ? 'vs-dark' : 'vs',
      automaticLayout: true,
      fontSize: 14,
      minimap: { enabled: true },
      scrollBeyondLastLine: false,
      wordWrap: 'off',
      tabSize: 4,
      insertSpaces: true,
      renderWhitespace: 'selection',
      bracketPairColorization: { enabled: true },
      ...options
    })

    // Track cursor position
    const cursorDisposable = editor.value.onDidChangeCursorPosition((e) => {
      cursorLine.value = e.position.lineNumber
      cursorCol.value = e.position.column
    })
    disposables.push(cursorDisposable)

    return editor.value
  }

  /**
   * Open a file in the editor. Creates or reuses a model for the file.
   * This preserves undo history per file (issue 6.9).
   */
  function openFile(filePath, content, language = 'java') {
    if (!editor.value) return

    // Save current view state before switching
    const currentModel = editor.value.getModel()
    if (currentModel) {
      const currentUri = currentModel.uri.toString()
      viewStates.set(currentUri, editor.value.saveViewState())
    }

    // Get or create model for the target file
    const uri = monaco.Uri.parse(`file://${filePath}`)
    let model = monaco.editor.getModel(uri)

    if (!model) {
      model = monaco.editor.createModel(content, language, uri)
      models.set(filePath, model)
    } else if (model.getValue() !== content) {
      // Only update if content actually changed (e.g., external reload)
      // This preserves undo history when just switching tabs
      model.setValue(content)
    }

    // Set the model on the editor
    editor.value.setModel(model)

    // Restore view state if we had one
    const savedState = viewStates.get(uri.toString())
    if (savedState) {
      editor.value.restoreViewState(savedState)
    }
  }

  /**
   * Get the current editor content.
   */
  function getValue() {
    if (!editor.value) return ''
    return editor.value.getValue()
  }

  /**
   * Set the editor language for the current model.
   */
  function setLanguage(language) {
    if (!editor.value) return
    const model = editor.value.getModel()
    if (model) {
      monaco.editor.setModelLanguage(model, language)
    }
  }

  /**
   * Navigate to a specific line and column.
   */
  function goToPosition(line, column = 1) {
    if (!editor.value) return
    editor.value.revealLineInCenter(line)
    editor.value.setPosition({ lineNumber: line, column })
    editor.value.focus()
  }

  /**
   * Close a file model and free its resources.
   */
  function closeFile(filePath) {
    const uri = monaco.Uri.parse(`file://${filePath}`)
    const model = monaco.editor.getModel(uri)
    if (model) {
      model.dispose()
      models.delete(filePath)
      viewStates.delete(uri.toString())
    }
  }

  /**
   * Set the editor theme.
   */
  function setTheme(dark) {
    monaco.editor.setTheme(dark ? 'vs-dark' : 'vs')
  }

  /**
   * Add an editor action (context menu item / keybinding).
   * Returns a disposable.
   */
  function addAction(actionDescriptor) {
    if (!editor.value) return null
    const disposable = editor.value.addAction(actionDescriptor)
    disposables.push(disposable)
    return disposable
  }

  /**
   * Dispose all resources. Called on component unmount (issue 6.4).
   */
  function dispose() {
    // Dispose all tracked disposables
    disposables.forEach(d => {
      try { d.dispose() } catch (e) { /* ignore */ }
    })
    disposables.length = 0

    // Dispose all models
    models.forEach(model => {
      try { model.dispose() } catch (e) { /* ignore */ }
    })
    models.clear()
    viewStates.clear()

    // Dispose the editor itself
    if (editor.value) {
      editor.value.dispose()
      editor.value = null
    }
  }

  // Issue 6.4: Cleanup on component unmount
  onBeforeUnmount(() => {
    dispose()
  })

  return {
    editor,
    cursorLine,
    cursorCol,
    createEditor,
    openFile,
    getValue,
    setLanguage,
    goToPosition,
    closeFile,
    setTheme,
    addAction,
    dispose
  }
}
