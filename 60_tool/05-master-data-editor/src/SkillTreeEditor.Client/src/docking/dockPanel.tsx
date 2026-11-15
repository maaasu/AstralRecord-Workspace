import { createContext, useContext, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import type { DockviewApi } from 'dockview-react'
import { toolIds, toolSpecs } from '../data/desktopTools'
import type { ToolPage } from '../api/toolJobApi'

export const editorPages = [
  { id: 'master', label: 'マスターデータ', icon: 'M4 3h10l4 4v14H4z M13 3v5h5 M8 12h6 M8 16h6' },
  { id: 'design', label: '装備・入手経路', icon: 'M3 3h6v6H3z M15 15h6v6h-6z M6 9v9h9 M15 3h6v6h-6z M9 6h6' },
  { id: 'skilltree', label: 'スキルツリー', icon: 'M12 6v5 M5 16v-5h14v5 M9 2h6v4H9z M2 16h6v5H2z M16 16h6v5h-6z' },
  { id: 'analytics', label: '比較・成長グラフ', icon: 'M3 3v18h18 M6 15l5-6 4 3 5-8' },
  { id: 'spawner', label: 'ワールド配置・編集', icon: 'M3 5l6-2 6 2 6-2v16l-6 2-6-2-6 2z M9 3v16 M15 5v16' },
] as const

export type EditorPage = (typeof editorPages)[number]['id']
export type Page = EditorPage | ToolPage
export type DockPanelId = Page | 'navigation' | 'master-folders' | 'master-files' | 'master-validation'

export const isToolPage = (id: DockPanelId): id is ToolPage => id === 'history' || toolIds.some(tool => tool === id)
export const isPage = (id: DockPanelId): id is Page => editorPages.some(page => page.id === id) || isToolPage(id)

export const dockPanels: Record<DockPanelId, string> = {
  navigation: '編集ナビ',
  'master-folders': 'フォルダ',
  'master-files': 'ファイル・検索結果',
  master: 'マスターデータ',
  'master-validation': '検証結果',
  design: '装備・入手経路',
  skilltree: 'スキルツリー',
  analytics: '比較・成長グラフ',
  spawner: 'ワールド配置・編集',
  '03': toolSpecs['03'].label,
  '01': toolSpecs['01'].label,
  '12': toolSpecs['12'].label,
  '16': toolSpecs['16'].label,
  history: '実行履歴',
}

export const dockPanelIds = Object.keys(dockPanels) as DockPanelId[]
export const isDockPanelId = (id: string): id is DockPanelId => Object.hasOwn(dockPanels, id)

export interface DockingState {
  api: DockviewApi | undefined
  targets: ReadonlyMap<DockPanelId, HTMLElement>
  visible: ReadonlySet<DockPanelId>
  open: ReadonlySet<DockPanelId>
  visited: ReadonlySet<DockPanelId>
  activeId: DockPanelId | undefined
  documents: ReadonlyMap<DockPanelId, Document>
  show: (id: DockPanelId, activate?: boolean) => void
  close: (id: DockPanelId) => void
  reset: () => void
}

export const DockingContext = createContext<DockingState | null>(null)
const PanelDocumentContext = createContext<Document | null>(null)
const PanelVisibilityContext = createContext(true)

export const useDocking = () => useContext(DockingContext)
export const usePanelDocument = () => useContext(PanelDocumentContext) ?? document
export const usePanelVisibility = () => useContext(PanelVisibilityContext)

export function DockPanelContent({ id, children }: { id: DockPanelId; children: ReactNode }) {
  const docking = useDocking()
  if (!docking) return children
  const target = docking.targets.get(id)!
  const ownerDocument = docking.documents.get(id) ?? document
  // The target is stable for the entire editor session. A slot can adopt it into
  // a different document without asking React to recreate its component tree.
  return createPortal(<PanelDocumentContext.Provider value={ownerDocument}>
    <PanelVisibilityContext.Provider value={docking.visible.has(id)}>{children}</PanelVisibilityContext.Provider>
  </PanelDocumentContext.Provider>, target)
}
