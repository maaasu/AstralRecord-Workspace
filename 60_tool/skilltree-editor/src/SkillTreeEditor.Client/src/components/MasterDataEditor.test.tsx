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
    const selector = await screen.findByRole('combobox', { name: 'マスターのカテゴリ' })
    await waitFor(() => expect(selector).toHaveValue(item.id))
    expect(within(selector).getByRole('option', { name: /アイテム共通 \(2\)/ })).toBeInTheDocument()
    expect(within(selector).getByRole('option', { name: /└ 10 素材 \(1\)/ })).toBeInTheDocument()
    expect(within(selector).getByRole('option', { name: '80 ルート / ルートプール (1)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /素材A/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /通貨B/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /セット効果/ })).not.toBeInTheDocument()
    fireEvent.change(selector, { target: { value: material.id } })
    expect(screen.queryByRole('button', { name: /通貨B/ })).not.toBeInTheDocument()
    expect(screen.getByText('1 件')).toBeInTheDocument()
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
    fireEvent.change(await screen.findByRole('textbox', { name: 'ドロップ元を名前で検索' }), { target: { value: '森のモブ' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'ドロップ元' }), { target: { value: '40.features.mob/enemy/a.yml' } })
    expect(screen.getByRole('button', { name: /素材A/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /共通ルート由来B/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /素材C/ })).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('combobox', { name: 'マスターのカテゴリ' }), { target: { value: material.id } })
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
    const source = screen.getByRole('combobox', { name: 'ドロップ元' })
    await waitFor(() => expect(within(source).getByRole('option', { name: /モブ/ })).toBeInTheDocument())
    fireEvent.change(source, { target: { value: '40.features.mob/enemy/mob.yml' } })
    fireEvent.click(screen.getByTitle('一覧と定義メタデータを再読込'))
    await waitFor(() => expect(source).toHaveValue(''))
    expect(await screen.findByText('選択していた絞り込み候補が見つからないため、絞り込みを解除しました。')).toBeInTheDocument()
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
    const source = screen.getByRole('combobox', { name: 'ドロップ元' })
    await waitFor(() => expect(within(source).getByRole('option', { name: /モブ/ })).toBeInTheDocument())
    fireEvent.change(source, { target: { value: '40.features.mob/enemy/mob.yml' } })
    fireEvent.click(screen.getByTitle('一覧と定義メタデータを再読込'))
    await waitFor(() => expect(source).toBeDisabled())
    expect(source).toHaveValue('')
    expect(screen.getByText('ドロップ元とタグの絞り込み情報を取得できませんでした。絞り込みを解除しました。再読込してください。')).toBeInTheDocument()
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
    fireEvent.change(screen.getByRole('combobox', { name: 'ドロップ元' }), { target: { value: source.path } })
    const refreshButton = screen.getByTitle('一覧と定義メタデータを再読込')
    fireEvent.click(refreshButton)
    fireEvent.click(refreshButton)
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
    fireEvent.change(await screen.findByRole('combobox', { name: 'スキルタグ' }), { target: { value: 'magic' } })
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
    expect(screen.getByRole('button', { name: '/a~1b~0c/items/1/params を編集' })).toHaveAttribute('aria-current', 'true')
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
    const navigationButton = screen.getByRole('button', { name: 'ナビを隠す' })
    fireEvent.keyDown(navigationButton, { key: 'z', ctrlKey: true })
    fireEvent.keyDown(navigationButton, { key: 'z', ctrlKey: true, shiftKey: true })
    fireEvent.keyDown(navigationButton, { key: 'y', ctrlKey: true })
    expect(screen.getByRole('button', { name: '全体を編集' })).toBeDisabled()
    expect(within(screen.getByRole('navigation', { name: '現在の階層' })).getByRole('button', { name: '全体' })).toBeDisabled()
    expect(input).toHaveValue('invalid')
    fireEvent.change(input, { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: '全体を編集' }))
    expect(screen.getByRole('textbox', { name: '/id' })).toBeInTheDocument()
  })
  it('keeps existing history and the invalid input lock until the number is corrected', async () => {
    vi.spyOn(masterDataApi, 'file').mockResolvedValue({ ...original, content: { id: 'old', params: { amount: 1, label: 'original' } } })
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '/params を開く' }))
    const input = screen.getByRole('textbox', { name: '/params/amount' })
    fireEvent.change(screen.getByRole('textbox', { name: '/params/label' }), { target: { value: 'edited' } })
    fireEvent.change(input, { target: { value: 'invalid' } })
    fireEvent.keyDown(screen.getByRole('button', { name: 'ナビを隠す' }), { key: 'z', ctrlKey: true })
    expect(input).toHaveValue('invalid')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('textbox', { name: '/params/label' })).toHaveValue('edited')
    expect(screen.getByRole('button', { name: '↶' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '全体を編集' })).toBeDisabled()
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
