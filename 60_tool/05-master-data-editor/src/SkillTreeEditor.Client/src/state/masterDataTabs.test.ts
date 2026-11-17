import { describe, expect, it } from 'vitest'
import { emptyMasterTabs, masterTabsReducer } from './masterDataTabs'

describe('master file preview tabs', () => {
  it('replaces a clean preview in place while preserving retained tabs', () => {
    let state = masterTabsReducer(emptyMasterTabs, { type: 'open', path: 'kept.yml' })
    state = masterTabsReducer(state, { type: 'open', path: 'first.yml', preview: true })
    const firstId = state.activeId
    state = masterTabsReducer(state, { type: 'open', path: 'next.yml', preview: true })
    expect(state.tabs.map((tab) => tab.path)).toEqual(['kept.yml', 'next.yml'])
    expect(state.tabs[1]).toMatchObject({ preview: true, loadState: 'loading' })
    expect(state.activeId).not.toBe(firstId)
  })

  it('retains a preview after any edit even if undo later makes the file clean', () => {
    let state = masterTabsReducer(emptyMasterTabs, { type: 'open', path: 'first.yml', preview: true })
    const id = state.activeId
    const status = { dirty: true, busy: false, name: '編集', loadState: 'ready' as const }
    state = masterTabsReducer(state, { type: 'status', id, status })
    expect(state.tabs[0].preview).toBe(false)
    state = masterTabsReducer(state, { type: 'status', id, status: { ...status, dirty: false } })
    state = masterTabsReducer(state, { type: 'open', path: 'next.yml', preview: true })
    expect(state.tabs.map((tab) => tab.path)).toEqual(['first.yml', 'next.yml'])
  })

  it('retains busy previews and has only one new replaceable preview', () => {
    let state = masterTabsReducer(emptyMasterTabs, { type: 'open', path: 'first.yml', preview: true })
    state = masterTabsReducer(state, { type: 'status', id: state.activeId, status: { dirty: false, busy: true, name: '', loadState: 'ready' } })
    state = masterTabsReducer(state, { type: 'open', path: 'next.yml', preview: true })
    expect(state.tabs).toHaveLength(2)
    expect(state.tabs[0]).toMatchObject({ busy: true, preview: false })
    expect(state.tabs.filter((tab) => tab.preview)).toHaveLength(1)
  })

  it('promotes an existing preview on a retained open and never demotes a kept tab', () => {
    let state = masterTabsReducer(emptyMasterTabs, { type: 'open', path: 'first.yml', preview: true })
    const id = state.activeId
    state = masterTabsReducer(state, { type: 'open', path: 'first.yml' })
    expect(state.tabs[0]).toMatchObject({ id, preview: false })
    state = masterTabsReducer(state, { type: 'open', path: 'first.yml', preview: true })
    expect(state.tabs[0]).toMatchObject({ id, preview: false })
  })

  it('ignores the late status of a replaced preview and allows explicitly pinning it', () => {
    let state = masterTabsReducer(emptyMasterTabs, { type: 'open', path: 'first.yml', preview: true })
    const firstId = state.activeId
    state = masterTabsReducer(state, { type: 'open', path: 'next.yml', preview: true })
    const stale = masterTabsReducer(state, { type: 'status', id: firstId, status: { dirty: true, busy: false, name: '古い応答', loadState: 'ready' } })
    expect(stale).toBe(state)
    state = masterTabsReducer(state, { type: 'pin', id: state.activeId })
    expect(state.tabs[0].preview).toBe(false)
  })
})
