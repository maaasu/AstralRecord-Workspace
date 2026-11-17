import type { MasterDocument } from '../types/masterData'

export interface MasterTabStatus { dirty: boolean; busy: boolean; name: string; loadState: 'loading' | 'ready' | 'error' }
export interface MasterReferenceTarget { requestId: number; pointer: string; value: string; locationOnly?: boolean }
export interface MasterFileTab extends MasterTabStatus { id: string; path: string; preview?: boolean; initialDocument?: MasterDocument; referenceTarget?: MasterReferenceTarget }
export interface MasterTabsState { tabs: MasterFileTab[]; activeId: string; sequence: number }
export const emptyMasterTabs: MasterTabsState = { tabs: [], activeId: '', sequence: 0 }
export type MasterTabsAction =
  | { type: 'open'; path: string; document?: MasterDocument; referenceTarget?: MasterReferenceTarget; preview?: boolean }
  | { type: 'pin'; id: string }
  | { type: 'referenceHandled'; id: string; requestId: number }
  | { type: 'select'; id: string }
  | { type: 'status'; id: string; status: MasterTabStatus }
  | { type: 'close'; id: string }
  | { type: 'closeMany'; ids: string[] }

export function masterTabsReducer(state: MasterTabsState, action: MasterTabsAction): MasterTabsState {
  if (action.type === 'open') {
    const existing = state.tabs.find((tab) => tab.path === action.path)
    if (existing) return { ...state, activeId: existing.id, tabs: state.tabs.map(tab => tab.id === existing.id ? { ...tab, preview: Boolean(tab.preview && action.preview), referenceTarget: action.referenceTarget } : tab) }
    const sequence = state.sequence + 1
    const tab: MasterFileTab = { id: `master-file-${sequence}`, path: action.path, preview: action.preview === true, initialDocument: action.document, referenceTarget: action.referenceTarget, dirty: false, busy: false, name: '', loadState: 'loading' }
    const previewIndex = action.preview ? state.tabs.findIndex((entry) => entry.preview && !entry.dirty && !entry.busy) : -1
    const tabs = action.preview ? state.tabs.map((entry) => entry.preview ? { ...entry, preview: false } : entry) : [...state.tabs]
    if (previewIndex >= 0) tabs[previewIndex] = tab
    else tabs.push(tab)
    return { tabs, activeId: tab.id, sequence }
  }
  if (action.type === 'pin') return state.tabs.some((tab) => tab.id === action.id && tab.preview)
    ? { ...state, tabs: state.tabs.map((tab) => tab.id === action.id ? { ...tab, preview: false } : tab) } : state
  if (action.type === 'select') return state.tabs.some((tab) => tab.id === action.id) ? { ...state, activeId: action.id } : state
  if (action.type === 'referenceHandled') {
    if (!state.tabs.some(tab => tab.id === action.id && tab.referenceTarget?.requestId === action.requestId)) return state
    return { ...state, tabs: state.tabs.map(tab => tab.id === action.id ? { ...tab, referenceTarget: undefined } : tab) }
  }
  if (action.type === 'status') {
    const tab = state.tabs.find((entry) => entry.id === action.id)
    if (!tab || tab.dirty === action.status.dirty && tab.busy === action.status.busy && tab.name === action.status.name && tab.loadState === action.status.loadState && !(tab.preview && action.status.dirty)) return state
    return { ...state, tabs: state.tabs.map((entry) => entry.id === action.id ? { ...entry, ...action.status, preview: Boolean(entry.preview && !action.status.dirty) } : entry) }
  }
  const ids = new Set(action.type === 'closeMany' ? action.ids : [action.id])
  const tabs = state.tabs.filter((tab) => !ids.has(tab.id))
  if (tabs.length === state.tabs.length) return state
  const index = state.tabs.findIndex((tab) => tab.id === state.activeId)
  const adjacent = state.tabs.slice(index + 1).find((tab) => !ids.has(tab.id))
    ?? state.tabs.slice(0, index).reverse().find((tab) => !ids.has(tab.id))
  return { ...state, tabs, activeId: ids.has(state.activeId) ? adjacent?.id ?? '' : state.activeId }
}
