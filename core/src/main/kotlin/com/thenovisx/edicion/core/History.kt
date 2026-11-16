package com.thenovisx.edicion.core

/** Historial de deshacer/rehacer basado en instantáneas inmutables. */
class History<T>(initial: T, private val limit: Int = 100) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    var current: T = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Registra un nuevo estado. Si es igual al actual no se guarda nada. */
    fun push(state: T) {
        if (state == current) return
        undoStack.addLast(current)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        current = state
    }

    /** Sustituye el estado actual sin crear un paso de historial (útil al arrastrar sliders). */
    fun replace(state: T) {
        current = state
    }

    fun undo(): T {
        if (undoStack.isEmpty()) return current
        redoStack.addLast(current)
        current = undoStack.removeLast()
        return current
    }

    fun redo(): T {
        if (redoStack.isEmpty()) return current
        undoStack.addLast(current)
        current = redoStack.removeLast()
        return current
    }
}
