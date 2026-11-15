import { describe, expect, it } from 'vitest'
import { createHistory, historyReducer } from './history'

describe('historyReducer', () => {
  it('supports record, undo and redo without mutating snapshots', () => {
    let state = createHistory({ value: 1 })
    state = historyReducer(state, { type: 'record', value: { value: 2 } })
    state = historyReducer(state, { type: 'record', value: { value: 3 } })

    state = historyReducer(state, { type: 'undo' })
    expect(state.present.value).toBe(2)
    state.present.value = 20
    state = historyReducer(state, { type: 'redo' })
    expect(state.present.value).toBe(3)
  })

  it('coalesces a transaction into one checkpoint', () => {
    let state = createHistory({ x: 0 })
    const before = structuredClone(state.present)
    state = historyReducer(state, { type: 'replace', value: { x: 10 } })
    state = historyReducer(state, { type: 'replace', value: { x: 20 } })
    state = historyReducer(state, { type: 'checkpoint', before })

    expect(state.past).toEqual([{ x: 0 }])
    expect(historyReducer(state, { type: 'undo' }).present).toEqual({ x: 0 })
  })
})
