import { useCallback, useReducer, useRef } from 'react'

export interface HistoryState<T> {
  past: T[]
  present: T
  future: T[]
}

export type HistoryAction<T> =
  | { type: 'record'; value: T }
  | { type: 'replace'; value: T }
  | { type: 'checkpoint'; before: T }
  | { type: 'reset'; value: T }
  | { type: 'undo' }
  | { type: 'redo' }

export const createHistory = <T,>(value: T): HistoryState<T> => ({
  past: [],
  present: value,
  future: [],
})

export function historyReducer<T>(state: HistoryState<T>, action: HistoryAction<T>): HistoryState<T> {
  switch (action.type) {
    case 'record':
      if (deepEqual(state.present, action.value)) return state
      return {
        past: [...state.past, structuredClone(state.present)].slice(-100),
        present: structuredClone(action.value),
        future: [],
      }
    case 'replace':
      return { ...state, present: structuredClone(action.value) }
    case 'checkpoint':
      if (deepEqual(action.before, state.present)) return state
      return {
        past: [...state.past, structuredClone(action.before)].slice(-100),
        present: structuredClone(state.present),
        future: [],
      }
    case 'reset':
      return createHistory(structuredClone(action.value))
    case 'undo': {
      const previous = state.past.at(-1)
      if (!previous) return state
      return {
        past: state.past.slice(0, -1),
        present: structuredClone(previous),
        future: [structuredClone(state.present), ...state.future].slice(0, 100),
      }
    }
    case 'redo': {
      const next = state.future[0]
      if (!next) return state
      return {
        past: [...state.past, structuredClone(state.present)].slice(-100),
        present: structuredClone(next),
        future: state.future.slice(1),
      }
    }
  }
}

export function useHistory<T>(initialValue: T) {
  const [state, dispatch] = useReducer(historyReducer<T>, createHistory(initialValue))
  const transactionStart = useRef<T | null>(null)

  const record = useCallback((value: T) => dispatch({ type: 'record', value }), [])
  const replace = useCallback((value: T) => dispatch({ type: 'replace', value }), [])
  const reset = useCallback((value: T) => dispatch({ type: 'reset', value }), [])
  const undo = useCallback(() => dispatch({ type: 'undo' }), [])
  const redo = useCallback(() => dispatch({ type: 'redo' }), [])
  const beginTransaction = useCallback(() => {
    transactionStart.current = structuredClone(state.present)
  }, [state.present])
  const commitTransaction = useCallback(() => {
    if (transactionStart.current) {
      dispatch({ type: 'checkpoint', before: transactionStart.current })
      transactionStart.current = null
    }
  }, [])

  return {
    ...state,
    record,
    replace,
    reset,
    undo,
    redo,
    beginTransaction,
    commitTransaction,
    canUndo: state.past.length > 0,
    canRedo: state.future.length > 0,
  }
}

export const deepEqual = (left: unknown, right: unknown) => JSON.stringify(left) === JSON.stringify(right)
