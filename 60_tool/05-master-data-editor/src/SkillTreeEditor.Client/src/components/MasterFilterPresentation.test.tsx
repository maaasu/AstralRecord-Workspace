import { useState } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MasterFileBrowser } from './MasterFileBrowser'
import type { MasterDataWorkspace } from '../state/masterDataWorkspace'
import type { MasterFileSummary, MasterFilterIndex } from '../types/masterData'

const sourcePath = '40.features.mob/eriva.yml'
const item: MasterFileSummary = { path: '10.features.item/sword.yml', category: 'item', format: 'yaml', name: '§#12aa33 勇者の剣 ', id: 'sword', revision: 'a', size: 1, modifiedUtc: '' }
const mob: MasterFileSummary = { ...item, path: sourcePath, category: 'mob', name: '§fモブ', id: 'eriva' }
const index: MasterFilterIndex = {
  sources: [{ path: sourcePath, kind: 'mob', name: '&f グラスボア ', id: 'eriva', itemIds: ['sword'] }],
  tags: [{ id: 'magic', name: '§l 魔法 ' }], skillTags: [],
  entries: [{ path: item.path, facets: { tags: ['magic'] } }],
}

function Harness({ selectedIndex = index, entries = [item, mob] }: { selectedIndex?: MasterFilterIndex; entries?: MasterFileSummary[] }) {
  const [dropSourcePath, setDropSourcePath] = useState(sourcePath)
  const [skillTag, setSkillTag] = useState('magic')
  const [dropItemId, setDropItemId] = useState('sword')
  const [facetFilters, setFacetFilters] = useState<Record<string, string>>({ tags: 'magic' })
  const [sourceQuery, setSourceQuery] = useState('グラス')
  const [dropItemQuery, setDropItemQuery] = useState('勇者')
  const workspace = {
    catalog: { root: '', backups: '', categories: [{ id: 'item', label: 'アイテム', directory: '10.features.item', fileCount: entries.length, documents: [], fields: [], jsonSchemas: [], templates: [] }] },
    files: entries, filterIndex: selectedIndex, candidates: [], categoryId: 'item', setCategoryId: vi.fn(),
    query: '', setQuery: vi.fn(), searchScope: 'all', setSearchScope: vi.fn(), visibleCount: 50, setVisibleCount: vi.fn(),
    browserView: 'files', setBrowserView: vi.fn(), expandedCategories: new Set<string>(), setExpandedCategories: vi.fn(),
    sidebarCollapsed: false, setSidebarCollapsed: vi.fn(), refreshing: false, refresh: vi.fn(async () => {}), setError: vi.fn(),
    format: '', setFormat: vi.fn(), sourceQuery, setSourceQuery, dropSourcePath, setDropSourcePath,
    skillTag, setSkillTag, dropItemQuery, setDropItemQuery, dropItemId, setDropItemId,
    dropPresence: '', setDropPresence: vi.fn(), facetFilters, setFacetFilters, fileStatus: '', setFileStatus: vi.fn(),
    numericFilters: {}, setNumericFilters: vi.fn(),
  } as unknown as MasterDataWorkspace
  return <MasterFileBrowser workspace={workspace} busy={false} active onOpen={vi.fn()} onNew={vi.fn()} onImport={vi.fn()} onDialogChange={vi.fn()} />
}

describe('applied filter display names', () => {
  it('uses readable text in chips, titles, and accessible names, and clears only the chosen condition', () => {
    render(<Harness />)
    const chips = within(screen.getByLabelText('適用中のフィルター'))
    const source = chips.getByRole('button', { name: 'ドロップ元: グラスボア を解除' })
    expect(source).toHaveAttribute('title', 'ドロップ元: グラスボア')
    expect(chips.getByRole('button', { name: 'スキルタグ: 魔法 を解除' })).toHaveAttribute('title', 'スキルタグ: 魔法')
    expect(chips.getByRole('button', { name: 'ドロップ品: 勇者の剣 を解除' })).toHaveAttribute('title', 'ドロップ品: 勇者の剣')
    expect(chips.getByRole('button', { name: 'タグ: 魔法 (magic) を解除' })).toHaveAttribute('title', 'タグ: 魔法 (magic)')
    for (const chip of chips.getAllByRole('button')) {
      expect(chip.textContent).not.toMatch(/[§]|&[0-9a-fklmnor]/i)
      expect(chip.getAttribute('title')).not.toMatch(/[§]|&[0-9a-fklmnor]/i)
      expect(chip.getAttribute('aria-label')).not.toMatch(/[§]|&[0-9a-fklmnor]/i)
    }
    fireEvent.click(source)
    expect(chips.queryByRole('button', { name: 'ドロップ元: グラスボア を解除' })).not.toBeInTheDocument()
    expect(chips.getByRole('button', { name: 'スキルタグ: 魔法 を解除' })).toBeInTheDocument()
    expect(chips.getByRole('button', { name: 'ドロップ品: 勇者の剣 を解除' })).toBeInTheDocument()
    expect(chips.getByRole('button', { name: 'タグ: 魔法 (magic) を解除' })).toBeInTheDocument()
  })

  it('uses verbatim path and ID fallbacks for missing or decoration-only names', () => {
    render(<Harness selectedIndex={{ ...index, sources: [{ ...index.sources[0], name: '§l' }], tags: [{ id: 'magic', name: '&f' }] }}
      entries={[{ ...item, name: '§#12aa33' }, mob]} />)
    const chips = within(screen.getByLabelText('適用中のフィルター'))
    expect(chips.getByRole('button', { name: `ドロップ元: ${sourcePath} を解除` })).toHaveAttribute('title', `ドロップ元: ${sourcePath}`)
    expect(chips.getByRole('button', { name: 'スキルタグ: magic を解除' })).toBeInTheDocument()
    expect(chips.getByRole('button', { name: 'ドロップ品: sword を解除' })).toBeInTheDocument()
    expect(chips.getByRole('button', { name: 'タグ: magic を解除' })).toBeInTheDocument()
  })
})
