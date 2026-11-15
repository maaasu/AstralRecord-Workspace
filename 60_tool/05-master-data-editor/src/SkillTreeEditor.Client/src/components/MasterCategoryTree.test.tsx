import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterCategoryTree } from './MasterCategoryTree'
import type { MasterCategory } from '../types/masterData'

const category = (id: string, directory: string, label: string): MasterCategory => ({ id, directory, label, fileCount: 0, documents: [], fields: [], jsonSchemas: [], templates: [] })
const categories = [category('item-id', '10.item', 'アイテム'), category('weapon-id', '10.item/20.weapon', 'アイテム / 武器'), category('pool-id', '80.loot/pool', 'ルート / プール'), category('table-id', '80.loot/table', 'ルート / テーブル')]

function Harness({ onSelect = vi.fn(), onBrowseFolder, entries = categories, initial = 'weapon-id' }: { onSelect?: (id: string) => void; onBrowseFolder?: (directory: string, label: string, categoryId?: string) => void; entries?: MasterCategory[]; initial?: string }) {
  const [expanded, setExpanded] = useState(new Set<string>())
  const [selected, setSelected] = useState(initial)
  return <MasterCategoryTree categories={entries} files={[]} selectedId={selected} onSelect={(id) => { setSelected(id); onSelect(id) }} onBrowseFolder={onBrowseFolder} expanded={expanded} onExpandedChange={setExpanded} />
}

const node = (name: string) => screen.getByRole('treeitem', { name })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('master category tree interaction', () => {
  it('reveals selection, keeps IDs and selection when a parent collapses, and separates chevron clicks', () => {
    const onSelect = vi.fn()
    render(<Harness onSelect={onSelect} />)
    expect(node('20 武器 (0)')).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(node('10 アイテム (0)').querySelector('.master-category-chevron')!)
    expect(screen.queryByRole('treeitem', { name: '20 武器 (0)' })).not.toBeInTheDocument()
    expect(onSelect).not.toHaveBeenCalled()
    fireEvent.keyDown(node('10 アイテム (0)'), { key: 'ArrowRight' })
    expect(node('20 武器 (0)')).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(node('10 アイテム (0)').querySelector('.master-category-name')!)
    expect(onSelect).toHaveBeenLastCalledWith('item-id')
  })

  it('implements roving keyboard navigation and explicit selection', () => {
    const onSelect = vi.fn()
    render(<Harness onSelect={onSelect} />)
    const weapon = node('20 武器 (0)')
    weapon.focus()
    fireEvent.keyDown(weapon, { key: 'ArrowLeft' })
    expect(node('10 アイテム (0)')).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowRight' })
    expect(weapon).toHaveFocus()
    fireEvent.keyDown(weapon, { key: 'Home' })
    expect(node('10 アイテム (0)')).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'End' })
    expect(node('80 ルート (0)')).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'Enter' })
    expect(node('80 ルート (0)')).toHaveAttribute('aria-expanded', 'true')
    expect(onSelect).not.toHaveBeenCalled()
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowRight' })
    expect(node('プール (0)')).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowDown' })
    expect(node('テーブル (0)')).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowUp' })
    expect(node('プール (0)')).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: ' ' })
    expect(onSelect).toHaveBeenLastCalledWith('pool-id')
    expect(screen.getAllByRole('treeitem').filter((item) => item.tabIndex === 0)).toHaveLength(1)
  })

  it('supports typeahead, collapse all, and safe empty or removed selections', () => {
    const { rerender } = render(<Harness />)
    fireEvent.keyDown(node('20 武器 (0)'), { key: 'ル' })
    expect(node('80 ルート (0)')).toHaveFocus()
    expect(node('80 ルート (0)')).not.toHaveAttribute('aria-selected')
    fireEvent.click(screen.getByRole('button', { name: 'すべて折りたたむ' }))
    expect(screen.getAllByRole('treeitem')).toHaveLength(2)
    expect(screen.getAllByRole('treeitem').filter((item) => item.tabIndex === 0)).toHaveLength(1)
    rerender(<Harness entries={[categories[2]]} />)
    expect(node('80 ルート (0)')).toHaveAttribute('tabindex', '0')
    rerender(<Harness entries={[]} />)
    expect(screen.getByText('カテゴリがありません')).toBeInTheDocument()
  })

  it('reveals a changed external selection while preserving a user collapse across file refreshes', () => {
    function Controlled({ id, revision }: { id: string; revision: string }) {
      const [expanded, setExpanded] = useState(new Set<string>())
      return <MasterCategoryTree categories={categories} files={[{ path: '80.loot/pool/a.yml', category: 'pool-id', format: 'yaml', size: 0, revision, modifiedUtc: '' }]} selectedId={id} onSelect={vi.fn()} expanded={expanded} onExpandedChange={setExpanded} />
    }
    const { rerender } = render(<Controlled id="weapon-id" revision="a" />)
    expect(node('20 武器 (0)')).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(node('10 アイテム (0)').querySelector('.master-category-chevron')!)
    rerender(<Controlled id="weapon-id" revision="b" />)
    expect(node('10 アイテム (0)')).toHaveAttribute('aria-expanded', 'false')
    rerender(<Controlled id="pool-id" revision="b" />)
    expect(node('プール (1)')).toHaveAttribute('aria-selected', 'true')
    expect(node('80 ルート (1)')).toHaveAttribute('aria-expanded', 'true')
  })

  it('uses aligned SVG chevrons and preserves tree arrow navigation', () => {
    render(<Harness />)
    const parent = node('10 アイテム (0)')
    expect(parent.querySelector('.master-category-chevron svg path')).toHaveAttribute('d', 'M3.5 6 8 10.5 12.5 6')
    fireEvent.click(parent.querySelector('.master-category-chevron')!)
    expect(parent.querySelector('.master-category-chevron svg path')).toHaveAttribute('d', 'M6 3.5 10.5 8 6 12.5')
    parent.focus()
    fireEvent.keyDown(parent, { key: 'ArrowRight' })
    expect(parent).toHaveAttribute('aria-expanded', 'true')
    fireEvent.keyDown(parent, { key: 'ArrowRight' })
    expect(node('20 武器 (0)')).toHaveFocus()
  })

  it('opens on the right-clicked virtual folder and copies its repository-relative path and name', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const onBrowseFolder = vi.fn()
    const onSelect = vi.fn()
    render(<Harness onBrowseFolder={onBrowseFolder} onSelect={onSelect} />)
    fireEvent.contextMenu(node('80 ルート (0)').querySelector('.master-category-row')!, { clientX: 20, clientY: 40 })
    const menu = screen.getByRole('menu', { name: 'フォルダの操作' })
    expect(menu).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'フォルダ内を表示' })).toHaveFocus()
    fireEvent.click(screen.getByRole('menuitem', { name: 'フォルダ内を表示' }))
    expect(onBrowseFolder).toHaveBeenCalledWith('80.loot', 'ルート', undefined)
    expect(onSelect).not.toHaveBeenCalled()
    fireEvent.click(node('80 ルート (0)').querySelector('.master-category-chevron')!)
    fireEvent.contextMenu(node('プール (0)').querySelector('.master-category-row')!)
    fireEvent.click(screen.getByRole('menuitem', { name: '相対パスをコピー' }))
    expect(writeText).toHaveBeenCalledWith('40_filebase/80.loot/pool')
    expect(await screen.findByRole('status')).toHaveTextContent('40_filebase/80.loot/pool')
    fireEvent.contextMenu(node('80 ルート (0)').querySelector('.master-category-row')!)
    fireEvent.click(screen.getByRole('menuitem', { name: 'フォルダ名をコピー' }))
    expect(writeText).toHaveBeenLastCalledWith('80.loot')
  })

  it('supports keyboard menu navigation, focus restoration, and subtree commands', () => {
    render(<Harness onBrowseFolder={vi.fn()} />)
    const parent = node('80 ルート (0)')
    parent.focus()
    fireEvent.keyDown(parent, { key: 'F10', shiftKey: true })
    expect(screen.getByRole('menuitem', { name: 'フォルダ内を表示' })).toHaveFocus()
    expect(screen.getByRole('menuitem', { name: 'サブフォルダをすべて折りたたむ' })).toBeDisabled()
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'End' })
    expect(screen.getByRole('menuitem', { name: 'サブフォルダをすべて展開' })).toHaveFocus()
    fireEvent.click(document.activeElement!)
    expect(parent).toHaveAttribute('aria-expanded', 'true')
    expect(node('プール (0)')).toBeInTheDocument()
    fireEvent.keyDown(parent, { key: 'ContextMenu' })
    fireEvent.click(screen.getByRole('menuitem', { name: 'サブフォルダをすべて折りたたむ' }))
    expect(parent).toHaveAttribute('aria-expanded', 'false')
    fireEvent.keyDown(parent, { key: 'ContextMenu' })
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' })
    expect(parent).toHaveFocus()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('reports clipboard failure with selectable fallback and closes on outside click', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    render(<Harness entries={[category('root', '.', 'Filebase 設定')]} initial="root" />)
    const root = node('Filebase 設定 (0)')
    fireEvent.contextMenu(root.querySelector('.master-category-row')!)
    fireEvent.click(screen.getByRole('menuitem', { name: '相対パスをコピー' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('コピーできませんでした')
    const fallback = screen.getByRole('textbox', { name: '手動コピー用のフォルダ' }) as HTMLInputElement
    expect(fallback).toHaveValue('40_filebase')
    fallback.focus()
    expect(fallback.selectionStart).toBe(0)
    expect(fallback.selectionEnd).toBe(fallback.value.length)
    fireEvent.contextMenu(root.querySelector('.master-category-row')!)
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('keeps the menu inside the viewport when opened near an edge', () => {
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 225, height: 180 } as DOMRect)
    render(<Harness />)
    fireEvent.contextMenu(node('80 ルート (0)').querySelector('.master-category-row')!, { clientX: window.innerWidth - 1, clientY: window.innerHeight - 1 })
    expect(screen.getByRole('menu')).toHaveStyle({ left: `${window.innerWidth - 233}px`, top: `${window.innerHeight - 188}px` })
  })
})
