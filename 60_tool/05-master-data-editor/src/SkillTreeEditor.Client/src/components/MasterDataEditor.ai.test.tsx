import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { aiAssistApi } from '../api/aiAssistApi'
import { masterDataApi } from '../api/masterDataApi'
import type { AiProposal, AiProposalResponse } from '../types/aiAssist'
import type { MasterCatalog, MasterDocument } from '../types/masterData'
import { MasterDataEditor } from './MasterDataEditor'

const category = { id: 'items/equipment', label: 'アイテム / 装備', directory: '10.items/20.equipment', itemCategoryCode: '20', fileCount: 2, documents: [], fields: [], jsonSchemas: [], templates: [] }
const materialCategory = { ...category, id: 'items/material', label: 'アイテム / 素材', directory: '10.items/10.material', itemCategoryCode: '10' }
const catalog: MasterCatalog = { root: '/ai-test-filebase', backups: '/backups', categories: [
  { ...category, id: 'items', label: 'アイテム', directory: '10.items', itemCategoryCode: null }, category, materialCategory,
] }
const content = { id: '20a00001', name: '元の剣', description: '元の説明', lore: ['1行目', '2行目'] }
const original: MasterDocument = { path: '10.items/20.equipment/sword.json', format: 'json', raw: JSON.stringify(content, null, 2), revision: 'rev-sword', content, issues: [] }
const other: MasterDocument = { ...original, path: '10.items/20.equipment/shield.json', revision: 'rev-shield', raw: JSON.stringify({ ...content, id: '20a00002', name: '元の盾' }, null, 2), content: { ...content, id: '20a00002', name: '元の盾' } }
const configured = { configured: true, model: 'gpt-6.1-terra', keySource: 'session', reason: null }
const changedRaw = JSON.stringify({ ...content, name: '星の剣' }, null, 2)
const proposal = (source = original): AiProposal => ({ path: source.path, sourcePath: source.path, raw: source === original ? changedRaw : JSON.stringify({ ...other.content as object, name: '星の盾' }, null, 2), beforeRaw: source.raw, revision: source.revision, summary: '名前を変更', issues: [], isNew: false })
const result = (proposals: AiProposal[], mode = 'batch', suggestions: string[] = []): AiProposalResponse => ({ mode, model: configured.model, proposals, suggestions, warnings: [] })

beforeEach(() => {
  vi.restoreAllMocks()
  vi.spyOn(aiAssistApi, 'settings').mockResolvedValue(configured)
  vi.spyOn(aiAssistApi, 'configure').mockResolvedValue(configured)
  vi.spyOn(aiAssistApi, 'propose').mockResolvedValue(result([proposal()]))
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue(catalog)
  vi.spyOn(masterDataApi, 'files').mockResolvedValue([original, other].map(document => ({ path: document.path, category: category.id, format: 'json', name: (document.content as { name: string }).name, id: (document.content as { id: string }).id, revision: document.revision, size: 100, modifiedUtc: '' })))
  vi.spyOn(masterDataApi, 'file').mockImplementation(async path => path === other.path ? other : original)
  vi.spyOn(masterDataApi, 'references').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'render').mockImplementation(async (_path, value) => ({ raw: JSON.stringify(value, null, 2), commentsPreserved: true, warnings: [] }))
  vi.spyOn(masterDataApi, 'parse').mockImplementation(async (_path, raw) => ({ content: JSON.parse(raw), issues: [] }))
  vi.spyOn(masterDataApi, 'save').mockResolvedValue(original)
  vi.spyOn(masterDataApi, 'create').mockResolvedValue(original)
})

async function openFile() {
  render(<MasterDataEditor />)
  fireEvent.doubleClick(await screen.findByRole('button', { name: /元の剣.*sword/ }))
  await screen.findByRole('textbox', { name: '/name' })
  await waitFor(() => expect(screen.getByRole('button', { name: 'AIアシスト' })).toBeEnabled())
}
async function openAi() {
  fireEvent.click(screen.getByRole('button', { name: 'AIアシスト' }))
  const dialog = within(await screen.findByRole('dialog', { name: 'AIアシスト' }))
  await dialog.findByText('APIキー設定済み')
  return dialog
}
async function generateBatch(name = '元の剣') {
  const dialog = await openAi()
  fireEvent.click(dialog.getByRole('button', { name: '一括編集' }))
  fireEvent.click(within(dialog.getByRole('group', { name: 'AI一括編集対象' })).getByRole('checkbox', { name: new RegExp(name) }))
  fireEvent.change(dialog.getByRole('textbox', { name: 'AIへの指示' }), { target: { value: '名前を星の装備へ変更' } })
  fireEvent.click(dialog.getByRole('button', { name: '候補を生成' }))
  return dialog
}
async function reviewAndSelect(dialog: ReturnType<typeof within>) {
  fireEvent.click(await dialog.findByText('原稿と差分を確認'))
  const selection = dialog.getByRole('checkbox', { name: 'この候補を採用する' })
  await waitFor(() => expect(selection).toBeEnabled())
  fireEvent.click(selection)
}

describe('AI master editor integration', () => {
  it('hands a material proposal from a parent category with no reference to the actual child category and automatic numbering', async () => {
    const raw = JSON.stringify({ schemaVersion: 1, id: '10z00001', category: 'material', name: 'デバッグ素材' })
    vi.mocked(aiAssistApi.propose).mockResolvedValue(result([{ path: `${materialCategory.directory}/v1.debug_material.json`, sourcePath: null, raw, beforeRaw: null, revision: null, summary: 'デバッグ用の素材', issues: [], isNew: true }], 'create'))
    render(<MasterDataEditor />)
    await screen.findByRole('button', { name: 'AIアシスト' })
    const dialog = await openAi()
    fireEvent.change(dialog.getByRole('combobox', { name: 'AI作成カテゴリ' }), { target: { value: 'items' } })
    expect(dialog.getByRole('combobox', { name: 'AI参考ファイル' })).toHaveValue('')
    fireEvent.change(dialog.getByRole('textbox', { name: 'AIへの指示' }), { target: { value: 'デバッグ用のアイテム。\nただのマテリアルアイテム' } })
    fireEvent.click(dialog.getByRole('button', { name: '候補を生成' }))
    const adopt = await dialog.findByRole('button', { name: 'この案を原稿から新規作成へ' })
    expect(dialog.getByText('作成先: アイテム / 素材')).toBeVisible()
    fireEvent.click(dialog.getByText('原稿と差分を確認'))
    await waitFor(() => expect(adopt).toBeEnabled())
    fireEvent.click(adopt)
    const creation = within(await screen.findByRole('dialog', { name: 'マスターファイル作成' }))
    expect(creation.getByRole('combobox', { name: 'カテゴリ' })).toHaveValue(materialCategory.id)
    expect(creation.getByRole('textbox', { name: '新規マスター原稿' })).toHaveValue(raw)
    expect(creation.getByRole('checkbox', { name: 'カテゴリに沿ったアイテムID・ファイル名を自動採番' })).toBeChecked()
    expect(masterDataApi.create).not.toHaveBeenCalled()
    expect(masterDataApi.save).not.toHaveBeenCalled()
    expect(aiAssistApi.propose).toHaveBeenCalledWith({ mode: 'create', categoryId: 'items', instruction: 'デバッグ用のアイテム。\nただのマテリアルアイテム' }, expect.any(AbortSignal))
  })

  it('uses the deepest source category and hands a reviewed draft to normal creation with auto numbering, without saving', async () => {
    vi.mocked(aiAssistApi.propose).mockResolvedValue(result([{ ...proposal(), isNew: true, revision: null, beforeRaw: null }], 'create'))
    await openFile()
    const dialog = await openAi()
    expect(screen.getByRole('dialog', { name: 'AIアシスト' }).parentElement?.parentElement).toBe(document.body)
    expect(dialog.getByRole('combobox', { name: 'AI作成カテゴリ' })).toHaveValue(category.id)
    fireEvent.change(dialog.getByRole('textbox', { name: 'AIへの指示' }), { target: { value: '星の剣を作る' } })
    fireEvent.click(dialog.getByRole('button', { name: '候補を生成' }))
    const adopt = await dialog.findByRole('button', { name: 'この案を原稿から新規作成へ' })
    expect(adopt).toBeDisabled()
    fireEvent.click(dialog.getByText('原稿と差分を確認'))
    await waitFor(() => expect(adopt).toBeEnabled())
    fireEvent.click(adopt)
    const creation = within(await screen.findByRole('dialog', { name: 'マスターファイル作成' }))
    expect(screen.getByRole('dialog', { name: 'マスターファイル作成' }).parentElement?.parentElement).toBe(document.body)
    expect(creation.getByRole('textbox', { name: '新規マスター原稿' })).toHaveValue(changedRaw)
    expect(creation.getByRole('combobox', { name: 'カテゴリ' })).toHaveValue(category.id)
    expect(creation.getByRole('checkbox', { name: 'カテゴリに沿ったアイテムID・ファイル名を自動採番' })).toBeChecked()
    expect(masterDataApi.create).not.toHaveBeenCalled()
    expect(masterDataApi.save).not.toHaveBeenCalled()
    expect(aiAssistApi.propose).toHaveBeenCalledWith(expect.objectContaining({ mode: 'create', categoryId: category.id, raw: original.raw, sourcePath: original.path }), expect.any(AbortSignal))
  })

  it('resets the reference when the creation category changes', async () => {
    await openFile()
    const dialog = await openAi()
    fireEvent.change(dialog.getByRole('combobox', { name: 'AI作成カテゴリ' }), { target: { value: 'items' } })
    expect(dialog.getByRole('combobox', { name: 'AI参考ファイル' })).toHaveValue('')
  })

  it('adopts field completion only with Tab, preserves Undo, and does not save', async () => {
    vi.mocked(aiAssistApi.propose).mockResolvedValue(result([], 'complete', ['星を宿す剣', '月の剣']))
    await openFile()
    const name = screen.getByRole('textbox', { name: '/name' })
    fireEvent.keyDown(name, { key: ' ', ctrlKey: true })
    await screen.findByRole('option', { name: /星を宿す剣/ })
    expect(name).toHaveValue('元の剣')
    expect(aiAssistApi.propose).toHaveBeenCalledWith(expect.objectContaining({ mode: 'complete', raw: original.raw, sourcePath: original.path, fieldPointer: '/name' }), expect.any(AbortSignal))
    fireEvent.keyDown(name, { key: 'Tab' })
    expect(name).toHaveValue('星を宿す剣')
    fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('元の剣')
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('does not trigger during Japanese IME composition, with additional modifiers, or on IDs', async () => {
    await openFile()
    const name = screen.getByRole('textbox', { name: '/name' })
    fireEvent.keyDown(name, { key: ' ', ctrlKey: true, isComposing: true })
    fireEvent.keyDown(name, { key: ' ', ctrlKey: true, keyCode: 229 })
    fireEvent.keyDown(name, { key: ' ', ctrlKey: true, shiftKey: true })
    fireEvent.keyDown(name, { key: ' ', ctrlKey: true, altKey: true })
    fireEvent.keyDown(screen.getByRole('textbox', { name: '/id' }), { key: ' ', ctrlKey: true })
    expect(aiAssistApi.propose).not.toHaveBeenCalled()
  })

  it('adopts newline completion as lore array elements and preserves the previous lines with Undo', async () => {
    vi.mocked(aiAssistApi.propose).mockResolvedValue(result([], 'complete', ['星の光\n旅人を守る']))
    await openFile()
    const lore = await screen.findByRole('textbox', { name: '/lore' })
    fireEvent.keyDown(lore, { key: ' ', ctrlKey: true })
    await screen.findByRole('option', { name: /星の光/ })
    fireEvent.keyDown(lore, { key: 'Tab' })
    expect(lore).toHaveValue('星の光\n旅人を守る')
    fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
    expect(screen.getByRole('textbox', { name: '/lore' })).toHaveValue('1行目\n2行目')
  })

  it('cancels stale completion if the field changes and rejects with Escape', async () => {
    let finish!: (value: AiProposalResponse) => void
    vi.mocked(aiAssistApi.propose).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    await openFile()
    const name = screen.getByRole('textbox', { name: '/name' })
    fireEvent.keyDown(name, { key: ' ', ctrlKey: true })
    await waitFor(() => expect(aiAssistApi.propose).toHaveBeenCalledTimes(1))
    const signal = vi.mocked(aiAssistApi.propose).mock.calls[0][1]!
    fireEvent.change(name, { target: { value: '手入力' } })
    expect(signal.aborted).toBe(true)
    await act(async () => finish(result([], 'complete', ['古い候補'])))
    expect(screen.queryByRole('listbox', { name: 'AI補完候補' })).not.toBeInTheDocument()
    expect(name).toHaveValue('手入力')
    vi.mocked(aiAssistApi.propose).mockResolvedValue(result([], 'complete', ['新しい候補']))
    fireEvent.keyDown(name, { key: ' ', ctrlKey: true })
    await screen.findByRole('option', { name: /新しい候補/ })
    fireEvent.keyDown(name, { key: 'Escape' })
    expect(screen.queryByRole('listbox', { name: 'AI補完候補' })).not.toBeInTheDocument()
    expect(name).toHaveValue('手入力')
  })

  it('rejects a dirty tab before sending batch data to AI', async () => {
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '未保存の剣' } })
    const dialog = await generateBatch()
    expect(await dialog.findByRole('alert')).toHaveTextContent('未保存の編集')
    expect(aiAssistApi.propose).not.toHaveBeenCalled()
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('未保存の剣')
  })

  it('refuses batch adoption after the saved revision changes', async () => {
    await openFile()
    const dialog = await generateBatch()
    await reviewAndSelect(dialog)
    vi.mocked(masterDataApi.file).mockResolvedValue({ ...original, revision: 'external-update' })
    fireEvent.click(dialog.getByRole('button', { name: '選択した候補を下書きへ適用' }))
    expect(await dialog.findByRole('alert')).toHaveTextContent('保存済み原稿が更新')
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('元の剣')
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('does not overwrite a concurrent form edit while adoption is parsing the AI draft', async () => {
    let finishParse!: (value: Awaited<ReturnType<typeof masterDataApi.parse>>) => void
    vi.mocked(masterDataApi.parse).mockImplementationOnce(() => new Promise(resolve => { finishParse = resolve }))
    await openFile()
    const dialog = await generateBatch()
    await reviewAndSelect(dialog)
    fireEvent.click(dialog.getByRole('button', { name: '選択した候補を下書きへ適用' }))
    await waitFor(() => expect(masterDataApi.parse).toHaveBeenCalledTimes(1))
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '並行した手入力' } })
    await act(async () => finishParse({ content: { ...content, name: '星の剣' }, issues: [] }))
    expect(await dialog.findByRole('alert')).toHaveTextContent('生成後に原稿が変更')
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('並行した手入力')
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('opens an unopened batch file, records its proposal as a draft, and keeps Undo on the saved baseline', async () => {
    vi.mocked(aiAssistApi.propose).mockResolvedValue(result([proposal(other)]))
    await openFile()
    const dialog = await generateBatch('元の盾')
    await reviewAndSelect(dialog)
    fireEvent.click(dialog.getByRole('button', { name: '選択した候補を下書きへ適用' }))
    await dialog.findByText('下書きへ適用済み')
    fireEvent.click(dialog.getByRole('button', { name: '閉じる' }))
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('星の盾')
    fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('元の盾')
    expect(screen.getByRole('tab', { name: /元の剣/ })).toBeInTheDocument()
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('keeps the API key in the local settings request only and clears the password field after submission', async () => {
    vi.mocked(aiAssistApi.settings).mockResolvedValue({ ...configured, configured: false, keySource: 'none' })
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: 'AIアシスト' }))
    const dialog = within(await screen.findByRole('dialog', { name: 'AIアシスト' }))
    const key = await dialog.findByLabelText('OpenAI APIキー')
    expect(key).toHaveAttribute('type', 'password')
    fireEvent.change(key, { target: { value: 'synthetic-test-key' } })
    fireEvent.click(dialog.getByRole('button', { name: '接続設定を保存' }))
    await dialog.findByText('APIキー設定済み')
    expect(aiAssistApi.configure).toHaveBeenCalledWith({ apiKey: 'synthetic-test-key', model: 'gpt-6.1-terra' }, expect.any(AbortSignal))
    fireEvent.click(dialog.getByRole('button', { name: '接続設定' }))
    expect(dialog.getByLabelText('OpenAI APIキー')).toHaveValue('')
    expect(window.localStorage.getItem('synthetic-test-key')).toBeNull()
    expect(aiAssistApi.propose).not.toHaveBeenCalled()
  })

  it('cancels an in-flight batch generation and ignores its late response', async () => {
    let finish!: (value: AiProposalResponse) => void
    vi.mocked(aiAssistApi.propose).mockImplementation(() => new Promise(resolve => { finish = resolve }))
    await openFile()
    const dialog = await generateBatch()
    await waitFor(() => expect(aiAssistApi.propose).toHaveBeenCalledTimes(1))
    const signal = vi.mocked(aiAssistApi.propose).mock.calls[0][1]!
    await userEvent.click(dialog.getByRole('button', { name: '生成をキャンセル' }))
    expect(signal.aborted).toBe(true)
    await act(async () => finish(result([proposal()])))
    expect(dialog.queryByRole('region', { name: 'AI生成候補' })).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('元の剣')
  })
})
