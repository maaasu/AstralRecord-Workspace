import type { MasterDocument } from '../types/masterData'

export interface MasterTabStatus { dirty: boolean; busy: boolean; name: string }
export interface MasterFileTab extends MasterTabStatus { id: string; path: string; initialDocument?: MasterDocument }
export interface MasterTabsState { tabs: MasterFileTab[]; activeId: string; sequence: number }
export const emptyMasterTabs: MasterTabsState = { tabs: [], activeId: '', sequence: 0 }
export type MasterTabsAction =
  | { type: 'open'; path: string; document?: MasterDocument }
  | { type: 'select'; id: string }
  | { type: 'status'; id: string; status: MasterTabStatus }
  | { type: 'close'; id: string }

export function masterTabsReducer(state: MasterTabsState, action: MasterTabsAction): MasterTabsState {
  if (action.type === 'open') {
    const existing = state.tabs.find((tab) => tab.path === action.path)
    if (existing) return { ...state, activeId: existing.id }
    const sequence = state.sequence + 1
    const tab: MasterFileTab = { id: `master-file-${sequence}`, path: action.path, initialDocument: action.document, dirty: false, busy: false, name: '' }
    return { tabs: [...state.tabs, tab], activeId: tab.id, sequence }
  }
  if (action.type === 'select') return state.tabs.some((tab) => tab.id === action.id) ? { ...state, activeId: action.id } : state
  if (action.type === 'status') {
    const tab = state.tabs.find((entry) => entry.id === action.id)
    if (!tab || tab.dirty === action.status.dirty && tab.busy === action.status.busy && tab.name === action.status.name) return state
    return { ...state, tabs: state.tabs.map((entry) => entry.id === action.id ? { ...entry, ...action.status } : entry) }
  }
  const index = state.tabs.findIndex((tab) => tab.id === action.id)
  if (index < 0) return state
  const tabs = state.tabs.filter((tab) => tab.id !== action.id)
  return { ...state, tabs, activeId: state.activeId === action.id ? (tabs[index] ?? tabs[index - 1])?.id ?? '' : state.activeId }
}
