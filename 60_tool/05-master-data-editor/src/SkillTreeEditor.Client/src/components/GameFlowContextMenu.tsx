import { useCallback, useEffect, useLayoutEffect, useRef, useState, type MouseEvent } from 'react'
import { createPortal } from 'react-dom'
import { refId, type GameFlowNode } from '../data/gameFlow'
import { usePanelDocument, usePanelVisibility } from '../docking/dockPanel'
import type { MasterFileSummary } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'

export interface ResolvedRef { name: string; file?: MasterFileSummary }

/** 右クリックメニューの項目。区切り線は null。 */
export type ContextCommand = { label: string; run: () => void; disabled?: boolean } | null

export interface FlowCopyTarget {
  name?: string | null
  ref?: string | null
  nodeId?: string | null
  masterId?: string | null
  path?: string | null
  values?: readonly { label: string; value?: string | null }[]
}

export type FlowCopyContextMenu = (target: FlowCopyTarget, title: string) => (event: MouseEvent<HTMLElement>) => void

function workspaceFilePath(path?: string | null) {
  const relative = path?.replaceAll('\\', '/')
  if (!relative || relative.split('/').some(part => !part.trim() || part === '..' || part === '.') || relative.includes(':')) return
  return relative.startsWith('40_filebase/') ? relative : `40_filebase/${relative}`
}

export function flowCopyCommands(target: FlowCopyTarget, copy: (value: string) => void): ContextCommand[] {
  const values = [
    { label: '表示名をコピー', value: target.name && stripMinecraftFormatting(target.name).trim() },
    { label: '参照をコピー', value: target.ref },
    { label: 'ノードIDをコピー', value: target.nodeId },
    { label: 'マスターIDをコピー', value: target.masterId },
    { label: 'ワークスペース相対パスをコピー', value: workspaceFilePath(target.path) },
    ...(target.values ?? []),
  ]
  return values.flatMap(({ label, value }) => value?.trim() ? [{ label, run: () => copy(value) }] : [])
}

export function flowReferenceCopyTarget(ref: string | undefined, resolved?: ResolvedRef): FlowCopyTarget {
  return {
    name: resolved?.name,
    ref,
    masterId: ref && ref.indexOf(':') > 0 ? refId(ref) : undefined,
    path: resolved?.file?.path,
  }
}

export function flowNodeCopyTarget(node: GameFlowNode, resolved?: ResolvedRef): FlowCopyTarget {
  const reference = flowReferenceCopyTarget(node.ref, resolved)
  return {
    ...reference,
    name: node.label?.trim() || reference.name,
    nodeId: node.id,
    values: [{ label: '補足をコピー', value: node.note }],
  }
}

interface MenuPosition {
  x: number
  y: number
  title: string
  commands: ContextCommand[]
  anchor?: HTMLElement
  container?: HTMLElement
  ownerDocument?: Document
}

export function FlowContextMenu({ x, y, title, commands, anchor, container, ownerDocument, onDismiss }: MenuPosition & { onDismiss: () => void }) {
  const panelDocument = usePanelDocument()
  const document = ownerDocument ?? panelDocument
  const window = document.defaultView
  const visible = usePanelVisibility()
  const menu = useRef<HTMLDivElement>(null)
  const previousFocus = useRef<HTMLElement | null>(null)
  const dismissWithFocus = useCallback(() => {
    onDismiss()
    const previous = anchor ?? previousFocus.current
    if (previous?.isConnected) previous.focus({ preventScroll: true })
  }, [anchor, onDismiss])

  useLayoutEffect(() => {
    const element = menu.current
    if (!window || !element || !visible) return
    const focused = document.activeElement as HTMLElement | null
    if (focused && !element.contains(focused)) previousFocus.current = focused
    const rect = element.getBoundingClientRect()
    element.style.left = `${Math.max(8, Math.min(x, window.innerWidth - rect.width - 8))}px`
    element.style.top = `${Math.max(8, Math.min(y, window.innerHeight - rect.height - 8))}px`
    const focusTarget = element.querySelector<HTMLButtonElement>('button:not(:disabled)') ?? element
    focusTarget.focus({ preventScroll: true })
  }, [anchor, container, document, title, visible, window, x, y])

  useEffect(() => {
    if (!window || !visible) return
    const outside = (event: Event) => { if (!menu.current?.contains(event.target as Node)) onDismiss() }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('contextmenu', outside, true)
    document.addEventListener('wheel', outside, true)
    window.addEventListener('resize', onDismiss)
    return () => {
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('contextmenu', outside, true)
      document.removeEventListener('wheel', outside, true)
      window.removeEventListener('resize', onDismiss)
    }
  }, [document, onDismiss, visible, window])

  useEffect(() => {
    if (!window || !visible) return
    // モーダルや職業設定へ Escape / Tab が伝わる前に、開いているメニューを閉じる。
    const onKeyDown = (event: KeyboardEvent) => {
      if (!menu.current?.contains(event.target as Node) || (event.key !== 'Escape' && event.key !== 'Tab')) return
      event.preventDefault()
      event.stopPropagation()
      dismissWithFocus()
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [dismissWithFocus, visible, window])

  if (!visible || !window || !commands.length) return null
  return createPortal(<div ref={menu} className="master-tab-context-menu game-flow-context-menu" role="menu" aria-label={title} tabIndex={-1}
    style={{ left: x, top: y }} onContextMenu={event => { event.preventDefault(); event.stopPropagation() }} onKeyDown={event => {
      const buttons = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
      if (!buttons.length) return
      const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
      const next = event.key === 'ArrowDown' ? (index + 1) % buttons.length
        : event.key === 'ArrowUp' ? (index < 0 ? buttons.length - 1 : (index - 1 + buttons.length) % buttons.length)
          : event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : -1
      if (next >= 0) { event.preventDefault(); event.stopPropagation(); buttons[next].focus() }
    }}>
    <div className="game-flow-context-title">{title}</div>
    {commands.map((command, index) => command
      ? <button type="button" role="menuitem" key={index} disabled={command.disabled} onClick={() => { dismissWithFocus(); command.run() }}>{command.label}</button>
      : <hr key={index} />)}
  </div>, container ?? document.body)
}

function isNativeContext(event: MouseEvent<HTMLElement>, composing: EventTarget | null) {
  if (event.shiftKey || composing) return true
  const element = event.target as HTMLElement
  const field = element.closest<HTMLInputElement | HTMLTextAreaElement>('textarea, input')
  if (field && field.selectionStart !== field.selectionEnd) return true
  if (element.isContentEditable) return true
  const selection = element.ownerDocument.getSelection()
  if (!selection || selection.isCollapsed) return false
  for (let index = 0; index < selection.rangeCount; index++) {
    if (selection.getRangeAt(index).intersectsNode(event.currentTarget)) return true
  }
  return false
}

export function useGameFlowCopyMenu(copy: (value: string) => void) {
  const document = usePanelDocument()
  const [menu, setMenu] = useState<MenuPosition | null>(null)
  const closeCopyMenu = useCallback(() => setMenu(null), [])
  const composing = useRef<EventTarget | null>(null)
  useEffect(() => {
    const start = (event: Event) => { composing.current = event.target }
    const end = (event: Event) => { if (event.target === composing.current) composing.current = null }
    document.addEventListener('compositionstart', start, true)
    document.addEventListener('compositionend', end, true)
    document.addEventListener('blur', end, true)
    return () => {
      document.removeEventListener('compositionstart', start, true)
      document.removeEventListener('compositionend', end, true)
      document.removeEventListener('blur', end, true)
      composing.current = null
    }
  }, [document])
  const onCopyContextMenu: FlowCopyContextMenu = (target, title) => event => {
    if (event.defaultPrevented) return
    if (isNativeContext(event, composing.current)) {
      event.stopPropagation()
      return
    }
    const commands = flowCopyCommands(target, copy)
    if (!commands.length) return
    event.preventDefault()
    event.stopPropagation()
    const clicked = event.target as HTMLElement
    const anchor = clicked.closest<HTMLElement>('button, input, textarea, a[href], [tabindex]') ?? undefined
    const bounds = event.clientX === 0 && event.clientY === 0 ? (anchor ?? event.currentTarget).getBoundingClientRect() : undefined
    setMenu({
      x: bounds?.left ?? event.clientX, y: bounds?.bottom ?? event.clientY, title, commands,
      anchor: anchor?.matches(':disabled') ? undefined : anchor,
      container: event.currentTarget.closest<HTMLElement>('[role="dialog"]') ?? undefined,
      ownerDocument: clicked.ownerDocument,
    })
  }
  return { onCopyContextMenu, closeCopyMenu, copyMenu: menu && <FlowContextMenu {...menu} onDismiss={closeCopyMenu} /> }
}
