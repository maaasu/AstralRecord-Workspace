import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import { MasterDataEditor } from './MasterDataEditor'
import type { MasterCatalog, MasterDocument } from '../types/masterData'

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
})
