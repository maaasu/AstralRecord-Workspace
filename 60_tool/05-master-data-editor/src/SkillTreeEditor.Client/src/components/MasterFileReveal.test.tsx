import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MasterFileBrowser } from './MasterFileBrowser'
import { useMasterDataWorkspace } from '../state/masterDataWorkspace'
import { masterDataApi } from '../api/masterDataApi'
import type { MasterCategory, MasterFileSummary } from '../types/masterData'

const categories: MasterCategory[] = [
  { id: '10.features.item', directory: '10.features.item', label: 'アイテム' },
  { id: 'mob', directory: '20.features.mob', label: 'モブ' },
  { id: 'deep', directory: '20.features.mob/forest', label: '森林' },
  { id: 'similar', directory: '20.features.mob/forest-other', label: '別の森林' },
].map(value => ({ ...value, fileCount: 100, fields: [], documents: [], jsonSchemas: [], templates: [] }))
const entry = (path: string, name: string): MasterFileSummary => ({ path, name, category: 'mob', format: 'yaml', id: name, revision: 'a', size: 1, modifiedUtc: '' })
const forest = Array.from({ length: 95 }, (_, i) => entry(`20.features.mob/forest/${i}.yml`, `森林モブ${i}`))
const item = entry('10.features.item/item.yml', 'アイテム')
const orphan = entry('unknown/root.yml', 'カテゴリ未登録')
const files = [item, ...forest, entry('20.features.mob/forest-other/extra.yml', '別の森林モブ'), orphan]
const target = forest[80].path
const onOpen = vi.fn()
const noop = () => {}

beforeEach(() => {
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({ root: '/fixture', backups: '/backups', categories })
  vi.spyOn(masterDataApi, 'files').mockResolvedValue(files)
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], tags: [], skillTags: [], entries: [] })
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([])
  onOpen.mockClear()
})
afterEach(() => vi.restoreAllMocks())

function Harness({ selectedPath = target, busy = false, active = true, refreshing = false }: { selectedPath?: string | null; busy?: boolean; active?: boolean; refreshing?: boolean }) {
  const w = useMasterDataWorkspace()
  return <>
    <button onClick={() => { w.setQuery('一致しない名前'); w.setFormat('json'); w.setFileStatus('error'); w.setNumericFilters({ requiredLevel: { min: '90', max: '' } }); w.setBrowserView('folders') }}>条件を設定</button>
    <button onClick={() => w.setExpandedCategories(new Set())}>フォルダを閉じる</button>
    <output data-testid="state">{JSON.stringify({ category: w.categoryId, scope: w.searchScope, count: w.visibleCount, view: w.browserView, expanded: [...w.expandedCategories], query: w.query, format: w.format, status: w.fileStatus, numeric: w.numericFilters })}</output>
    <MasterFileBrowser workspace={{ ...w, refreshing: w.refreshing || refreshing }} selectedPath={selectedPath ?? undefined} busy={busy} active={active} onOpen={onOpen} onNew={noop} onImport={noop} onDialogChange={noop} />
  </>
}
const reveal = () => screen.getByRole('button', { name: '編集中のファイルを一覧に表示' })
const state = () => JSON.parse(screen.getByTestId('state').textContent!)
const results = () => within(screen.getByLabelText('マスターの検索結果'))

it('clears filters, chooses the deepest folder, and focuses a row past 50 without opening it', async () => {
  render(<Harness />)
  await waitFor(() => expect(reveal()).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: '条件を設定' }))
  expect(results().queryByRole('button', { name: /森林モブ80/ })).not.toBeInTheDocument()
  fireEvent.click(reveal())
  await waitFor(() => expect(results().getByRole('button', { name: /森林モブ80/ })).toHaveFocus())
  expect(state()).toMatchObject({ category: 'deep', scope: 'folder', count: 95, view: 'files', query: '', format: '', status: '', numeric: {} })
  expect(state().expanded).toContain('20.features.mob')
  expect(results().getByRole('button', { name: /森林モブ80/ })).toHaveAttribute('aria-current', 'true')
  expect(results().queryByRole('button', { name: /別の森林モブ/ })).not.toBeInTheDocument()
  expect(onOpen).not.toHaveBeenCalled()
  expect(masterDataApi.files).toHaveBeenCalledTimes(1)
})

it('reveals again in the same folder after the user collapses its parent and narrows the search', async () => {
  render(<Harness />)
  await waitFor(() => expect(reveal()).toBeEnabled())
  fireEvent.click(reveal())
  await waitFor(() => expect(results().getByRole('button', { name: /森林モブ80/ })).toHaveFocus())
  fireEvent.click(screen.getByRole('button', { name: 'フォルダを閉じる' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'マスター検索' }), { target: { value: '森林モブ1' } })
  expect(state().expanded).not.toContain('20.features.mob')
  fireEvent.click(reveal())
  await waitFor(() => expect(results().getByRole('button', { name: /森林モブ80/ })).toHaveFocus())
  expect(state().expanded).toContain('20.features.mob')
  expect(state().count).toBe(95)
})

it('falls back to all Filebase for a file without a matching category', async () => {
  render(<Harness selectedPath={orphan.path} />)
  await waitFor(() => expect(reveal()).toBeEnabled())
  fireEvent.click(reveal())
  await waitFor(() => expect(results().getByRole('button', { name: /カテゴリ未登録/ })).toHaveFocus())
  expect(state().scope).toBe('all')
  expect(state().count).toBe(files.length)
})

it('keeps search and count on tab changes until explicitly requested, including readonly files', async () => {
  vi.mocked(masterDataApi.files).mockResolvedValue(files.map(file => file.path === target ? { ...file, readOnly: true } : file))
  const { rerender } = render(<Harness selectedPath={item.path} />)
  await waitFor(() => expect(reveal()).toBeEnabled())
  fireEvent.change(screen.getByRole('textbox', { name: 'マスター検索' }), { target: { value: 'アイテム' } })
  rerender(<Harness selectedPath={target} />)
  expect(state()).toMatchObject({ category: '10.features.item', query: 'アイテム', count: 50 })
  expect(onOpen).not.toHaveBeenCalled()
  fireEvent.click(reveal())
  await waitFor(() => expect(results().getByRole('button', { name: /森林モブ80/ })).toHaveFocus())
})

it.each([{ selectedPath: null }, { selectedPath: 'deleted.yml' }, { busy: true }, { active: false }, { refreshing: true }])('disables unavailable or locked navigation: %j', async options => {
  render(<Harness {...options} />)
  await screen.findByRole('button', { name: /アイテム.*item.yml/ })
  expect(reveal()).toBeDisabled()
  fireEvent.click(reveal())
  expect(state()).toMatchObject({ category: '10.features.item', count: 50 })
  expect(onOpen).not.toHaveBeenCalled()
})
