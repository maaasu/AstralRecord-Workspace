import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type CSSProperties } from 'react'
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
}

export function MasterCategoryTree({ categories, files, selectedId, onSelect, expanded, onExpandedChange }: MasterCategoryTreeProps) {
  const roots = useMemo(() => buildMasterCategoryTree(categories, files), [categories, files])
  const all = useMemo(() => visibleMasterCategoryNodes(roots, new Set(categories.flatMap((category) => category.directory.split('/').map((_, i, parts) => parts.slice(0, i + 1).join('/'))))), [roots, categories])
  const selected = all.find((node) => node.category?.id === selectedId)
  const visible = visibleMasterCategoryNodes(roots, expanded)
  const [focused, setFocused] = useState<string | undefined>()
  const elements = useRef(new Map<string, HTMLLIElement>())
  const typeahead = useRef({ text: '', time: 0 })
  const revealKey = `${selectedId}\n${selected?.directory ?? ''}\n${all.map((node) => node.directory).join('\n')}`
  const lastRevealed = useRef('')

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
  const onKeyDown = (event: KeyboardEvent, node: MasterCategoryNode) => {
    event.stopPropagation()
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
    const selected = node.category?.id === selectedId
    return <li key={node.directory} role="treeitem" aria-label={`${node.number ? node.number + ' ' : ''}${node.label} (${node.count})`}
      aria-selected={node.category ? selected : undefined} aria-expanded={node.children.length ? open : undefined}
      tabIndex={tabStop === node.directory ? 0 : -1} ref={(element) => { if (element) elements.current.set(node.directory, element); else elements.current.delete(node.directory) }}
      onFocus={(event) => { if (event.target === event.currentTarget) setFocused(node.directory) }} onKeyDown={(event) => onKeyDown(event, node)}>
      <div className={`master-category-row${selected ? ' selected' : ''}`} style={{ '--category-depth': depth } as CSSProperties}
        title={`${node.label}\n${node.directory}\n${node.count}件${node.category ? '' : '（展開用フォルダ）'}`} onClick={() => activate(node)}>
        <span className="master-category-guides" aria-hidden="true">{Array.from({ length: depth }, (_, index) => <i key={index} />)}</span>
        <span className={`master-category-chevron${node.children.length ? ' expandable' : ''}`} aria-hidden="true"
          onClick={(event) => { event.stopPropagation(); toggle(node) }}>{node.children.length ? open ? '⌄' : '›' : ''}</span>
        <svg className="master-category-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d={open ? 'M2 6h6l2 2h8l-3 9H2V6Zm0 3h16' : 'M2 5h6l2 2h8v10H2V5Z'} stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" /></svg>
        {node.number && <span className="master-category-number" aria-hidden="true">{node.number}</span>}
        <span className="master-category-name">{node.label}</span><span className="master-category-count" aria-hidden="true">{node.count}</span>
      </div>
      {node.children.length > 0 && open && <ul role="group">{renderNodes(node.children, depth + 1)}</ul>}
    </li>
  })

  return <div className="master-category-browser">
    <div className="master-category-toolbar"><span>カテゴリ</span><button type="button" onClick={() => { onExpandedChange(new Set()); setFocused(roots[0]?.directory) }} disabled={!expanded.size}>すべて折りたたむ</button></div>
    {roots.length ? <ul role="tree" aria-label="マスターカテゴリ" className="master-category-tree">{renderNodes(roots, 0)}</ul> : <p className="master-category-empty">カテゴリがありません</p>}
  </div>
}
