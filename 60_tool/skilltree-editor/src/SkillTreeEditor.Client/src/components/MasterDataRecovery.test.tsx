import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import { listMasterDrafts, readMasterDraft, writeMasterDraft } from '../state/masterDataRecovery'
import type { MasterCatalog, MasterDocument } from '../types/masterData'
import { MasterDataEditor } from './MasterDataEditor'

const document: MasterDocument = { path: '20.features.class/v1.old.yml', format: 'yaml', revision: 'r1', raw: 'id: old\nname: 元クラス\n', content: { id: 'old', name: '元クラス', order: 1 }, issues: [] }
const catalog: MasterCatalog = { root: '/workspace/filebase', backups: '/backups', categories: [{ id: 'class', label: 'クラス', directory: '20.features.class', fileCount: 1, documents: [], fields: [], jsonSchemas: [], templates: [] }] }

beforeEach(() => {
  vi.restoreAllMocks()
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue(catalog)
  vi.spyOn(masterDataApi, 'files').mockResolvedValue([{ path: document.path, category: 'class', name: '元クラス', id: 'old', format: 'yaml', size: 20, modifiedUtc: '', revision: 'r1' }])
  vi.spyOn(masterDataApi, 'file').mockResolvedValue(document)
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'references').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'validate').mockResolvedValue({ isValid: true, issues: [] })
  vi.spyOn(masterDataApi, 'render').mockResolvedValue({ raw: 'id: old\nname: 下書き\n', warnings: [], commentsPreserved: true })
})

async function open() {
  const view = render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /元クラス.*old/ }))
  await screen.findByRole('textbox', { name: '/name' })
  await waitFor(() => expect(screen.getByRole('button', { name: '原稿 YAML' })).not.toBeDisabled())
  return view
}

function seed(revision = 'r1', invalidValues: Record<string, string> = {}) {
  writeMasterDraft(catalog.root, { version: 1, document: { ...document, revision }, draft: { source: 'form', raw: document.raw, content: { ...document.content as object, name: '下書き' } }, formPath: '', invalidValues, updatedAt: new Date().toISOString() })
}

describe('draft recovery and connection recovery', () => {
  it('recovers unsaved forms after remount without writing to Filebase', async () => {
    const save = vi.spyOn(masterDataApi, 'save')
    const first = await open()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '下書き' } })
    await waitFor(() => expect(readMasterDraft(catalog.root, document.path)?.draft.content).toMatchObject({ name: '下書き' }))
    first.unmount()
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '下書き' }))
    await screen.findByRole('region', { name: '未保存の下書きの復元' })
    expect(screen.getByRole('textbox', { name: '/name' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '下書きを復元' }))
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('下書き')
    expect(screen.getByRole('tab', { name: /下書き · 未保存/ })).toBeInTheDocument()
    expect(save).not.toHaveBeenCalled()
  })

  it('retains incomplete numeric text across reload and blocks saving until corrected', async () => {
    seed('r1', { '/order': '-' })
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '下書き' }))
    fireEvent.click(await screen.findByRole('button', { name: '下書きを復元' }))
    expect(screen.getByRole('textbox', { name: '/order' })).toHaveValue('-')
    expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: '/order' }), { target: { value: '2' } })
    expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).not.toBeDisabled()
  })

  it('requires reviewing the latest disk content before saving a changed-revision draft', async () => {
    seed('older')
    const save = vi.spyOn(masterDataApi, 'save').mockResolvedValue({ ...document, revision: 'r2', raw: 'id: old\nname: 下書き\n', content: { id: 'old', name: '下書き' } })
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '下書き' }))
    fireEvent.click(await screen.findByRole('button', { name: '下書きを復元' }))
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await screen.findByRole('dialog', { name: '保存前の差分' })
    expect(save).not.toHaveBeenCalled()
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '閉じる' }))
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(save).toHaveBeenCalledWith(document.path, 'id: old\nname: 下書き\n', 'r1'))
    await waitFor(() => expect(readMasterDraft(catalog.root, document.path)).toBeNull())
  })

  it('keeps the draft when discard is cancelled, and removes it after confirmation', async () => {
    seed()
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '下書き' }))
    const discard = await screen.findByRole('button', { name: '下書きを破棄' })
    vi.mocked(window.confirm).mockReturnValue(false)
    fireEvent.click(discard)
    expect(readMasterDraft(catalog.root, document.path)).not.toBeNull()
    vi.mocked(window.confirm).mockReturnValue(true)
    fireEvent.click(discard)
    expect(readMasterDraft(catalog.root, document.path)).toBeNull()
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('元クラス')
  })

  it('retains mandatory diff review after a recovered draft is reloaded again', async () => {
    seed('older')
    const save = vi.spyOn(masterDataApi, 'save')
    const first = render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '下書き' }))
    fireEvent.click(await screen.findByRole('button', { name: '下書きを復元' }))
    await waitFor(() => expect(readMasterDraft(catalog.root, document.path)?.requiresDiffReview).toBe(true))
    expect(readMasterDraft(catalog.root, document.path)?.document.revision).toBe('r1')
    first.unmount()
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '下書き' }))
    fireEvent.click(await screen.findByRole('button', { name: '下書きを復元' }))
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await screen.findByRole('dialog', { name: '保存前の差分' })
    expect(save).not.toHaveBeenCalled()
  })

  it('isolates recovery by workspace and ignores damaged entries', () => {
    seed()
    expect(listMasterDrafts('/other/workspace')).toEqual([])
    const key = window.sessionStorage.key(0)!
    window.sessionStorage.setItem(key, '{broken')
    expect(listMasterDrafts(catalog.root)).toEqual([])
  })

  it('warns if browser storage fails while allowing editing', async () => {
    await open()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '下書き' } })
    expect(await screen.findByText(/ブラウザへ一時下書きを保存できません/)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('下書き')
  })

  it('clears a confirmed closed-tab draft', async () => {
    await open()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '下書き' } })
    fireEvent.click(screen.getByRole('button', { name: /v1.old.yml のタブを閉じる/ }))
    expect(readMasterDraft(catalog.root, document.path)).toBeNull()
  })

  it('uses the API reference kinds to show Japanese names', async () => {
    vi.mocked(masterDataApi.references).mockResolvedValue([
      { path: '44.features.fishing.fish/fish.yml', pointer: '/ref', value: 'fish', kind: 'fishing_fish' },
      { path: '80.shared.loot/table/table.yml', pointer: '/ref', value: 'table', kind: 'loot_table' },
      { path: '41.features.mob_spawner/spawner.yml', pointer: '/ref', value: 'spawner', kind: 'mob_spawner' },
    ])
    await open()
    fireEvent.click(await screen.findByRole('button', { name: '参照一覧 (3)' }))
    expect(screen.getByRole('cell', { name: '釣り魚' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'ドロップ報酬テーブル' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'モブスポナー' })).toBeInTheDocument()
  })

  it('retries initial loading after a connection failure and clears the error', async () => {
    vi.mocked(masterDataApi.catalog).mockRejectedValueOnce(new Error('接続失敗')).mockResolvedValue(catalog)
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '再試行' }))
    await screen.findByRole('button', { name: /元クラス.*old/ })
    expect(screen.queryByText('接続失敗')).not.toBeInTheDocument()
  })

  it('offers an existing draft after retrying a failed initial file load', async () => {
    seed()
    vi.mocked(masterDataApi.file).mockRejectedValueOnce(new ApiError('接続失敗', 0)).mockResolvedValue(document)
    render(<MasterDataEditor />)
    fireEvent.click(await screen.findByRole('button', { name: '下書き' }))
    await screen.findByText('ファイルを読み込めませんでした')
    fireEvent.click(screen.getByRole('button', { name: 'ファイルを再読込' }))
    await screen.findByRole('textbox', { name: '/name' })
    expect(readMasterDraft(catalog.root, document.path)).not.toBeNull()
    expect(screen.getByRole('button', { name: '下書きを復元' })).toBeInTheDocument()
  })

  it('preserves edits during save conflicts and changes the revision only after explicit adoption', async () => {
    const save = vi.spyOn(masterDataApi, 'save').mockRejectedValueOnce(new ApiError('保存競合', 409)).mockResolvedValue({ ...document, revision: 'r3', raw: 'id: old\nname: 下書き\n', content: { id: 'old', name: '下書き' } })
    vi.mocked(masterDataApi.file).mockResolvedValueOnce(document).mockResolvedValue({ ...document, revision: 'r2', raw: 'id: old\nname: 別の編集\n', content: { id: 'old', name: '別の編集' } })
    await open()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '下書き' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await screen.findByRole('region', { name: '保存競合の解決' })
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('下書き')
    fireEvent.click(screen.getByRole('button', { name: 'この編集を維持して最新を基準にする' }))
    expect(readMasterDraft(catalog.root, document.path)?.requiresDiffReview).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await screen.findByRole('dialog', { name: '保存前の差分' })
    expect(save).toHaveBeenCalledTimes(1)
    expect(screen.getByText('name: 別の編集')).toBeInTheDocument()
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '閉じる' }))
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(save).toHaveBeenLastCalledWith(document.path, 'id: old\nname: 下書き\n', 'r2'))
  })
})
