import { usePanelDocument, usePanelVisibility } from '../docking/dockPanel'
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { registerAndroidDialog } from '../androidEditor'
import type { MasterCategory, MasterFileSummary } from '../types/masterData'
import { buildMasterCategoryTree, visibleMasterCategoryNodes, type MasterCategoryNode } from '../data/masterCategoryTree'
import './MasterCategoryTree.css'

interface MasterCategoryTreeProps {
  categories: MasterCategory[]
  files: MasterFileSummary[]
  selectedId: string
  onSelect: (id: string) => void
  expanded: Set<string>
  onExpandedChange: (expanded: Set<string>) => void
  selectedDirectory?: string
  onBrowseFolder?: (directory: string, label: string, categoryId?: string) => void
}

interface FolderMenu { directory: string; x: number; y: number }
interface CopyFeedback { state: 'success' | 'failure'; value: string }

function FolderContextMenu({ x, y, commands, onDismiss }: { x: number; y: number; commands: { label: string; disabled?: boolean; run: () => void }[]; onDismiss: (restoreFocus?: boolean) => void }) {
  const document = usePanelDocument()
  const visibleInLayout = usePanelVisibility()
  const window = document.defaultView!
  const menu = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const element = menu.current
    if (!element) return
    const rect = element.getBoundingClientRect()
    element.style.left = `${Math.max(8, Math.min(x, window.innerWidth - rect.width - 8))}px`
    element.style.top = `${Math.max(8, Math.min(y, window.innerHeight - rect.height - 8))}px`
    element.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
  }, [x, y])
  useEffect(() => {
    if (!visibleInLayout) return
    const unregisterBack = menu.current ? registerAndroidDialog(menu.current, () => onDismiss(true)) : undefined
    const outside = (event: Event) => { if (!menu.current?.contains(event.target as Node)) onDismiss() }
    const dismiss = () => onDismiss()
    document.addEventListener('pointerdown', outside)
    document.addEventListener('contextmenu', outside, true)
    window.addEventListener('resize', dismiss)
    document.addEventListener('scroll', dismiss, true)
    return () => {
      unregisterBack?.()
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('contextmenu', outside, true)
      window.removeEventListener('resize', dismiss)
      document.removeEventListener('scroll', dismiss, true)
    }
  }, [onDismiss, document, window, visibleInLayout])
  return createPortal(<div hidden={!visibleInLayout} ref={menu} className="master-folder-context-menu" role="menu" tabIndex={-1} aria-label="フォルダの操作" style={{ left: x, top: y }} onKeyDown={(event) => {
    if (event.key === 'Escape' || event.key === 'Tab') { event.preventDefault(); onDismiss(true); return }
    const buttons = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
    const next = event.key === 'ArrowDown' ? (index + 1) % buttons.length : event.key === 'ArrowUp' ? (index - 1 + buttons.length) % buttons.length : event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : -1
    if (next >= 0) { event.preventDefault(); buttons[next]?.focus() }
  }}>{commands.map((command) => <button type="button" role="menuitem" key={command.label} disabled={command.disabled} onClick={() => { onDismiss(true); command.run() }}>{command.label}</button>)}</div>, document.body)
}

export function MasterCategoryTree({ categories, files, selectedId, onSelect, expanded, onExpandedChange, selectedDirectory, onBrowseFolder }: MasterCategoryTreeProps) {
  const roots = useMemo(() => buildMasterCategoryTree(categories, files), [categories, files])
  const all = useMemo(() => visibleMasterCategoryNodes(roots, new Set(categories.flatMap((category) => category.directory.split('/').map((_, i, parts) => parts.slice(0, i + 1).join('/'))))), [roots, categories])
  const selected = all.find((node) => node.directory === selectedDirectory) ?? all.find((node) => node.category?.id === selectedId)
  const visible = visibleMasterCategoryNodes(roots, expanded)
  const [focused, setFocused] = useState<string | undefined>()
  const [menu, setMenu] = useState<FolderMenu | null>(null)
  const [copyFeedback, setCopyFeedback] = useState<CopyFeedback | null>(null)
  const elements = useRef(new Map<string, HTMLLIElement>())
  const typeahead = useRef({ text: '', time: 0 })
  const revealKey = `${selectedId}\n${selected?.directory ?? ''}\n${all.map((node) => node.directory).join('\n')}`
  const lastRevealed = useRef('')
  const menuTarget = menu && all.find((node) => node.directory === menu.directory)

  useEffect(() => {
    if (lastRevealed.current === revealKey) return
    lastRevealed.current = revealKey
    if (!selected) return
    const next = new Set(expanded)
    let parent = selected.parent
    while (parent) {
      next.add(parent)
      parent = all.find((node) => node.directory === parent)?.parent
    }
    if (next.size !== expanded.size) onExpandedChange(next)
    setFocused(selected.directory)
  }, [revealKey, selected, all, expanded, onExpandedChange])

  const tabStop = visible.find((node) => node.directory === focused)?.directory
    ?? visible.find((node) => node.category?.id === selectedId)?.directory ?? visible[0]?.directory
  const focus = (node?: MasterCategoryNode) => {
    if (!node) return
    setFocused(node.directory)
    elements.current.get(node.directory)?.focus()
  }
  const toggle = (node: MasterCategoryNode) => {
    if (!node.children.length) return
    const next = new Set(expanded)
    if (next.has(node.directory)) next.delete(node.directory)
    else next.add(node.directory)
    onExpandedChange(next)
    focus(node)
  }
  const activate = (node: MasterCategoryNode) => {
    focus(node)
    if (node.category) onSelect(node.category.id)
    else toggle(node)
  }
  const dismissMenu = (restoreFocus = false) => {
    if (restoreFocus && menuTarget) focus(menuTarget)
    setMenu(null)
  }
  const showMenu = (node: MasterCategoryNode, x: number, y: number) => {
    setCopyFeedback(null)
    setMenu({ directory: node.directory, x, y })
  }
  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value)
      setCopyFeedback({ state: 'success', value })
    } catch {
      setCopyFeedback({ state: 'failure', value })
    }
  }
  const subtree = (node: MasterCategoryNode): string[] => [node.directory, ...node.children.flatMap(subtree)]
  const changeSubtree = (node: MasterCategoryNode, open: boolean) => {
    const next = new Set(expanded)
    for (const directory of subtree(node)) {
      if (open) next.add(directory)
      else next.delete(directory)
    }
    onExpandedChange(next)
  }
  const onKeyDown = (event: KeyboardEvent, node: MasterCategoryNode) => {
    event.stopPropagation()
    if (event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey)) {
      event.preventDefault()
      const rect = elements.current.get(node.directory)?.getBoundingClientRect()
      showMenu(node, rect?.left ?? 0, rect?.bottom ?? 0)
      return
    }
    if (event.altKey || event.ctrlKey || event.metaKey || event.nativeEvent.isComposing) return
    const index = visible.indexOf(node)
    switch (event.key) {
      case 'ArrowDown': focus(visible[Math.min(index + 1, visible.length - 1)]); break
      case 'ArrowUp': focus(visible[Math.max(index - 1, 0)]); break
      case 'Home': focus(visible[0]); break
      case 'End': focus(visible.at(-1)); break
      case 'ArrowRight':
        if (node.children.length && !expanded.has(node.directory)) toggle(node)
        else if (node.children.length) focus(node.children[0])
        break
      case 'ArrowLeft':
        if (node.children.length && expanded.has(node.directory)) toggle(node)
        else focus(visible.find((entry) => entry.directory === node.parent))
        break
      case 'Enter': case ' ': activate(node); break
      default: {
        if (event.key.length !== 1) return
        const now = Date.now()
        const previous = now - typeahead.current.time < 700 ? typeahead.current.text : ''
        const text = (previous + event.key).toLocaleLowerCase('ja')
        typeahead.current = { text, time: now }
        const query = [...text].every((char) => char === text[0]) ? text[0] : text
        const candidates = [...visible.slice(index + 1), ...visible.slice(0, index + 1)]
        focus(candidates.find((entry) => entry.label.toLocaleLowerCase('ja').startsWith(query) || entry.directory.split('/').at(-1)?.toLocaleLowerCase('ja').startsWith(query)))
      }
    }
    event.preventDefault()
  }

  const renderNodes = (nodes: MasterCategoryNode[], depth: number) => nodes.map((node) => {
    const open = expanded.has(node.directory)
    const selected = selectedDirectory ? node.directory === selectedDirectory : node.category?.id === selectedId
    return <li key={node.directory} role="treeitem" aria-label={`${node.number ? node.number + ' ' : ''}${node.label} (${node.count})`}
      aria-selected={node.category || selectedDirectory === node.directory ? selected : undefined} aria-expanded={node.children.length ? open : undefined}
      tabIndex={tabStop === node.directory ? 0 : -1} ref={(element) => { if (element) elements.current.set(node.directory, element); else elements.current.delete(node.directory) }}
      onFocus={(event) => { if (event.target === event.currentTarget) setFocused(node.directory) }} onKeyDown={(event) => onKeyDown(event, node)}>
      <div className={`master-category-row${selected ? ' selected' : ''}`} style={{ '--category-depth': depth } as CSSProperties}
        title={`${node.label}\n${node.directory}\n${node.count}件${node.category ? '' : '（展開用フォルダ）'}`} onClick={() => activate(node)} onContextMenu={(event) => { event.preventDefault(); event.stopPropagation(); showMenu(node, event.clientX, event.clientY) }}>
        <span className="master-category-guides" aria-hidden="true">{Array.from({ length: depth }, (_, index) => <i key={index} />)}</span>
        <span className={`master-category-chevron${node.children.length ? ' expandable' : ''}`} aria-hidden="true"
          onClick={(event) => { event.stopPropagation(); toggle(node) }}>{node.children.length ? <svg viewBox="0 0 16 16" fill="none"><path d={open ? 'M3.5 6 8 10.5 12.5 6' : 'M6 3.5 10.5 8 6 12.5'} stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg> : null}</span>
        <svg className="master-category-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d={open ? 'M2 6h6l2 2h8l-3 9H2V6Zm0 3h16' : 'M2 5h6l2 2h8v10H2V5Z'} stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" /></svg>
        {node.number && <span className="master-category-number" aria-hidden="true">{node.number}</span>}
        <span className="master-category-name">{node.label}</span><span className="master-category-count" aria-hidden="true">{node.count}</span>
      </div>
      {node.children.length > 0 && open && <ul role="group">{renderNodes(node.children, depth + 1)}</ul>}
    </li>
  })

  return <div className="master-category-browser">
    <div className="master-category-toolbar"><span>カテゴリ</span><button type="button" onClick={() => { onExpandedChange(new Set()); setFocused(roots[0]?.directory) }} disabled={!expanded.size}>すべて折りたたむ</button></div>
    {copyFeedback && <div className="master-folder-copy-feedback">{copyFeedback.state === 'success' ? <p role="status">コピーしました: {copyFeedback.value}</p> : <><p role="alert">コピーできませんでした。下の欄から手動でコピーしてください。</p><input aria-label="手動コピー用のフォルダ" readOnly value={copyFeedback.value} onFocus={(event) => event.currentTarget.select()} /></>}</div>}
    {roots.length ? <ul role="tree" aria-label="マスターカテゴリ" className="master-category-tree">{renderNodes(roots, 0)}</ul> : <p className="master-category-empty">カテゴリがありません</p>}
    {menuTarget && <FolderContextMenu x={menu!.x} y={menu!.y} onDismiss={dismissMenu} commands={[
      { label: 'フォルダ内を表示', disabled: !onBrowseFolder && !menuTarget.category, run: () => onBrowseFolder ? onBrowseFolder(menuTarget.directory, menuTarget.label, menuTarget.category?.id) : menuTarget.category && onSelect(menuTarget.category.id) },
      { label: '相対パスをコピー', run: () => void copy(menuTarget.directory === '.' ? '40_filebase' : `40_filebase/${menuTarget.directory}`) },
      { label: 'フォルダ名をコピー', run: () => void copy(menuTarget.directory === '.' ? '40_filebase' : menuTarget.directory.split('/').at(-1)!) },
      { label: 'サブフォルダをすべて展開', disabled: !menuTarget.children.length, run: () => changeSubtree(menuTarget, true) },
      { label: 'サブフォルダをすべて折りたたむ', disabled: !menuTarget.children.length || !subtree(menuTarget).some((directory) => expanded.has(directory)), run: () => changeSubtree(menuTarget, false) },
    ]} />}
  </div>
}
