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
    vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({ ...catalog, categories: [structureCategory, skillCategory] })
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
