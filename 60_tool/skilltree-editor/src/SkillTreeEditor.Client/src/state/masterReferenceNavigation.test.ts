import { describe, expect, it } from 'vitest'
import { emptyMasterTabs, masterTabsReducer } from './masterDataTabs'

describe('reference navigation requests', () => {
  it('opens once, replaces requests on repeat clicks, and ignores stale acknowledgements', () => {
    const first = { requestId: 1, pointer: '/items/30/id', value: 'mob:first' }
    let state = masterTabsReducer(emptyMasterTabs, { type: 'open', path: 'mob.yml', referenceTarget: first })
    const tabId = state.activeId
    state = masterTabsReducer(state, { type: 'status', id: tabId, status: { name: '原稿', dirty: true, busy: true } })
    state = masterTabsReducer(state, { type: 'open', path: 'mob.yml', referenceTarget: { ...first, requestId: 2 } })
    expect(state.tabs).toHaveLength(1)
    expect(state.tabs[0]).toMatchObject({ id: tabId, name: '原稿', dirty: true, busy: true, referenceTarget: { requestId: 2 } })
    expect(masterTabsReducer(state, { type: 'referenceHandled', id: tabId, requestId: 1 })).toBe(state)
    state = masterTabsReducer(state, { type: 'referenceHandled', id: tabId, requestId: 2 })
    expect(state.tabs[0].referenceTarget).toBeUndefined()
    expect(state.tabs[0].dirty).toBe(true)
  })

  it('cancels a pending position when the file is explicitly reopened without a position', () => {
    let state = masterTabsReducer(emptyMasterTabs, { type: 'open', path: 'mob.yml', referenceTarget: { requestId: 1, pointer: '/id', value: 'mob' } })
    state = masterTabsReducer(state, { type: 'open', path: 'mob.yml' })
    expect(state.tabs[0].referenceTarget).toBeUndefined()
    expect(state.tabs).toHaveLength(1)
  })
})
