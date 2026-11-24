import { useState } from 'react'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { MasterFileBrowser } from './MasterFileBrowser'
import { DockingContext, type DockingState } from '../docking/dockPanel'
import type { MasterDataWorkspace } from '../state/masterDataWorkspace'
import type { MasterCategory, MasterFileSummary, MasterReference } from '../types/masterData'

const files: MasterFileSummary[] = Array.from({ length: 55 }, (_, index) => ({
  path: `10.features.item/file-${String(index).padStart(2, '0')}.yml`,
  category: 'item', format: 'yaml', name: `ファイル ${index}`, id: `item-${index}`,
  revision: 'a', size: 1, modifiedUtc: '',
}))

function Harness({ entries = files, categoryEntries, busy = false, initialFormat = '', onOpen = vi.fn(), candidates = [], referencePrefixes }: {
  entries?: MasterFileSummary[]; categoryEntries?: MasterCategory[]; busy?: boolean; initialFormat?: string
  onOpen?: (path: string, preview: boolean) => void; candidates?: MasterReference[]; referencePrefixes?: Record<string, string>
}) {
  const [query, setQuery] = useState('')
  const [searchScope, setSearchScope] = useState<'folder' | 'all'>('folder')
  const [visibleCount, setVisibleCount] = useState(50)
  const [format, setFormat] = useState(initialFormat)
  const [expandedCategories, setExpandedCategories] = useState(new Set<string>())
  const workspace = {
    catalog: { root: '', backups: '', referencePrefixes, categories: categoryEntries ?? [{ id: 'item', label: 'アイテム', directory: '10.features.item', fileCount: entries.length, documents: [], fields: [], jsonSchemas: [], templates: [] }] },
    files: entries, filterIndex: null, candidates, categoryId: 'item', setCategoryId: vi.fn(),
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
    expect(onOpen).toHaveBeenCalledWith(files[0].path, false)
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
    expect(onOpen).toHaveBeenCalledWith(files[0].path, false)
    await user.click(results()[1])
    expect(onOpen).toHaveBeenCalledWith(files[1].path, true)
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

describe('ファイル行のコピー', () => {
  const equipment = { ...files[0], path: '10.features.item/20.equipment/set_effect/v1.example.yml', name: '&a旅人のセット', id: 'shared_id', icon: 'apple' }
  const quest = { ...files[1], path: '51.features.quest/v1.route.yml', name: '旅人の依頼', id: 'shared_id' }
  const loot = { ...files[2], path: '80.shared.loot/table/v1.treasure.yml', name: '旅人の宝箱', id: 'shared_id' }
  const equipmentFolder: MasterCategory = { id: 'item', label: '装備セット', directory: '10.features.item/20.equipment/set_effect',
    fileCount: 2, documents: [], fields: [], jsonSchemas: [], templates: [] }
  const candidates = [equipment, quest, loot].map((file, index) => ({
    path: file.path, pointer: '/id', value: file.id!, kind: ['set_effect', 'quest', 'loot_table'][index],
  }))
  const referencePrefixes = { item: 'item', set: 'set_effect', st: 'set_effect', loot_table: 'loot_table' }
  const menu = () => within(screen.getByRole('menu', { name: 'ファイルのコピー' }))

  it('フォルダのアイコン・名前・ID・ファイル名から、開いていない同じ行の情報をコピーする', async () => {
    const user = userEvent.setup()
    const writeText = vi.spyOn(navigator.clipboard, 'writeText')
    const onOpen = vi.fn()
    render(<Harness entries={[{ ...files[0], path: equipmentFolder.directory + '/first.yml' }, equipment]} categoryEntries={[equipmentFolder]}
      candidates={candidates} referencePrefixes={referencePrefixes} onOpen={onOpen} busy />)
    const row = screen.getByRole('button', { name: /旅人のセット/ })
    const values = [
      { target: row.querySelector('.master-list-icon')!, menuItem: 'ワークスペース相対パスをコピー', value: `40_filebase/${equipment.path}` },
      { target: within(row).getByText('旅人のセット'), menuItem: '表示名をコピー', value: '旅人のセット' },
      { target: within(row).getByText('ID: shared_id'), menuItem: 'マスターIDをコピー', value: 'shared_id' },
      { target: within(row).getByText('v1.example.yml'), menuItem: 'ファイル名をコピー', value: 'v1.example.yml' },
      { target: row, menuItem: '参照をコピー', value: 'set:shared_id' },
    ]
    for (const entry of values) {
      fireEvent.contextMenu(entry.target)
      await user.click(menu().getByRole('menuitem', { name: entry.menuItem }))
      expect(writeText).toHaveBeenLastCalledWith(entry.value)
      expect(row).toHaveFocus()
    }
    expect(onOpen).not.toHaveBeenCalled()
    fireEvent.click(row)
    fireEvent.doubleClick(row)
    expect(onOpen.mock.calls).toEqual([[equipment.path, true], [equipment.path, false]])
    writeText.mockRestore()
  })

  it('絞り込んだ別フォルダの結果から実登録の参照をコピーし、未登録・古い候補の参照を出さない', async () => {
    const user = userEvent.setup()
    const writeText = vi.spyOn(navigator.clipboard, 'writeText')
    const onOpen = vi.fn()
    render(<Harness entries={[equipment, quest, loot, { ...loot, path: '80.shared.loot/table/v1.stale.yml', id: 'renamed', name: '旅人の古い候補' }]}
      candidates={[...candidates, { ...candidates[2], path: '80.shared.loot/table/v1.stale.yml' }]} referencePrefixes={referencePrefixes} onOpen={onOpen} />)
    const search = screen.getByRole('textbox', { name: 'マスター検索' })
    await user.selectOptions(screen.getByRole('combobox', { name: '検索範囲' }), 'all')
    await user.type(search, '旅人')
    expect(fireEvent.contextMenu(search)).toBe(true)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    const result = screen.getByRole('button', { name: /旅人の宝箱/ })
    result.focus()
    fireEvent.keyDown(result, { key: 'F10', shiftKey: true })
    await user.click(menu().getByRole('menuitem', { name: '参照をコピー' }))
    expect(writeText).toHaveBeenLastCalledWith('loot_table:shared_id')
    expect(result).toHaveFocus()
    for (const name of [/旅人の依頼/, /旅人の古い候補/]) {
      const unregistered = screen.getByRole('button', { name })
      fireEvent.contextMenu(unregistered)
      expect(menu().queryByRole('menuitem', { name: '参照をコピー' })).not.toBeInTheDocument()
      expect(menu().getByRole('menuitem', { name: 'マスターIDをコピー' })).toBeInTheDocument()
      await user.keyboard('{Escape}')
      expect(unregistered).toHaveFocus()
    }
    expect(search).toHaveValue('旅人')
    expect(onOpen).not.toHaveBeenCalled()
    writeText.mockRestore()
  })

  it('別ウィンドウに移したパネルのクリップボードを使い、拒否された値は手動でコピーできる', async () => {
    const iframe = document.body.appendChild(document.createElement('iframe'))
    const panelDocument = iframe.contentDocument!
    const panelWindow = iframe.contentWindow!
    const target = panelDocument.body.appendChild(panelDocument.createElement('div'))
    const folderTarget = document.createElement('div')
    const writeText = vi.fn().mockRejectedValueOnce(new Error('denied')).mockResolvedValue(undefined)
    Object.defineProperty(panelWindow.navigator, 'clipboard', { value: { writeText } })
    const state: DockingState = {
      api: undefined, editorGroupId: '', visible: new Set(['master-files']), open: new Set(['master-files']), visited: new Set(['master-files']),
      activeId: 'master-files', targets: new Map([['master-files', target], ['master-folders', folderTarget]]),
      documents: new Map([['master-files', panelDocument]]), show: vi.fn(), close: vi.fn(), reset: vi.fn(),
    }
    const view = render(<DockingContext.Provider value={state}><Harness entries={[equipment]} categoryEntries={[equipmentFolder]} /></DockingContext.Provider>)
    try {
      const panel = within(panelDocument.body)
      const row = panel.getByRole('button', { name: /旅人のセット/ })
      fireEvent.contextMenu(row)
      expect(screen.queryByRole('menu')).not.toBeInTheDocument()
      fireEvent.click(panel.getByRole('menuitem', { name: 'ワークスペース相対パスをコピー' }))
      const fallback = await panel.findByRole('textbox', { name: '手動コピー用のファイル情報' })
      expect(fallback).toHaveValue(`40_filebase/${equipment.path}`)
      expect(fallback).toHaveAttribute('readonly')
      expect(row).toHaveFocus()
      fireEvent.contextMenu(row)
      fireEvent.click(panel.getByRole('menuitem', { name: 'ファイル名をコピー' }))
      expect(await panel.findByRole('status')).toHaveTextContent('コピーしました')
      expect(panel.queryByRole('textbox', { name: '手動コピー用のファイル情報' })).not.toBeInTheDocument()
      expect(writeText.mock.calls).toEqual([[`40_filebase/${equipment.path}`], ['v1.example.yml']])
    } finally {
      view.unmount()
      iframe.remove()
    }
  })
})
