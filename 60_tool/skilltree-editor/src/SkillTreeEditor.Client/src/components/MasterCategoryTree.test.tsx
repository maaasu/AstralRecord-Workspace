import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MasterCategoryTree } from './MasterCategoryTree'
import type { MasterCategory } from '../types/masterData'

const category = (id: string, directory: string, label: string): MasterCategory => ({ id, directory, label, fileCount: 0, documents: [], fields: [], jsonSchemas: [], templates: [] })
const categories = [category('item-id', '10.item', 'アイテム'), category('weapon-id', '10.item/20.weapon', 'アイテム / 武器'), category('pool-id', '80.loot/pool', 'ルート / プール'), category('table-id', '80.loot/table', 'ルート / テーブル')]

function Harness({ onSelect = vi.fn(), entries = categories, initial = 'weapon-id' }: { onSelect?: (id: string) => void; entries?: MasterCategory[]; initial?: string }) {
  const [expanded, setExpanded] = useState(new Set<string>())
  const [selected, setSelected] = useState(initial)
  return <MasterCategoryTree categories={entries} files={[]} selectedId={selected} onSelect={(id) => { setSelected(id); onSelect(id) }} expanded={expanded} onExpandedChange={setExpanded} />
}

const node = (name: string) => screen.getByRole('treeitem', { name })

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
})
