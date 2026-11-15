import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { DockviewReact, themeVisualStudio, type AddPanelOptions, type DockviewApi, type DockviewReadyEvent, type IDockviewHeaderActionsProps, type IDockviewPanelProps, type SerializedDockview } from 'dockview-react'
import 'dockview-react/dist/styles/dockview.css'
import { DockingContext, dockPanelIds, dockPanels, isDockPanelId, isPage, useDocking, type DockPanelId, type DockingState } from './dockPanel'
import './dockWorkspace.css'

const theme = { ...themeVisualStudio, name: 'astral', className: 'dockview-theme-astral' }
const component = 'workspace-panel'
const storagePrefix = 'astral-editor:docking:v1:'
const navigation = 'navigation'
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
  return api.addPanel({ id, component, title: dockPanels[id], renderer: 'always', minimumWidth: 128, minimumHeight: 92, ...options })
}

function defaultLayout(api: DockviewApi) {
  api.clear()
  addPanel(api, 'master')
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

function loadLayout(key: string, api: DockviewApi) {
  try {
    const raw = window.localStorage.getItem(key)
    if (!raw) return false
    const layout = JSON.parse(raw) as SerializedDockview
    if (!layout.grid?.root || typeof layout.panels !== 'object' || !layout.panels) return false
    for (const [id, panel] of Object.entries(layout.panels)) {
      if (!isDockPanelId(id) || panel.id !== id || panel.contentComponent !== component) return false
      panel.title = dockPanels[id]
    }
    // Always use the bundled host page, including when the layout was saved on
    // a different loopback port in a previous desktop session.
    for (const popout of layout.popoutGroups ?? []) popout.url = '/popout.html'
    api.fromJSON(layout)
    return true
  } catch {
    return false
  }
}

type LayoutState = Pick<DockingState, 'open' | 'visible' | 'visited' | 'activeId' | 'documents'>
const emptyState: LayoutState = { open: new Set(), visible: new Set(), visited: new Set(), activeId: undefined, documents: new Map() }

function WorkspaceSlot({ api }: IDockviewPanelProps) {
  const docking = useDocking()
  const slot = useRef<HTMLDivElement>(null)
  const target = isDockPanelId(api.id) ? docking?.targets.get(api.id) : undefined
  useLayoutEffect(() => {
    const host = slot.current
    if (!host || !target) return
    host.appendChild(target)
    return () => {
      if (target.parentNode === host) target.remove()
    }
  }, [target])
  return <div ref={slot} className="dock-panel-slot" />
}

const components = { [component]: WorkspaceSlot }

function focusPanel(api: DockviewApi, id: DockPanelId, activate: boolean) {
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
    const referencePanel = id === 'master-folders' ? api.getPanel('master-files') ?? api.getPanel('master') : api.getPanel('master')
    const position = id !== navigation && referencePanel?.api.location.type === 'grid'
      ? { referencePanel, direction: 'left' as const }
      : { direction: 'left' as const }
    addPanel(api, id, { position, initialWidth: defaultWidths[id], inactive: !activate })
  } else if (id === 'master-validation') {
    const master = api.getPanel('master')
    const position = master ? { referencePanel: master, direction: 'below' as const } : { direction: 'below' as const }
    addPanel(api, id, { position, initialHeight: 208, inactive: !activate })
  } else {
    const gridPages = api.panels.filter(item => isDockPanelId(item.id) && isPage(item.id) && item.api.location.type === 'grid')
    const referencePanel = gridPages.find(item => item.id === 'master') ?? gridPages[0]
    const position = referencePanel ? { referencePanel, direction: 'within' as const } : { direction: 'right' as const }
    addPanel(api, id, { position, inactive: !activate })
  }
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
  const onReady = useCallback(({ api }: DockviewReadyEvent) => {
    defaultLayout(api)
    setApi(api)
  }, [])
  const [suspended, setSuspended] = useState(false)
  const persistencePaused = useRef(false)
  const closing = useRef(false)
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
      setState(previous => {
        if (activeId === previous.activeId && open.size === previous.open.size && visible.size === previous.visible.size
          && [...documents].every(([id, doc]) => previous.documents.get(id) === doc)
          && [...visible].every(id => previous.visible.has(id))) return previous
        return { open, visible, documents, activeId, visited: new Set([...previous.visited, ...open]) }
      })
    }
    let lastNavigation: boolean | undefined
    const sendNavigation = () => {
      const visible = api.getPanel(navigation)?.api.isVisible === true
      if (desktop && visible !== lastNavigation) {
        desktop.postMessage({ type: 'workspace-state', navigationVisible: visible })
        lastNavigation = visible
      }
    }
    const save = () => {
      if (!layoutKey || persistencePaused.current || closing.current) return
      try { window.localStorage.setItem(layoutKey, JSON.stringify(api.toJSON())) } catch { /* Storage can be unavailable in a browser session. */ }
    }
    const listeners = [
      api.onDidLayoutChange(() => { publish(); sendNavigation(); save() }),
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
      if (loadLayout(layoutKey, api)) {
        setSuspended(true)
        void api.popoutRestorationPromise.then(() => {
          if (!live) return
          persistencePaused.current = false
          setSuspended(false)
          publish()
          sendNavigation()
          save()
        })
      } else {
        if (api.totalPanels === 0) defaultLayout(api)
        persistencePaused.current = false
        save()
      }
    }
    const command = (event: Event) => {
      const detail: unknown = (event as CustomEvent<unknown>).detail
      if (typeof detail !== 'string') return
      if (detail === 'toggle-navigation') {
        const panel = api.getPanel(navigation)
        if (panel?.api.isVisible) panel.api.close()
        else focusPanel(api, navigation, Boolean(panel))
      } else if (detail === 'reset-layout') defaultLayout(api)
      else if (detail.startsWith('show-')) {
        const id = detail.slice(5)
        if (isDockPanelId(id)) focusPanel(api, id, true)
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
    window.addEventListener('astral-desktop-command', command)
    publish()
    sendNavigation()
    return () => {
      live = false
      listeners.forEach(listener => listener.dispose())
      stylesheetObservers.forEach(observer => observer.disconnect())
      window.removeEventListener('astral-desktop-command', command)
      if (window.astralEditorReturnPopout === returnPopout) delete window.astralEditorReturnPopout
    }
  }, [api, desktop, layoutKey])

  const stateWithActions: DockingState = {
    ...state, api, targets,
    show: (id, activate = true) => { if (api) focusPanel(api, id, activate) },
    close: id => api?.getPanel(id)?.api.close(),
    reset: () => { if (api) defaultLayout(api) },
  }
  return <DockingContext.Provider value={stateWithActions}>
    {error && <div className="dock-error" role="alert">{error}<button type="button" onClick={() => setError('')} aria-label="通知を閉じる">×</button></div>}
    <div className="dock-layout" aria-busy={suspended}>
      <DockviewReact onReady={onReady} components={components} rightHeaderActionsComponent={GroupActions}
        watermarkComponent={EmptyWorkspace} theme={theme} messages={messages}
        defaultRenderer="always" proportionalLayout={false} popoutUrl="/popout.html" />
    </div>
    {children}
  </DockingContext.Provider>
}

export const workspaceLayoutKey = (workspaceRoot?: string) => workspaceRoot ? `${storagePrefix}${workspaceRoot}` : undefined

function EmptyWorkspace() {
  return <div className="dock-empty"><DockMenu /></div>
}

function GroupActions({ activePanel, location, containerApi }: IDockviewHeaderActionsProps) {
  const panel = activePanel
  return <div className="dock-group-actions">
    {panel && location?.type === 'popout' && <button type="button" className="dock-header-button" title="メインウィンドウに戻す" aria-label="メインウィンドウに戻す" onClick={() => returnPopouts(containerApi, panel.api.getWindow())}>↙</button>}
    {panel && (location?.type === 'grid' || location?.type === 'floating') && <button type="button" className="dock-header-button" title="別ウィンドウで開く" aria-label="別ウィンドウで開く" onClick={() => {
      const window = panel.api.getWindow()
      void containerApi.addPopoutGroup(panel, {
        position: { left: window.screenX + 65, top: window.screenY + 65, width: Math.max(420, panel.api.width), height: Math.max(340, panel.api.height) },
      })
    }}>↗</button>}
    <DockMenu panel={panel} />
  </div>
}

export function DockMenu({ panel, label = 'パネルの操作' }: {
  panel?: IDockviewHeaderActionsProps['activePanel']
  label?: string
}) {
  const docking = useDocking()
  const opener = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)
  const [position, setPosition] = useState<{ top: number; right: number; document: Document }>()
  useEffect(() => {
    if (!position) return
    const doc = position.document
    const dismiss = (event: PointerEvent) => {
      const target = event.target as Node
      if (!opener.current?.contains(target) && !menu.current?.contains(target)) setPosition(undefined)
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setPosition(undefined)
      opener.current?.focus()
    }
    doc.addEventListener('pointerdown', dismiss)
    doc.addEventListener('keydown', escape)
    menu.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
    return () => {
      doc.removeEventListener('pointerdown', dismiss)
      doc.removeEventListener('keydown', escape)
    }
  }, [position])
  const run = (action: () => void) => { setPosition(undefined); action() }
  return <>
    <button type="button" ref={opener} className="dock-header-button" title={label} aria-label={label} aria-expanded={Boolean(position)} onClick={event => {
      const doc = event.currentTarget.ownerDocument
      const rect = event.currentTarget.getBoundingClientRect()
      setPosition(previous => previous ? undefined : { top: rect.bottom + 2, right: Math.max(4, doc.documentElement.clientWidth - rect.right), document: doc })
    }}>⋯</button>
    {position && createPortal(<div className="dock-menu" ref={menu} style={{ top: position.top, right: position.right }} aria-label={label}>
      {panel && <>
        <button type="button" onClick={() => run(() => panel.api.moveTo({ group: panel.group, position: 'right' }))} disabled={panel.group.size < 2}>右に分割</button>
        <button type="button" onClick={() => run(() => panel.api.moveTo({ group: panel.group, position: 'bottom' }))} disabled={panel.group.size < 2}>下に分割</button>
        {panel.api.location.type === 'grid' && <button type="button" onClick={() => run(() => docking?.api?.addFloatingGroup(panel))}>画面内にフロート</button>}
        {panel.api.location.type === 'floating' && <button type="button" onClick={() => run(() => panel.api.moveTo({ position: 'right' }))}>画面内にドッキング</button>}
        <button type="button" onClick={() => run(() => panel.api.close())}>パネルを閉じる</button>
        <hr />
      </>}
      {dockPanelIds.map(id => <button key={id} type="button" onClick={() => run(() => docking?.show(id))}>{dockPanels[id]}を表示</button>)}
      <hr />
      <button type="button" onClick={() => run(() => docking?.reset())}>レイアウトを初期化</button>
    </div>, position.document.body)}
  </>
}
