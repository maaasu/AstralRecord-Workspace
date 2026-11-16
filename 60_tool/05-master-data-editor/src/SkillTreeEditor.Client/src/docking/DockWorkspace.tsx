import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { DockviewReact, themeVisualStudio, type AddPanelOptions, type DockviewApi, type DockviewReadyEvent, type GetTabContextMenuItemsParams, type IDockviewHeaderActionsProps, type IDockviewPanelProps, type SerializedDockview } from 'dockview-react'
import 'dockview-react/dist/styles/dockview.css'
import { DockCommandMenu, type DockCommand } from './DockCommandMenu'
import { DockIcon } from './DockIcon'
import { DockingContext, dockPanelIds, dockPanels, isDockPanelId, isPage, useDocking, type DockPanelId, type DockingState } from './dockPanel'
import './dockWorkspace.css'

const theme = { ...themeVisualStudio, name: 'astral', className: 'dockview-theme-astral', gap: 4, dndTabIndicator: 'line' as const }
const component = 'workspace-panel'
const storagePrefix = 'astral-editor:docking:v1:'
const navigation = 'navigation'
const defaultEditorGroupId = 'editor'
const panelConstraints = { minimumWidth: 128, minimumHeight: 92 }
const defaultWidths: Partial<Record<DockPanelId, number>> = { navigation: 184, 'master-folders': 196, 'master-files': 310 }
const messages = { closeTab: (title: string) => `${title}パネルを閉じる`, closeTabPlain: () => 'パネルを閉じる' }

export type DesktopBridge = { postMessage: (message: unknown) => void }
declare global {
  interface Window { astralEditorReturnPopout?: (child?: Window) => void }
}

export function getDesktopBridge() {
  return (window as Window & { chrome?: { webview?: DesktopBridge } }).chrome?.webview
}

function addPanel(api: DockviewApi, id: DockPanelId, options?: Partial<AddPanelOptions>) {
  return api.addPanel({ id, component, title: dockPanels[id], renderer: 'always', ...panelConstraints, ...options })
}

function defaultLayout(api: DockviewApi) {
  api.clear()
  const editorGroup = api.addGroup({ id: defaultEditorGroupId, direction: 'right', constraints: panelConstraints })
  addPanel(api, 'master', { position: { referenceGroup: editorGroup } })
  if (window.innerWidth < 900) {
    addPanel(api, navigation, { position: { direction: 'left' }, initialWidth: defaultWidths.navigation, inactive: true })
    addPanel(api, 'master-folders', { position: { referencePanel: navigation, direction: 'within' }, inactive: true })
    addPanel(api, 'master-files', { position: { referencePanel: navigation, direction: 'within' }, inactive: true })
    return
  }
  addPanel(api, 'master-files', { position: { referencePanel: 'master', direction: 'left' }, initialWidth: defaultWidths['master-files'], inactive: true })
  addPanel(api, 'master-folders', { position: { referencePanel: 'master-files', direction: 'left' }, initialWidth: defaultWidths['master-folders'], inactive: true })
  addPanel(api, navigation, { position: { direction: 'left' }, initialWidth: defaultWidths.navigation, inactive: true })
  for (const id of ['master-files', 'master-folders', navigation] as const) {
    api.getPanel(id)?.group.api.setSize({ width: defaultWidths[id] })
  }
}

type WorkspaceLayout = SerializedDockview & { editorGroupId?: string }
type GridNode = SerializedDockview['grid']['root']

function restoreGridSizes({ data }: GridNode) {
  if (!Array.isArray(data)) return
  // Dockview serializes rendered sizes but restores the sizes before sash gaps.
  const visibleCount = data.filter(child => child.visible !== false).length
  const gapShare = visibleCount > 1 ? theme.gap * (visibleCount - 1) / visibleCount : 0
  for (const child of data) {
    if (child.visible !== false && typeof child.size === 'number') child.size += gapShare
    restoreGridSizes(child)
  }
}

function loadLayout(key: string, api: DockviewApi) {
  try {
    const raw = window.localStorage.getItem(key)
    if (!raw) return
    const layout = JSON.parse(raw) as WorkspaceLayout
    if (!layout.grid?.root || typeof layout.panels !== 'object' || !layout.panels) return
    for (const [id, panel] of Object.entries(layout.panels)) {
      if (!isDockPanelId(id) || panel.id !== id || panel.contentComponent !== component) return
      panel.title = dockPanels[id]
    }
    // Always use the bundled host page, including when the layout was saved on
    // a different loopback port in a previous desktop session.
    for (const popout of layout.popoutGroups ?? []) {
      popout.url = '/popout.html'
      if (popout.grid) restoreGridSizes(popout.grid.root)
    }
    for (const floating of layout.floatingGroups ?? []) {
      if (floating.grid) restoreGridSizes(floating.grid.root)
    }
    restoreGridSizes(layout.grid.root)
    api.fromJSON(layout)
    return layout
  } catch { /* Ignore invalid or unavailable saved layouts. */ }
}

function gridGroup(api: DockviewApi, id?: string) {
  return api.groups.find(group => group.id === id && group.api.location.type === 'grid')
}

function serializedGroups({ data }: GridNode): Exclude<GridNode['data'], GridNode[]>[] {
  return Array.isArray(data) ? data.flatMap(serializedGroups) : [data]
}

function restoreEditorGroupId(layout: WorkspaceLayout) {
  const groups = serializedGroups(layout.grid.root)
  const saved = groups.find(group => group.id === layout.editorGroupId)
  const master = groups.find(group => group.views.includes('master'))
  const mainPage = groups.find(group => group.views.some(id => isDockPanelId(id) && isPage(id)))
  const legacyPopout = layout.popoutGroups?.find(({ data, grid }) => data?.views.includes('master') ||
    (grid && serializedGroups(grid.root).some(group => group.views.includes('master'))))
  return saved?.id ?? master?.id ?? legacyPopout?.gridReferenceGroup ?? mainPage?.id ?? defaultEditorGroupId
}

type LayoutState = Pick<DockingState, 'open' | 'visible' | 'visited' | 'activeId' | 'documents' | 'editorGroupId'>
const emptyState: LayoutState = { open: new Set(), visible: new Set(), visited: new Set(), activeId: undefined, documents: new Map(), editorGroupId: defaultEditorGroupId }

function WorkspaceSlot({ api }: IDockviewPanelProps) {
  const docking = useDocking()
  const slot = useRef<HTMLDivElement>(null)
  const target = isDockPanelId(api.id) ? docking?.targets.get(api.id) : undefined
  useLayoutEffect(() => {
    const host = slot.current
    if (!host || !target) return
    // Dockview hides the always renderer with visibility, which a child can override.
    host.hidden = !api.isVisible
    const visibility = api.onDidVisibilityChange(({ isVisible }) => { host.hidden = !isVisible })
    host.appendChild(target)
    return () => {
      visibility.dispose()
      if (target.parentNode === host) target.remove()
    }
  }, [api, target])
  return <div ref={slot} className="dock-panel-slot" />
}

const components = { [component]: WorkspaceSlot }

function focusPanel(api: DockviewApi, editorGroupId: string, id: DockPanelId, activate: boolean) {
  const panel = api.getPanel(id)
  if (panel) {
    if (activate) {
      panel.api.setActive()
      const panelWindow = panel.api.getWindow()
      if (panelWindow !== window) panelWindow.focus()
    }
    return
  }
  if (id === navigation || id === 'master-folders' || id === 'master-files') {
    const files = api.getPanel('master-files')
    const referenceGroup = id === 'master-folders' && files?.api.location.type === 'grid' ? files.group.id : editorGroupId
    const position = id !== navigation
      ? { referenceGroup, direction: 'left' as const }
      : { direction: 'left' as const }
    addPanel(api, id, { position, initialWidth: defaultWidths[id], inactive: !activate })
  } else if (id === 'master-validation') {
    addPanel(api, id, { position: { referenceGroup: editorGroupId, direction: 'below' }, initialHeight: 208, inactive: !activate })
  } else {
    addPanel(api, id, { position: { referenceGroup: editorGroupId, direction: 'within' }, inactive: !activate })
  }
}

function tabMenuItems({ panel, group }: GetTabContextMenuItemsParams) {
  const tabs = group.panels
  const index = tabs.indexOf(panel)
  const close = (label: string, targets: typeof tabs) => ({
    label,
    action: () => targets.forEach(tab => tab.api.close()),
    disabled: targets.length === 0,
  })
  return [
    close('タブを閉じる', [panel]),
    close('他のタブを閉じる', tabs.filter(tab => tab !== panel)),
    close('すべてのタブを閉じる', tabs.slice()),
    'separator' as const,
    close('右側のタブを閉じる', tabs.slice(index + 1)),
    close('左側のタブを閉じる', tabs.slice(0, index)),
  ]
}

function returnPopouts(api: DockviewApi, child?: Window) {
  for (const { window: popout } of api.getPopouts()) {
    if (!child || child === popout) {
      // Dockview's own unload path returns every group in this window before
      // releasing its handle, including secondary groups docked into its grid.
      popout.dispatchEvent(new Event('beforeunload'))
    }
  }
}

export function DockWorkspace({ desktop, layoutKey, children }: { desktop?: DesktopBridge; layoutKey?: string; children: ReactNode }) {
  const [api, setApi] = useState<DockviewApi>()
  const [state, setState] = useState(emptyState)
  const [error, setError] = useState('')
  const [targets] = useState(() => new Map(dockPanelIds.map(id => {
    const element = document.createElement('div')
    element.className = 'dock-panel-content'
    element.dataset.dockPanel = id
    return [id, element]
  })))
  const subscribedKey = useRef<string | undefined>(undefined)
  const editorGroupId = useRef(defaultEditorGroupId)
  const onReady = useCallback(({ api }: DockviewReadyEvent) => {
    defaultLayout(api)
    setApi(api)
  }, [])
  const [suspended, setSuspended] = useState(false)
  const persistencePaused = useRef(false)
  const closing = useRef(false)
  const ensureEditorGroup = (container: DockviewApi) => {
    const existing = gridGroup(container, editorGroupId.current)
    if (existing) return existing
    const id = container.getGroup(defaultEditorGroupId) ? undefined : defaultEditorGroupId
    const group = container.addGroup({ id, direction: 'right', skipSetActive: true, constraints: panelConstraints })
    editorGroupId.current = group.id
    return group
  }
  useEffect(() => {
    if (!api) return
    const publish = () => {
      const open = new Set<DockPanelId>()
      const visible = new Set<DockPanelId>()
      const documents = new Map<DockPanelId, Document>()
      for (const panel of api.panels) {
        if (!isDockPanelId(panel.id)) continue
        open.add(panel.id)
        if (panel.api.isVisible) visible.add(panel.id)
        documents.set(panel.id, panel.api.getWindow().document)
      }
      const active = api.activePanel?.id
      const activeId = active && isDockPanelId(active) ? active : undefined
      const groupId = editorGroupId.current
      setState(previous => {
        if (activeId === previous.activeId && groupId === previous.editorGroupId && open.size === previous.open.size && visible.size === previous.visible.size
          && [...documents].every(([id, doc]) => previous.documents.get(id) === doc)
          && [...visible].every(id => previous.visible.has(id))) return previous
        return { open, visible, documents, activeId, editorGroupId: groupId, visited: new Set([...previous.visited, ...open]) }
      })
    }
    const save = () => {
      if (!layoutKey || persistencePaused.current || closing.current) return
      try {
        const layout: WorkspaceLayout = { ...api.toJSON(), editorGroupId: editorGroupId.current }
        window.localStorage.setItem(layoutKey, JSON.stringify(layout))
      } catch { /* Storage can be unavailable in a browser session. */ }
    }
    const maintainLayout = () => {
      if (persistencePaused.current || closing.current) return
      const editor = ensureEditorGroup(api)
      if (!editor.api.isVisible && !api.hasMaximizedGroup()) editor.api.setVisible(true)
      // Dockview retains all empty groups with closeEmptyGroups disabled. Keep
      // the editor and any hidden docking positions for separate windows.
      for (const group of api.groups) {
        if (group === editor || group.size !== 0) continue
        if (group.api.location.type !== 'grid' || group.api.isVisible) api.removeGroup(group)
      }
    }
    const listeners = [
      api.onDidLayoutChange(() => { maintainLayout(); publish(); save() }),
      api.onDidActivePanelChange(publish),
      api.onDidAddPanel(publish),
      api.onDidRemovePanel(publish),
      api.onDidPopoutGroupSizeChange(save),
      api.onDidPopoutGroupPositionChange(save),
      api.onDidOpenPopoutWindowFail(() => setError('別ウィンドウを開けませんでした。画面内で作業を続けられます。')),
      api.onWillClosePopoutWindow(event => desktop?.postMessage({ type: 'close-popout', id: event.id })),
    ]
    const stylesheetObservers = new Map<Window, MutationObserver>()
    listeners.push(api.onDidAddPopoutGroup(({ window: child }) => {
      publish()
      const observer = new MutationObserver(records => {
        for (const record of records) {
          for (const node of record.addedNodes) {
            if (node instanceof Element && node.matches('link[rel="stylesheet"], style')) {
              child.document.head.appendChild(node.cloneNode(true))
            }
          }
        }
      })
      observer.observe(document.head, { childList: true })
      stylesheetObservers.set(child, observer)
    }))
    listeners.push(api.onDidRemovePopoutGroup(({ window: child }) => {
      stylesheetObservers.get(child)?.disconnect()
      stylesheetObservers.delete(child)
      publish()
    }))

    let live = true
    if (layoutKey && layoutKey !== subscribedKey.current) {
      subscribedKey.current = layoutKey
      persistencePaused.current = true
      const layout = loadLayout(layoutKey, api)
      if (layout) {
        editorGroupId.current = restoreEditorGroupId(layout)
        gridGroup(api, editorGroupId.current)?.api.setConstraints(panelConstraints)
        setSuspended(true)
        void api.popoutRestorationPromise.then(() => {
          if (!live) return
          persistencePaused.current = false
          setSuspended(false)
          maintainLayout()
          publish()
          save()
        })
      } else {
        if (!gridGroup(api, editorGroupId.current)) {
          editorGroupId.current = defaultEditorGroupId
          defaultLayout(api)
        }
        persistencePaused.current = false
        save()
      }
    }
    const returnPopout = (child?: Window) => {
      if (!child) {
        save()
        closing.current = true
      }
      returnPopouts(api, child)
    }
    window.astralEditorReturnPopout = returnPopout
    publish()
    return () => {
      live = false
      listeners.forEach(listener => listener.dispose())
      stylesheetObservers.forEach(observer => observer.disconnect())
      if (window.astralEditorReturnPopout === returnPopout) delete window.astralEditorReturnPopout
    }
  }, [api, desktop, layoutKey])

  const stateWithActions: DockingState = {
    ...state, api, targets,
    show: (id, activate = true) => { if (api) focusPanel(api, ensureEditorGroup(api).id, id, activate) },
    close: id => api?.getPanel(id)?.api.close(),
    reset: () => {
      if (!api) return
      editorGroupId.current = defaultEditorGroupId
      defaultLayout(api)
    },
  }
  return <DockingContext.Provider value={stateWithActions}>
    {children}
    {error && <div className="dock-error" role="alert">{error}<button type="button" onClick={() => setError('')} aria-label="通知を閉じる">×</button></div>}
    <div className="dock-layout" aria-busy={suspended}>
      <DockviewReact onReady={onReady} components={components} rightHeaderActionsComponent={GroupActions}
        watermarkComponent={EmptyWorkspace} theme={theme} messages={messages} getTabContextMenuItems={tabMenuItems}
        defaultRenderer="always" proportionalLayout={false} closeEmptyGroups={false} popoutUrl="/popout.html" />
    </div>
  </DockingContext.Provider>
}

export const workspaceLayoutKey = (workspaceRoot?: string) => workspaceRoot ? `${storagePrefix}${workspaceRoot}` : undefined

function EmptyWorkspace() {
  return <div className="dock-empty">「パネル」メニューから開く画面を選べます。</div>
}

type DockPanel = NonNullable<IDockviewHeaderActionsProps['activePanel']>

function openInNewWindow(api: DockviewApi, panel: DockPanel, editorGroupId: string | undefined) {
  const { screenX, screenY } = panel.api.getWindow()
  const target = panel.group.id !== editorGroupId && panel.group.size === 1 ? panel.group : panel
  void api.addPopoutGroup(target, {
    position: { left: screenX + 65, top: screenY + 65, width: Math.max(420, panel.api.width), height: Math.max(340, panel.api.height) },
  })
}

const returnToMainLabel = 'メインウィンドウに戻す'

export function DockWindowButton({ api, panel, compact = false }: {
  api?: DockviewApi
  panel?: DockPanel
  compact?: boolean
}) {
  const editorGroupId = useDocking()?.editorGroupId
  const poppedOut = panel?.api.location.type === 'popout'
  const label = poppedOut ? returnToMainLabel : '別ウィンドウで開く'
  return <button type="button" className={compact ? 'dock-header-button' : 'editor-command-button editor-command-window'} title={label}
    aria-label={label} disabled={!api || !panel} onClick={() => {
      if (!api || !panel) return
      if (poppedOut) returnPopouts(api, panel.api.getWindow())
      else openInNewWindow(api, panel, editorGroupId)
    }}>
    <DockIcon name="windows" />{!compact && <span>{label}</span>}
  </button>
}

function GroupActions({ activePanel: panel, location, containerApi }: IDockviewHeaderActionsProps) {
  const editorGroupId = useDocking()?.editorGroupId
  if (!panel) return null
  const splitDisabled = panel.group.size < 2
  const commands: DockCommand[] = [
    { label: '右に分割', run: () => panel.api.moveTo({ group: panel.group, position: 'right' }), disabled: splitDisabled },
    { label: '下に分割', run: () => panel.api.moveTo({ group: panel.group, position: 'bottom' }), disabled: splitDisabled },
  ]
  if (location?.type === 'grid') commands.push({ label: '画面内にフロート', run: () => containerApi.addFloatingGroup(panel) })
  if (location?.type === 'floating') commands.push({ label: '画面内にドッキング', run: () => {
    const editor = gridGroup(containerApi, editorGroupId)
    if (editor && isDockPanelId(panel.id) && isPage(panel.id)) {
      panel.api.moveTo({ group: editor, position: 'center' })
    } else panel.api.moveTo({ position: 'right' })
  } })
  if (location?.type === 'popout') commands.push({ label: returnToMainLabel, run: () => returnPopouts(containerApi, panel.api.getWindow()) })
  return <div className="dock-group-actions">
    <DockWindowButton api={containerApi} panel={panel} compact />
    <DockCommandMenu label="パネルの操作" className="dock-header-button" groups={[
      { commands },
      { commands: [{ label: 'パネルを閉じる', run: () => panel.api.close() }] },
    ]}><DockIcon name="more" /></DockCommandMenu>
  </div>
}
