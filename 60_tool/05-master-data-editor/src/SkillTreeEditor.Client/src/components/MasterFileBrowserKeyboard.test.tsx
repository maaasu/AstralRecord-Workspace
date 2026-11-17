import { useState } from 'react'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { MasterFileBrowser } from './MasterFileBrowser'
import type { MasterDataWorkspace } from '../state/masterDataWorkspace'
import type { MasterCategory, MasterFileSummary } from '../types/masterData'

const files: MasterFileSummary[] = Array.from({ length: 55 }, (_, index) => ({
  path: `10.features.item/file-${String(index).padStart(2, '0')}.yml`,
  category: 'item', format: 'yaml', name: `ファイル ${index}`, id: `item-${index}`,
  revision: 'a', size: 1, modifiedUtc: '',
}))

function Harness({ entries = files, categoryEntries, busy = false, initialFormat = '', onOpen = vi.fn() }: { entries?: MasterFileSummary[]; categoryEntries?: MasterCategory[]; busy?: boolean; initialFormat?: string; onOpen?: (path: string) => void }) {
  const [query, setQuery] = useState('')
  const [searchScope, setSearchScope] = useState<'folder' | 'all'>('folder')
  const [visibleCount, setVisibleCount] = useState(50)
  const [format, setFormat] = useState(initialFormat)
  const [expandedCategories, setExpandedCategories] = useState(new Set<string>())
  const workspace = {
    catalog: { root: '', backups: '', categories: categoryEntries ?? [{ id: 'item', label: 'アイテム', directory: '10.features.item', fileCount: entries.length, documents: [], fields: [], jsonSchemas: [], templates: [] }] },
    files: entries, filterIndex: null, candidates: [], categoryId: 'item', setCategoryId: vi.fn(),
    query, setQuery, searchScope, setSearchScope, visibleCount, setVisibleCount,
    browserView: 'files', setBrowserView: vi.fn(), expandedCategories, setExpandedCategories,
    sidebarCollapsed: false, setSidebarCollapsed: vi.fn(), refreshing: false, refresh: vi.fn(async () => {}), setError: vi.fn(),
    format, setFormat, sourceQuery: '', setSourceQuery: vi.fn(), dropSourcePath: '', setDropSourcePath: vi.fn(),
    skillTag: '', setSkillTag: vi.fn(), dropItemQuery: '', setDropItemQuery: vi.fn(), dropItemId: '', setDropItemId: vi.fn(),
    dropPresence: '', setDropPresence: vi.fn(), facetFilters: {}, setFacetFilters: vi.fn(), fileStatus: '', setFileStatus: vi.fn(),
    numericFilters: {}, setNumericFilters: vi.fn(),
  } as unknown as MasterDataWorkspace
  return <MasterFileBrowser workspace={workspace} busy={busy} active selectedPath={undefined} onOpen={onOpen} onNew={vi.fn()} onImport={vi.fn()} onDialogChange={vi.fn()} />
}

const results = () => within(screen.getByLabelText('マスターの検索結果')).getAllByRole('button').filter((button) => button.classList.contains('master-file'))

describe('master file search keyboard navigation', () => {
  it('moves from search into results, navigates, opens, and returns to search', async () => {
    const onOpen = vi.fn()
    const user = userEvent.setup()
    render(<Harness onOpen={onOpen} />)
    const search = screen.getByRole('textbox', { name: 'マスター検索' })
    search.focus()
    fireEvent.keyDown(search, { key: 'ArrowDown' })
    expect(results()[0]).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowDown' })
    expect(results()[1]).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'End' })
    await waitFor(() => expect(results()[54]).toHaveFocus())
    fireEvent.keyDown(document.activeElement!, { key: 'Home' })
    expect(results()[0]).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'ArrowUp' })
    expect(results()[0]).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(onOpen).toHaveBeenCalledWith(files[0].path)
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
    expect(search).toHaveFocus()
    fireEvent.keyDown(search, { key: 'Enter' })
    expect(results()[0]).toHaveFocus()
  })

  it('loads and focuses the next result after the initial 50', async () => {
    render(<Harness />)
    const buttons = results()
    expect(buttons).toHaveLength(50)
    buttons[49].focus()
    fireEvent.keyDown(buttons[49], { key: 'ArrowDown' })
    await waitFor(() => expect(results()[50]).toHaveFocus())
  })

  it('preserves the query and filters when expanding an empty folder search, and focuses search after clearing', async () => {
    const user = userEvent.setup()
    render(<Harness entries={[{ ...files[0], path: 'other/file-00.yml' }]} initialFormat="yaml" />)
    const search = screen.getByRole('textbox', { name: 'マスター検索' })
    await user.type(search, 'file-00')
    await user.click(screen.getByRole('button', { name: '同じ検索条件でFilebase全体を検索' }))
    expect(search).toHaveValue('file-00')
    expect(screen.getByRole('button', { name: '形式: YAML を解除' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: '検索範囲' })).toHaveValue('all')
    expect(results()).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'ファイル検索をクリア' }))
    expect(search).toHaveFocus()
    expect(search).toHaveValue('')
  })

  it('ignores IME confirmation and can open a different file while a tab is busy', async () => {
    const onOpen = vi.fn()
    const user = userEvent.setup()
    render(<Harness busy onOpen={onOpen} />)
    const search = screen.getByRole('textbox', { name: 'マスター検索' })
    search.focus()
    fireEvent.keyDown(search, { key: 'Enter', isComposing: true })
    expect(search).toHaveFocus()
    fireEvent.keyDown(search, { key: 'ArrowDown' })
    expect(results()[0]).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(onOpen).toHaveBeenCalledWith(files[0].path)
    await user.click(results()[1])
    expect(onOpen).toHaveBeenCalledWith(files[1].path)
    expect(screen.getByRole('button', { name: '＋ 新規' })).toBeDisabled()
  })

  it('lists files from a virtual parent folder opened through its context menu', () => {
    const categories: MasterCategory[] = ['pool', 'table'].map((part) => ({ id: part, label: `ルート / ${part}`, directory: `80.loot/${part}`, fileCount: 1, documents: [], fields: [], jsonSchemas: [], templates: [] }))
    const entries = ['pool', 'table'].map((part) => ({ ...files[0], path: `80.loot/${part}/one.yml`, category: part }))
    render(<Harness entries={entries} categoryEntries={categories} />)
    fireEvent.contextMenu(screen.getByRole('treeitem', { name: '80 ルート (2)' }).querySelector('.master-category-row')!)
    fireEvent.click(screen.getByRole('menuitem', { name: 'フォルダ内を表示' }))
    expect(screen.getByRole('heading', { name: 'ルート' })).toBeInTheDocument()
    expect(screen.getByRole('treeitem', { name: '80 ルート (2)' })).toHaveAttribute('aria-selected', 'true')
    expect(results()).toHaveLength(2)
  })
})
