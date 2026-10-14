import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import { MasterDataEditor } from './MasterDataEditor'
import type { MasterCatalog, MasterCategory, MasterDocument } from '../types/masterData'

const catalog: MasterCatalog = { root: '/filebase', backups: '/backups', categories: [{
  id: 'class', label: 'クラス', directory: '20.features.class', fileCount: 1,
  documents: [], fields: [{ path: '/id', key: 'id', label: 'クラスID', description: '', required: true, type: 'string' }], jsonSchemas: [], templates: [],
}] }
const original: MasterDocument = { path: '20.features.class/v1.old.yml', format: 'yaml', revision: 'original-revision', raw: 'id: old\nname: 元クラス\n', content: { id: 'old', name: '元クラス' }, issues: [] }

beforeEach(() => {
  vi.restoreAllMocks()
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue(catalog)
  vi.spyOn(masterDataApi, 'files').mockResolvedValue([{ path: original.path, category: 'class', format: 'yaml', name: '元クラス', id: 'old', revision: original.revision, size: 20, modifiedUtc: '2026-10-01T00:00:00Z' }])
  vi.spyOn(masterDataApi, 'file').mockResolvedValue(original)
  vi.spyOn(masterDataApi, 'references').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'validate').mockResolvedValue({ isValid: true, issues: [] })
  vi.spyOn(masterDataApi, 'render').mockResolvedValue({ raw: 'id: new\nname: 元クラス\n', commentsPreserved: true, warnings: [] })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

async function openFile() {
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /元クラス.*old/ }))
  await screen.findByRole('textbox', { name: '/id' })
  await waitFor(() => expect(screen.getByRole('button', { name: '原稿 YAML' })).not.toBeDisabled())
}

describe('master editor workflow', () => {
  it('keeps a filter dialog visible when a pending document collapses the narrow browser', async () => {
    window.innerWidth = 600
    let finishLoad!: (value: MasterDocument) => void
    vi.mocked(masterDataApi.file).mockImplementationOnce(() => new Promise((resolve) => { finishLoad = resolve }))
    const previousOverflow = document.body.style.overflow
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: /元クラス.*old/ }))
    await waitFor(() => expect(masterDataApi.file).toHaveBeenCalledWith(original.path))
    fireEvent.click(screen.getByRole('button', { name: '詳細フィルター' }))
    const dialog = screen.getByRole('dialog', { name: '詳細フィルター' })
    expect(document.body.style.overflow).toBe('hidden')
    await act(async () => finishLoad(original))
    await screen.findByRole('textbox', { name: '/id' })
    expect(screen.getByRole('button', { name: 'ナビを表示' })).toBeVisible()
    expect(dialog).toBeVisible()
    expect(dialog.closest('[hidden]')).toBeNull()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(screen.queryByRole('dialog', { name: '詳細フィルター' })).not.toBeInTheDocument()
    expect(document.body.style.overflow).toBe(previousOverflow)
  })

  it.each(['displayName', 'title'])('shows and updates Japanese %s in the document heading and tab', async (key) => {
    const document = { ...original, content: { id: 'old', [key]: '日本語の名称' } }
    vi.mocked(masterDataApi.file).mockResolvedValue(document)
    vi.mocked(masterDataApi.files).mockResolvedValue([{ path: original.path, category: 'class', format: 'yaml', name: '日本語の名称', id: 'old', revision: original.revision, size: 20, modifiedUtc: '' }])
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: /日本語の名称/ }))
    await screen.findByRole('heading', { name: '日本語の名称' })
    expect(screen.getByRole('tab', { name: '日本語の名称' })).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: '/' + key }), { target: { value: '新しい日本語名' } })
    expect(screen.getByRole('heading', { name: '新しい日本語名' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /新しい日本語名 · 未保存/ })).toBeInTheDocument()
  })

  it('shows Japanese names and file names while omitting only redundant IDs', async () => {
    vi.mocked(masterDataApi.files).mockResolvedValue([
      { path: '20.features.class/v1.ruins.yml', category: 'class', format: 'yaml', name: '始まりの遺跡', id: 'ruins', revision: 'a', size: 20, modifiedUtc: '' },
      { path: '20.features.class/v1.central_base.yml', category: 'class', format: 'yaml', name: '星影の街ノクス', id: 'starlit_nox', revision: 'b', size: 20, modifiedUtc: '' },
      { path: '20.features.class/v1.20a00001.sword.yml', category: 'class', format: 'yaml', name: '日本語の剣', id: '20a00001', revision: 'c', size: 20, modifiedUtc: '' },
      { path: '20.features.class/v1.sample.extra.yml', category: 'class', format: 'yaml', name: 'sample', id: 'sample', revision: 'd', size: 20, modifiedUtc: '' },
    ])
    render(<MasterDataEditor />)
    const ruins = await screen.findByRole('button', { name: /始まりの遺跡/ })
    expect(within(ruins).getByText('v1.ruins.yml')).toBeInTheDocument()
    expect(within(ruins).queryByText('ID: ruins')).not.toBeInTheDocument()
    expect(ruins).toHaveAttribute('title', expect.stringContaining('ID: ruins'))
    expect(within(screen.getByRole('button', { name: /星影の街ノクス/ })).getByText('ID: starlit_nox')).toBeInTheDocument()
    expect(within(screen.getByRole('button', { name: /日本語の剣/ })).getByText('ID: 20a00001')).toBeInTheDocument()
    expect(within(screen.getByRole('button', { name: /sample/ })).getByText('ID: sample')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: 'マスター検索' }), { target: { value: '始まり' } })
    expect(screen.getByRole('button', { name: /始まりの遺跡/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /星影の街ノクス/ })).not.toBeInTheDocument()
  })

  it('uses Japanese world names in filters while retaining ID-based filtering', async () => {
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({ sources: [], skillTags: [], tags: [], entries: [{ path: original.path, facets: { world: ['starlit_nox'] } }] })
    vi.mocked(masterDataApi.candidates).mockResolvedValue([{ path: '60.features.world/v1.central_base.yml', pointer: '/id', kind: 'world', value: 'starlit_nox', label: '星影の街ノクス' }])
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /元クラス/ })
    fireEvent.click(screen.getByRole('button', { name: '詳細フィルター' }))
    const option = screen.getByRole('option', { name: '星影の街ノクス (starlit_nox)' })
    expect(option).toHaveValue('starlit_nox')
    fireEvent.change(screen.getByRole('combobox', { name: 'ワールド' }), { target: { value: 'starlit_nox' } })
    fireEvent.click(screen.getByRole('button', { name: '条件を適用' }))
    expect(screen.getByRole('button', { name: /元クラス/ })).toBeInTheDocument()
  })

  it('starts from the complete item parent and shows nested category counts', async () => {
    const item = { ...catalog.categories[0], id: '10.features.item', label: 'アイテム共通', directory: '10.features.item', fileCount: 0 }
    const material = { ...item, id: '10.features.item/10.material', label: 'アイテム共通 / 素材', directory: '10.features.item/10.material', fileCount: 1 }
    const pool = { ...item, id: '80.shared.loot/pool', label: 'ルート / ルートプール', directory: '80.shared.loot/pool', fileCount: 1 }
    vi.mocked(masterDataApi.catalog).mockResolvedValue({ ...catalog, categories: [item, material, pool] })
    vi.mocked(masterDataApi.files).mockResolvedValue([
      { path: '10.features.item/10.material/a.yml', category: material.id, format: 'yaml', id: '10a00001', name: '素材A', revision: 'a', size: 10, modifiedUtc: '' },
      { path: '10.features.item/99.currency/b.yml', category: '10.features.item/99.currency', format: 'yaml', id: '99a00001', name: '通貨B', revision: 'b', size: 10, modifiedUtc: '' },
      { path: '10.features.item/20.equipment/set_effect/set.yml', category: '10.features.item/20.equipment/set_effect', format: 'yaml', id: 'set', name: 'セット効果', revision: 'set', size: 10, modifiedUtc: '' },
      { path: '80.shared.loot/pool/pool.yml', category: pool.id, format: 'yaml', id: 'pool', name: 'ルートプール', revision: 'pool', size: 10, modifiedUtc: '' },
    ])
    render(<MasterDataEditor />)
    const parent = await screen.findByRole('treeitem', { name: '10 アイテム共通 (2)' })
    expect(parent).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(parent, { key: 'ArrowRight' })
    expect(screen.getByRole('treeitem', { name: '10 素材 (1)' })).toBeInTheDocument()
    const virtualParent = screen.getByRole('treeitem', { name: '80 ルート (1)' })
    fireEvent.keyDown(virtualParent, { key: 'ArrowRight' })
    expect(screen.getByRole('treeitem', { name: 'ルートプール (1)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /素材A/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /通貨B/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /セット効果/ })).not.toBeInTheDocument()
    fireEvent.keyDown(screen.getByRole('treeitem', { name: '10 素材 (1)' }), { key: 'Enter' })
    expect(screen.queryByRole('button', { name: /通貨B/ })).not.toBeInTheDocument()
    expect(screen.getByLabelText('検索結果の件数')).toHaveTextContent('1 / 1 件')
  })

  it('combines the item category with a mob drop source and keeps shared drops in the list', async () => {
    const item = { ...catalog.categories[0], id: '10.features.item', label: 'アイテム共通', directory: '10.features.item' }
    const material = { ...item, id: '10.features.item/10.material', label: 'アイテム共通 / 素材', directory: '10.features.item/10.material' }
    vi.mocked(masterDataApi.catalog).mockResolvedValue({ ...catalog, categories: [item, material] })
    vi.mocked(masterDataApi.files).mockResolvedValue([
      { path: '10.features.item/10.material/a.yml', category: material.id, format: 'yaml', id: '10a00001', name: '素材A', revision: 'a', size: 10, modifiedUtc: '' },
      { path: '10.features.item/99.currency/b.yml', category: '10.features.item/99.currency', format: 'yaml', id: '99a00001', name: '共通ルート由来B', revision: 'b', size: 10, modifiedUtc: '' },
      { path: '10.features.item/10.material/c.yml', category: material.id, format: 'yaml', id: '10a00002', name: '素材C', revision: 'c', size: 10, modifiedUtc: '' },
    ])
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({
      sources: [{ path: '40.features.mob/enemy/a.yml', id: 'mob_a', name: '森のモブ', kind: 'mob', itemIds: ['10a00001', '99a00001'] }],
      skillTags: [], tags: [],
    })
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /素材A/ })
    fireEvent.click(screen.getByRole('button', { name: '詳細フィルター' }))
    fireEvent.change(await screen.findByRole('textbox', { name: 'ドロップ元を名前で検索' }), { target: { value: '森のモブ' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'ドロップ元' }), { target: { value: '40.features.mob/enemy/a.yml' } })
    fireEvent.click(screen.getByRole('button', { name: '条件を適用' }))
    expect(screen.getByRole('button', { name: /素材A/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /共通ルート由来B/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /素材C/ })).not.toBeInTheDocument()
    fireEvent.keyDown(screen.getByRole('treeitem', { name: '10 アイテム共通 (3)' }), { key: 'ArrowRight' })
    fireEvent.keyDown(screen.getByRole('treeitem', { name: '10 素材 (2)' }), { key: 'Enter' })
    expect(screen.queryByRole('button', { name: /共通ルート由来B/ })).not.toBeInTheDocument()
  })

  it('clears a selected drop source when it disappears from the refreshed index', async () => {
    const item = { ...catalog.categories[0], id: '10.features.item', label: 'アイテム共通', directory: '10.features.item' }
    vi.mocked(masterDataApi.catalog).mockResolvedValue({ ...catalog, categories: [item] })
    vi.mocked(masterDataApi.files).mockResolvedValue([
      { path: '10.features.item/10.material/a.yml', category: '10.features.item/10.material', format: 'yaml', id: 'a', name: '素材A', revision: 'a', size: 10, modifiedUtc: '' },
    ])
    vi.mocked(masterDataApi.filterIndex).mockResolvedValueOnce({
      sources: [{ path: '40.features.mob/enemy/mob.yml', id: 'mob', name: 'モブ', kind: 'mob', itemIds: ['a'] }], skillTags: [], tags: [],
    }).mockResolvedValue({ sources: [], skillTags: [], tags: [] })
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /素材A/ })
    fireEvent.click(screen.getByRole('button', { name: '詳細フィルター' }))
    const source = screen.getByRole('combobox', { name: 'ドロップ元' })
    await waitFor(() => expect(within(source).getByRole('option', { name: /モブ/ })).toBeInTheDocument())
    fireEvent.change(source, { target: { value: '40.features.mob/enemy/mob.yml' } })
    fireEvent.click(screen.getByRole('button', { name: '条件を適用' }))
    fireEvent.click(screen.getByTitle('一覧と定義メタデータを再読込'))
    expect(await screen.findByText('選択していた絞り込み候補が見つからないため、絞り込みを解除しました。')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '詳細フィルター' }))
    expect(screen.getByRole('combobox', { name: 'ドロップ元' })).toHaveValue('')
    expect(screen.getByRole('combobox', { name: 'ドロップ元' })).not.toBeDisabled()
  })

  it('disables stale filters when the index refresh fails', async () => {
    const item = { ...catalog.categories[0], id: '10.features.item', label: 'アイテム共通', directory: '10.features.item' }
    vi.mocked(masterDataApi.catalog).mockResolvedValue({ ...catalog, categories: [item] })
    vi.mocked(masterDataApi.files).mockResolvedValue([
      { path: '10.features.item/10.material/a.yml', category: '10.features.item/10.material', format: 'yaml', id: 'a', name: '素材A', revision: 'a', size: 10, modifiedUtc: '' },
    ])
    vi.mocked(masterDataApi.filterIndex).mockResolvedValueOnce({
      sources: [{ path: '40.features.mob/enemy/mob.yml', id: 'mob', name: 'モブ', kind: 'mob', itemIds: ['a'] }], skillTags: [], tags: [],
    }).mockRejectedValue(new Error('index unavailable'))
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /素材A/ })
    fireEvent.click(screen.getByRole('button', { name: '詳細フィルター' }))
    const source = screen.getByRole('combobox', { name: 'ドロップ元' })
    await waitFor(() => expect(within(source).getByRole('option', { name: /モブ/ })).toBeInTheDocument())
    fireEvent.change(source, { target: { value: '40.features.mob/enemy/mob.yml' } })
    fireEvent.click(screen.getByRole('button', { name: '条件を適用' }))
    fireEvent.click(screen.getByTitle('一覧と定義メタデータを再読込'))
    expect(await screen.findByText('ドロップ元とタグなどの絞り込み情報を取得できませんでした。絞り込みを解除しました。再読込してください。')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '詳細フィルター' }))
    expect(screen.getByRole('combobox', { name: 'ドロップ元' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'ドロップ元' })).toHaveValue('')
  })

  it('keeps the newest filter index when two refreshes finish out of order', async () => {
    const item = { ...catalog.categories[0], id: '10.features.item', label: 'アイテム共通', directory: '10.features.item' }
    vi.mocked(masterDataApi.catalog).mockResolvedValue({ ...catalog, categories: [item] })
    vi.mocked(masterDataApi.files).mockResolvedValue([
      { path: '10.features.item/10.material/a.yml', category: '10.features.item/10.material', format: 'yaml', id: 'a', name: '素材A', revision: 'a', size: 10, modifiedUtc: '' },
      { path: '10.features.item/10.material/b.yml', category: '10.features.item/10.material', format: 'yaml', id: 'b', name: '素材B', revision: 'b', size: 10, modifiedUtc: '' },
    ])
    const source = { path: '40.features.mob/enemy/mob.yml', id: 'mob', name: 'モブ', kind: 'mob' as const }
    let finishOlder!: (value: { sources: (typeof source & { itemIds: string[] })[]; skillTags: []; tags: [] }) => void
    let finishNewer!: typeof finishOlder
    vi.mocked(masterDataApi.filterIndex)
      .mockResolvedValueOnce({ sources: [{ ...source, itemIds: ['a'] }], skillTags: [], tags: [] })
      .mockImplementationOnce(() => new Promise((resolve) => { finishOlder = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { finishNewer = resolve }))
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /素材A/ })
    fireEvent.click(screen.getByRole('button', { name: '詳細フィルター' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'ドロップ元' }), { target: { value: source.path } })
    fireEvent.click(screen.getByRole('button', { name: '条件を適用' }))
    const refreshButton = screen.getByTitle('一覧と定義メタデータを再読込')
    act(() => { fireEvent.click(refreshButton); fireEvent.click(refreshButton) })
    await waitFor(() => expect(masterDataApi.filterIndex).toHaveBeenCalledTimes(3))
    await act(async () => finishNewer({ sources: [{ ...source, itemIds: ['b'] }], skillTags: [], tags: [] }))
    expect(screen.getByRole('button', { name: /素材B/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /素材A/ })).not.toBeInTheDocument()
    await act(async () => finishOlder({ sources: [{ ...source, itemIds: ['a'] }], skillTags: [], tags: [] }))
    expect(screen.getByRole('button', { name: /素材B/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /素材A/ })).not.toBeInTheDocument()
  })

  it('filters skills by tag', async () => {
    const skill = { ...catalog.categories[0], id: '30.features.skill', label: 'スキル', directory: '30.features.skill' }
    vi.mocked(masterDataApi.catalog).mockResolvedValue({ ...catalog, categories: [skill] })
    vi.mocked(masterDataApi.files).mockResolvedValue([
      { path: '30.features.skill/a.yml', category: skill.id, format: 'yaml', id: 'a', name: 'スキルA', revision: 'a', size: 10, modifiedUtc: '' },
      { path: '30.features.skill/b.yml', category: skill.id, format: 'yaml', id: 'b', name: 'スキルB', revision: 'b', size: 10, modifiedUtc: '' },
    ])
    vi.mocked(masterDataApi.filterIndex).mockResolvedValue({ sources: [], skillTags: [{ path: '30.features.skill/a.yml', tags: ['magic'] }], tags: [{ id: 'magic', name: '魔法' }] })
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /スキルA/ })
    fireEvent.click(screen.getByRole('button', { name: '詳細フィルター' }))
    fireEvent.change(await screen.findByRole('combobox', { name: 'スキルタグ' }), { target: { value: 'magic' } })
    fireEvent.click(screen.getByRole('button', { name: '条件を適用' }))
    expect(screen.getByRole('button', { name: /スキルA/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /スキルB/ })).not.toBeInTheDocument()
  })

  it('adds more files at the bottom without replacing previously shown files', async () => {
    vi.mocked(masterDataApi.files).mockResolvedValue(Array.from({ length: 60 }, (_, index) => ({
      path: `20.features.class/v1.item-${index}.yml`, category: 'class', format: 'yaml', id: `item-${index}`,
      name: `項目${index}`, revision: String(index), size: 10, modifiedUtc: '',
    })))
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: /項目0/ })
    expect(screen.queryByRole('button', { name: /項目59/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /さらに表示/ }))
    expect(screen.getByRole('button', { name: /項目0/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /項目59/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /さらに表示/ })).not.toBeInTheDocument()
  })

  it('loads the next batch when the bottom of the list enters view', async () => {
    let notify!: IntersectionObserverCallback
    const observe = vi.fn()
    vi.stubGlobal('IntersectionObserver', class {
      constructor(callback: IntersectionObserverCallback) { notify = callback }
      observe = observe
      disconnect = vi.fn()
    })
    try {
      vi.mocked(masterDataApi.files).mockResolvedValue(Array.from({ length: 60 }, (_, index) => ({
        path: `20.features.class/v1.item-${index}.yml`, category: 'class', format: 'yaml', id: `item-${index}`,
        name: `項目${index}`, revision: String(index), size: 10, modifiedUtc: '',
      })))
      render(<MasterDataEditor />)
      await screen.findByRole('button', { name: /項目0/ })
      await waitFor(() => expect(observe).toHaveBeenCalled())
      act(() => notify([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver))
      expect(screen.getByRole('button', { name: /項目59/ })).toBeInTheDocument()
    } finally { vi.unstubAllGlobals() }
  })
  it('renders and saves the complete document after editing a selected deep node', async () => {
    const content = { id: 'old', 'a/b~c': { items: [{ params: { amount: 2, UnknownRpc: 'keep' } }, { untouched: true }] }, other: { keep: 'root' } }
    const loaded = { ...original, content }
    vi.spyOn(masterDataApi, 'file').mockResolvedValue(loaded)
    const save = vi.spyOn(masterDataApi, 'save').mockResolvedValue(loaded)
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '/a~1b~0c を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/a~1b~0c/items を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/a~1b~0c/items/0 を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/a~1b~0c/items/0/params を開く' }))
    fireEvent.change(screen.getByRole('textbox', { name: '/a~1b~0c/items/0/params/amount' }), { target: { value: '9' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(save).toHaveBeenCalled())
    expect(masterDataApi.render).toHaveBeenCalledWith(original.path, { ...content, 'a/b~c': { items: [{ params: { amount: 9, UnknownRpc: 'keep' } }, { untouched: true }] } }, original.raw)
    expect(save).toHaveBeenCalledWith(original.path, expect.any(String), original.revision)
  })
  it('opens an invisible deep validation field and focuses the actual input', async () => {
    const path = '/a~1b~0c/items/1/params/amount'
    vi.spyOn(masterDataApi, 'file').mockResolvedValue({ ...original, content: { id: 'old', 'a/b~c': { items: [{ params: { amount: 1 } }, { params: { amount: 2 } }] } } })
    vi.spyOn(masterDataApi, 'validate').mockResolvedValue({ isValid: false, issues: [{ code: 'amount', severity: 'error', path, message: '値を確認してください。' }] })
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '検証' }))
    fireEvent.click(await screen.findByRole('button', { name: path }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: path })).toHaveFocus())
    expect(within(screen.getByRole('navigation', { name: '現在の階層' })).getByRole('button', { name: 'params' })).toHaveAttribute('aria-current', 'page')
  })
  it('focuses the shared lore textarea for a validation issue at one array element', async () => {
    vi.mocked(masterDataApi.file).mockResolvedValue({ ...original, content: { id: 'old', lore: ['一行目', '二行目'] } })
    vi.mocked(masterDataApi.validate).mockResolvedValue({ isValid: false, issues: [{ code: 'lore', severity: 'error', path: '/lore/0', message: '説明を確認してください。' }] })
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '検証' }))
    fireEvent.click(await screen.findByRole('button', { name: '/lore/0' }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: '/lore' })).toHaveFocus())
  })
  it('returns to the array list before undoing a reordered element', async () => {
    vi.spyOn(masterDataApi, 'file').mockResolvedValue({ ...original, content: { id: 'old', items: [{ name: 'one', params: { amount: 1 } }, { name: 'two', params: { amount: 2 } }] } })
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '/items を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/items/1 上へ' }))
    fireEvent.click(screen.getByRole('button', { name: '/items/0 を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/items/0/params を開く' }))
    expect(screen.getByRole('textbox', { name: '/items/0/params/amount' })).toHaveValue('2')
    fireEvent.click(screen.getByRole('button', { name: '↶' }))
    expect(screen.queryByRole('textbox', { name: '/items/0/params/amount' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '/items/0 を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/items/0/params を開く' }))
    expect(screen.getByRole('textbox', { name: '/items/0/params/amount' })).toHaveValue('1')
  })
  it('keeps invalid numeric text mounted while blocking navigation away', async () => {
    vi.spyOn(masterDataApi, 'file').mockResolvedValue({ ...original, content: { id: 'old', params: { amount: 1 } } })
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '/params を開く' }))
    const input = screen.getByRole('textbox', { name: '/params/amount' })
    fireEvent.change(input, { target: { value: 'invalid' } })
    const navigationButton = screen.getByRole('button', { name: 'ナビを閉じる' })
    fireEvent.keyDown(navigationButton, { key: 'z', ctrlKey: true })
    fireEvent.keyDown(navigationButton, { key: 'z', ctrlKey: true, shiftKey: true })
    fireEvent.keyDown(navigationButton, { key: 'y', ctrlKey: true })
    expect(within(screen.getByRole('navigation', { name: '編集セクション' })).getByRole('button', { name: '基本情報' })).toBeDisabled()
    expect(within(screen.getByRole('navigation', { name: '現在の階層' })).getByRole('button', { name: '基本情報' })).toBeDisabled()
    expect(input).toHaveValue('invalid')
    fireEvent.change(input, { target: { value: '3' } })
    fireEvent.click(within(screen.getByRole('navigation', { name: '編集セクション' })).getByRole('button', { name: '基本情報' }))
    expect(screen.getByRole('textbox', { name: '/id' })).toBeInTheDocument()
  })
  it('keeps existing history and the invalid input lock until the number is corrected', async () => {
    vi.spyOn(masterDataApi, 'file').mockResolvedValue({ ...original, content: { id: 'old', params: { amount: 1, label: 'original' } } })
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '/params を開く' }))
    const input = screen.getByRole('textbox', { name: '/params/amount' })
    fireEvent.change(screen.getByRole('textbox', { name: '/params/label' }), { target: { value: 'edited' } })
    fireEvent.change(input, { target: { value: 'invalid' } })
    fireEvent.keyDown(screen.getByRole('button', { name: 'ナビを閉じる' }), { key: 'z', ctrlKey: true })
    expect(input).toHaveValue('invalid')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('textbox', { name: '/params/label' })).toHaveValue('edited')
    expect(screen.getByRole('button', { name: '↶' })).toBeDisabled()
    expect(within(screen.getByRole('navigation', { name: '編集セクション' })).getByRole('button', { name: '基本情報' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).toBeDisabled()
    fireEvent.change(input, { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: '↶' }))
    expect(input).toHaveValue('1')
    expect(screen.getByRole('textbox', { name: '/params/label' })).toHaveValue('edited')
    fireEvent.click(screen.getByRole('button', { name: '↶' }))
    expect(screen.getByRole('textbox', { name: '/params/label' })).toHaveValue('original')
  })
  it('colors list, header and references, finds visible names, and saves original color codes', async () => {
    const name = '&6太字&aのアイテム'
    const colored = { ...original, content: { id: 'old', name }, raw: `id: old\nname: '${name}'\n` }
    vi.mocked(masterDataApi.files).mockResolvedValue([{ path: original.path, category: 'class', format: 'yaml', name, id: 'old', revision: original.revision, size: 20, modifiedUtc: '' }])
    vi.mocked(masterDataApi.file).mockResolvedValue(colored)
    vi.mocked(masterDataApi.references).mockResolvedValue([{ path: original.path, kind: 'item', pointer: '/itemId', value: 'old', label: '§b参照元' }])
    vi.mocked(masterDataApi.render).mockImplementation(async (_path, content) => ({ raw: `id: new\nname: '${(content as { name: string }).name}'\n`, commentsPreserved: true, warnings: [] }))
    const save = vi.spyOn(masterDataApi, 'save').mockResolvedValue({ ...colored, revision: 'next' })
    render(<MasterDataEditor />)
    const entry = await screen.findByRole('button', { name: /太字のアイテム.*old/ })
    expect(within(entry).getByText('太字')).toHaveStyle({ color: '#ffaa00' })
    fireEvent.change(screen.getByRole('textbox', { name: 'マスター検索' }), { target: { value: '太字のアイテム' } })
    expect(entry).toBeInTheDocument()
    fireEvent.click(entry)
    await screen.findByRole('textbox', { name: '/name' })
    expect(screen.getByRole('heading', { name: '太字のアイテム' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue(name)
    fireEvent.click(screen.getByRole('button', { name: /参照一覧/ }))
    expect(await screen.findByText('参照元', { selector: '.minecraft-text span' })).toHaveStyle({ color: '#55ffff' })
    fireEvent.change(screen.getByRole('textbox', { name: '/id' }), { target: { value: 'new' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith(original.path, `id: new\nname: '${name}'\n`, original.revision))
  })

  it('validates first and saves using the original optimistic revision', async () => {
    const save = vi.spyOn(masterDataApi, 'save').mockResolvedValue({ ...original, content: { id: 'new', name: '元クラス' }, raw: 'id: new\nname: 元クラス\n', revision: 'new-revision' })
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/id' }), { target: { value: 'new' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith(original.path, 'id: new\nname: 元クラス\n', 'original-revision'))
    expect(masterDataApi.validate).toHaveBeenCalledWith(original.path, 'id: new\nname: 元クラス\n')
    await screen.findByText('v1.old.yml を保存しました。')
    expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).toBeDisabled()
  })

  it('keeps the draft on a server conflict', async () => {
    vi.spyOn(masterDataApi, 'save').mockRejectedValue(new ApiError('他の編集で変更されています。再読込してください。', 409))
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/id' }), { target: { value: 'new' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await screen.findByText('他の編集で変更されています。再読込してください。')
    expect(screen.getByRole('textbox', { name: '/id' })).toHaveValue('new')
    expect(screen.getByText('未保存')).toBeInTheDocument()
  })

  it('does not save documents with path-specific validation errors', async () => {
    const save = vi.spyOn(masterDataApi, 'save')
    vi.spyOn(masterDataApi, 'validate').mockResolvedValue({ isValid: false, issues: [{ code: 'duplicate-id', severity: 'error', path: '/id', message: 'IDが重複しています。' }] })
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/id' }), { target: { value: 'new' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await screen.findByText('検証エラーがあるため保存しませんでした。')
    expect(save).not.toHaveBeenCalled()
    expect(screen.getByText('duplicate-id')).toBeInTheDocument()
  })

  it('requires reviewing raw diff when structural changes may remove comments', async () => {
    const save = vi.spyOn(masterDataApi, 'save').mockResolvedValue({ ...original, content: { id: 'new', name: '元クラス' }, raw: 'id: new\nname: 元クラス\n' })
    vi.spyOn(masterDataApi, 'render').mockResolvedValue({ raw: 'id: new\nname: 元クラス\n', commentsPreserved: false, warnings: ['変更した配列内のコメントは保持できません。'] })
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/id' }), { target: { value: 'new' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    const dialog = await screen.findByRole('dialog', { name: '保存前の差分' })
    expect(save).not.toHaveBeenCalled()
    fireEvent.click(dialog.querySelector('button')!)
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1))
  })

  it('allows a new ID to be edited before creating a non-item copy', async () => {
    const create = vi.spyOn(masterDataApi, 'create').mockResolvedValue({ ...original, path: '20.features.class/v1.new.yml', raw: 'id: new\nname: 複製クラス\n', content: { id: 'new', name: '複製クラス' } })
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '保存済みファイルを複製' }))
    const raw = screen.getByRole('textbox', { name: '新規マスター原稿' })
    expect(raw).not.toHaveAttribute('readonly')
    fireEvent.change(raw, { target: { value: 'id: new\nname: 複製クラス\n' } })
    fireEvent.click(screen.getByRole('button', { name: '検証して作成' }))
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({ path: '20.features.class', raw: 'id: new\nname: 複製クラス\n', autoItemId: false, autoName: true })))
  })

  it('blocks a document switch if discarding unsaved content is declined', async () => {
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/id' }), { target: { value: 'unsaved' } })
    vi.mocked(window.confirm).mockReturnValue(false)
    fireEvent.click(screen.getByRole('button', { name: 'ファイルを再読込' }))
    expect(masterDataApi.file).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('textbox', { name: '/id' })).toHaveValue('unsaved')
  })

  it('shows read-only administrative documents and disables their mutations', async () => {
    vi.spyOn(masterDataApi, 'files').mockResolvedValue([{ path: original.path, category: 'class', format: 'yaml', name: '元クラス', id: 'old', revision: original.revision, size: 20, modifiedUtc: '', readOnly: true }])
    await openFile()
    expect(screen.getByRole('textbox', { name: '/id' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '保存済みファイルを複製' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '削除' })).toBeDisabled()
  })

  it('uses a YAML template format for a new category after opening JSON', async () => {
    const structureCategory: MasterCategory = { ...catalog.categories[0], id: 'structure', label: '構造', directory: '35.features.skilltree/structures', templates: [] }
    const skillCategory: MasterCategory = { ...catalog.categories[0], id: 'skill', label: 'スキル', directory: '30.features.skill', templates: [{ path: '30.features.skill/v1.new_skill.yml', raw: 'schemaVersion: 1\nid: new_skill\n' }] }
    vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({ ...catalog, categories: [catalog.categories[0], structureCategory, skillCategory] })
    vi.spyOn(masterDataApi, 'file').mockResolvedValue({ ...original, path: '35.features.skilltree/structures/tree.json', format: 'json', raw: '{"id":"old"}' })
    const create = vi.spyOn(masterDataApi, 'create').mockResolvedValue({ ...original, path: '30.features.skill/v1.new_skill.yml' })
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: /元クラス.*old/ }))
    await screen.findByRole('textbox', { name: '/id' })
    await waitFor(() => expect(screen.getByRole('button', { name: '＋ 新規' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '＋ 新規' }))
    const dialog = within(screen.getByRole('dialog', { name: 'マスターファイル作成' }))
    fireEvent.change(dialog.getByRole('combobox', { name: 'カテゴリ' }), { target: { value: 'skill' } })
    expect(dialog.getByRole('combobox', { name: '新規マスターの形式' })).toHaveValue('yaml')
    expect(dialog.getByRole('textbox', { name: /保存先/ })).toHaveValue('30.features.skill/v1.new_skill.yml')
    fireEvent.click(dialog.getByRole('button', { name: '検証して作成' }))
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({ path: '30.features.skill/v1.new_skill.yml', raw: 'schemaVersion: 1\nid: new_skill\n' })))
  })

  it('omits a dot directory prefix and permits explicitly choosing JSON', async () => {
    const rootCategory: MasterCategory = { ...catalog.categories[0], id: 'root', label: 'ルート', directory: '.', templates: [{ path: 'v1.root_config.yml', raw: 'schemaVersion: 1\nid: root_config\n' }] }
    vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({ ...catalog, categories: [rootCategory] })
    const create = vi.spyOn(masterDataApi, 'create').mockResolvedValue(original)
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '＋ 新規' }))
    const dialog = within(screen.getByRole('dialog', { name: 'マスターファイル作成' }))
    expect(dialog.getByRole('textbox', { name: /保存先/ })).toHaveValue('v1.root_config.yml')
    fireEvent.change(dialog.getByRole('combobox', { name: '新規マスターの形式' }), { target: { value: 'json' } })
    fireEvent.change(dialog.getByRole('textbox', { name: '新規マスター原稿' }), { target: { value: '{"id":"root_json","schemaVersion":1}' } })
    expect(dialog.getByRole('textbox', { name: /保存先/ })).toHaveValue('root_json.json')
    fireEvent.click(dialog.getByRole('button', { name: '検証して作成' }))
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({ path: 'root_json.json', raw: '{"id":"root_json","schemaVersion":1}' })))
  })

  it('locks every creation control while the submitted request is pending', async () => {
    const skillCategory: MasterCategory = { ...catalog.categories[0], id: 'skill', label: 'スキル', directory: '30.features.skill', templates: [{ path: '30.features.skill/v1.new_skill.yml', raw: 'schemaVersion: 1\nid: new_skill\n' }] }
    vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({ ...catalog, categories: [skillCategory] })
    let complete!: (value: MasterDocument) => void
    const create = vi.spyOn(masterDataApi, 'create').mockImplementation(() => new Promise((resolve) => { complete = resolve }))
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '＋ 新規' }))
    const dialog = within(screen.getByRole('dialog', { name: 'マスターファイル作成' }))
    fireEvent.click(dialog.getByRole('button', { name: '検証して作成' }))
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(dialog.getByRole('textbox', { name: '新規マスター原稿' })).toBeDisabled()
    expect(dialog.getByRole('combobox', { name: 'カテゴリ' })).toBeDisabled()
    expect(dialog.getByRole('combobox', { name: '新規マスターの形式' })).toBeDisabled()
    expect(dialog.getByRole('combobox', { name: 'テンプレート' })).toBeDisabled()
    expect(dialog.getByRole('textbox', { name: /ファイル名用 slug/ })).toBeDisabled()
    expect(dialog.getByRole('checkbox', { name: /slugから保存先/ })).toBeDisabled()
    expect(dialog.getByRole('button', { name: 'キャンセル' })).toBeDisabled()
    await act(async () => complete({ ...original, path: '30.features.skill/v1.new_skill.yml' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'マスターファイル作成' })).not.toBeInTheDocument())
  })
})


describe('creation draft protection', () => {
  async function openNew(onDirtyChange = vi.fn()) {
    const view = render(<MasterDataEditor onDirtyChange={onDirtyChange} />)
    fireEvent.click(await screen.findByRole('button', { name: '＋ 新規' }))
    return { ...view, dialog: within(screen.getByRole('dialog', { name: 'マスターファイル作成' })), onDirtyChange }
  }
  function unloadPrevented() {
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    return event.defaultPrevented
  }
  function withSchema() {
    vi.mocked(masterDataApi.catalog).mockResolvedValue({ ...catalog, categories: [{
      ...catalog.categories[0], jsonSchemas: [{ path: 'test.schema.json', title: 'Test', schema: { type: 'object', properties: { id: { type: 'string' } } } }],
    }] })
  }

  it('protects a welcome draft on unload and cancel, and clears only after confirmed discard', async () => {
    const { dialog, onDirtyChange } = await openNew()
    expect(unloadPrevented()).toBe(false)
    fireEvent.change(dialog.getByRole('textbox', { name: '新規マスター原稿' }), { target: { value: 'id: unsaved' } })
    expect(onDirtyChange).toHaveBeenLastCalledWith(true)
    expect(unloadPrevented()).toBe(true)
    vi.mocked(window.confirm).mockReturnValue(false)
    fireEvent.click(dialog.getByRole('button', { name: 'キャンセル' }))
    expect(dialog.getByRole('textbox', { name: '新規マスター原稿' })).toHaveValue('id: unsaved')
    vi.mocked(window.confirm).mockReturnValue(true)
    fireEvent.click(dialog.getByRole('button', { name: 'キャンセル' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(unloadPrevented()).toBe(false)
    expect(onDirtyChange).toHaveBeenLastCalledWith(false)
  })

  it('keeps existing document edits dirty when a copy is discarded and guards tab closing', async () => {
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/id' }), { target: { value: 'existing-edit' } })
    fireEvent.click(screen.getByRole('button', { name: '保存済みファイルを複製' }))
    vi.mocked(window.confirm).mockReturnValue(false)
    fireEvent.click(screen.getByRole('button', { name: /v1.old.yml のタブを閉じる/ }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    vi.mocked(window.confirm).mockReturnValue(true)
    fireEvent.click(screen.getByRole('button', { name: 'キャンセル' }))
    expect(screen.getByRole('textbox', { name: '/id' })).toHaveValue('existing-edit')
    expect(unloadPrevented()).toBe(true)
  })

  it('preserves a draft while hidden and refuses category, template and welcome-file replacement', async () => {
    const template = { path: 'new.yml', raw: 'id: template' }
    vi.mocked(masterDataApi.catalog).mockResolvedValue({ ...catalog, categories: [
      { ...catalog.categories[0], templates: [template, { ...template, path: 'other.yml', raw: 'id: other' }] },
      { ...catalog.categories[0], id: 'other', directory: 'other' },
    ] })
    const { dialog, rerender, onDirtyChange } = await openNew()
    fireEvent.change(dialog.getByRole('textbox', { name: '新規マスター原稿' }), { target: { value: 'id: preserved' } })
    vi.mocked(window.confirm).mockReturnValue(false)
    fireEvent.change(dialog.getByRole('combobox', { name: 'カテゴリ' }), { target: { value: 'other' } })
    fireEvent.change(dialog.getByRole('combobox', { name: 'テンプレート' }), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: /元クラス.*old/ }))
    expect(dialog.getByRole('textbox', { name: '新規マスター原稿' })).toHaveValue('id: preserved')
    expect(masterDataApi.file).not.toHaveBeenCalled()
    rerender(<MasterDataEditor active={false} onDirtyChange={onDirtyChange} />)
    expect(unloadPrevented()).toBe(true)
    rerender(<MasterDataEditor active onDirtyChange={onDirtyChange} />)
    expect(dialog.getByRole('textbox', { name: '新規マスター原稿' })).toHaveValue('id: preserved')
  })

  it('locks generation controls, rejects duplicate generation, and ignores a cancelled response', async () => {
    withSchema()
    let finish!: (value: Awaited<ReturnType<typeof masterDataApi.render>>) => void
    vi.mocked(masterDataApi.render).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
    const { dialog } = await openNew()
    const generate = dialog.getByRole('button', { name: 'Schemaから必須項目を生成' })
    fireEvent.click(generate)
    fireEvent.click(generate)
    expect(masterDataApi.render).toHaveBeenCalledTimes(1)
    expect(dialog.getByRole('textbox', { name: '新規マスター原稿' })).toBeDisabled()
    expect(dialog.getByRole('combobox', { name: 'カテゴリ' })).toBeDisabled()
    expect(dialog.getByRole('button', { name: '検証して作成' })).toBeDisabled()
    expect(unloadPrevented()).toBe(true)
    fireEvent.click(dialog.getByRole('button', { name: 'キャンセル' }))
    fireEvent.click(screen.getByRole('button', { name: '＋ 新規' }))
    const nextRaw = screen.getByRole('textbox', { name: '新規マスター原稿' })
    fireEvent.change(nextRaw, { target: { value: 'id: next' } })
    await act(async () => finish({ raw: 'id: stale', commentsPreserved: true, warnings: [] }))
    expect(nextRaw).toHaveValue('id: next')
  })

  it('preserves input on generation failure and permits retry after explicit replacement approval', async () => {
    withSchema()
    vi.mocked(masterDataApi.render).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ raw: 'id: generated', commentsPreserved: true, warnings: [] })
    const { dialog } = await openNew()
    const raw = dialog.getByRole('textbox', { name: '新規マスター原稿' })
    fireEvent.change(raw, { target: { value: 'id: manual' } })
    vi.mocked(window.confirm).mockReturnValue(false)
    fireEvent.click(dialog.getByRole('button', { name: 'Schemaから必須項目を生成' }))
    expect(masterDataApi.render).not.toHaveBeenCalled()
    vi.mocked(window.confirm).mockReturnValue(true)
    fireEvent.click(dialog.getByRole('button', { name: 'Schemaから必須項目を生成' }))
    await screen.findByText('offline')
    expect(raw).toHaveValue('id: manual')
    expect(raw).not.toBeDisabled()
    fireEvent.click(dialog.getByRole('button', { name: 'Schemaから必須項目を生成' }))
    await waitFor(() => expect(raw).toHaveValue('id: generated'))
    expect(unloadPrevented()).toBe(true)
  })

  it('retains failed creation drafts, prevents duplicate submission and clears dirty after success', async () => {
    const create = vi.spyOn(masterDataApi, 'create').mockRejectedValueOnce(new Error('create offline')).mockResolvedValueOnce(original)
    const { dialog, onDirtyChange } = await openNew()
    const raw = dialog.getByRole('textbox', { name: '新規マスター原稿' })
    fireEvent.change(raw, { target: { value: 'id: new' } })
    fireEvent.click(dialog.getByRole('button', { name: '検証して作成' }))
    fireEvent.submit(raw.closest('form')!)
    await waitFor(() => expect(raw).not.toBeDisabled())
    expect(create).toHaveBeenCalledTimes(1)
    expect(raw).toHaveValue('id: new')
    expect(unloadPrevented()).toBe(true)
    fireEvent.click(dialog.getByRole('button', { name: '検証して作成' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(create).toHaveBeenCalledTimes(2)
    await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(false))
    expect(unloadPrevented()).toBe(false)
  })
})
