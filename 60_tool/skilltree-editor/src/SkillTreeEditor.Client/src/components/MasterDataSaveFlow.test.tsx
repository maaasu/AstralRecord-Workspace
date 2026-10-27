import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { masterDataApi } from '../api/masterDataApi'
import { MasterDataEditor } from './MasterDataEditor'
import type { MasterCatalog, MasterDocument } from '../types/masterData'

const original: MasterDocument = { path: '20.features.class/v1.sample.yml', format: 'yaml', revision: 'before', raw: 'id: sample\nname: 検証クラス\nsettings:\n  name: 詳細\n', content: { id: 'sample', name: '検証クラス', settings: { name: '詳細' } }, issues: [] }
const catalog: MasterCatalog = { root: '/save-flow-fixture', backups: '/backups', categories: [{ id: 'class', label: 'クラス', directory: '20.features.class', fileCount: 1, fields: [], documents: [], jsonSchemas: [], templates: [] }] }

beforeEach(() => {
  vi.restoreAllMocks()
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue(catalog)
  vi.spyOn(masterDataApi, 'files').mockResolvedValue([{ path: original.path, category: 'class', format: 'yaml', name: '検証クラス', id: 'sample', revision: 'before', size: 80, modifiedUtc: '' }])
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'file').mockResolvedValue(original)
  vi.spyOn(masterDataApi, 'references').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'render').mockResolvedValue({ raw: original.raw.replace('検証クラス', '変更済み'), commentsPreserved: true, warnings: [] })
  vi.spyOn(masterDataApi, 'validate').mockResolvedValue({ isValid: true, issues: [] })
  vi.spyOn(masterDataApi, 'save').mockResolvedValue({ ...original, revision: 'after', raw: original.raw.replace('検証クラス', '変更済み'), content: { ...original.content as object, name: '変更済み' } })
})

async function openFile() {
  render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /検証クラス/ }))
  await screen.findByRole('textbox', { name: '/name' })
  await waitFor(() => expect(screen.getByRole('button', { name: '原稿 YAML' })).toBeEnabled())
}

function saveKey(repeat = false) {
  const event = new KeyboardEvent('keydown', { key: 's', ctrlKey: true, bubbles: true, cancelable: true, repeat })
  window.dispatchEvent(event)
  return event
}

describe('saving without losing the editing context', () => {
  it('consumes Ctrl+S without writing an unchanged document', async () => {
    await openFile()
    act(() => expect(saveKey().defaultPrevented).toBe(true))
    expect(masterDataApi.render).not.toHaveBeenCalled()
    expect(masterDataApi.validate).not.toHaveBeenCalled()
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('saves only once for repeated shortcuts and shows validation and write progress', async () => {
    let finishRender!: (value: Awaited<ReturnType<typeof masterDataApi.render>>) => void
    let finishValidation!: (value: Awaited<ReturnType<typeof masterDataApi.validate>>) => void
    let finishSave!: (value: MasterDocument) => void
    vi.mocked(masterDataApi.render).mockImplementationOnce(() => new Promise(resolve => { finishRender = resolve }))
    vi.mocked(masterDataApi.validate).mockImplementationOnce(() => new Promise(resolve => { finishValidation = resolve }))
    vi.mocked(masterDataApi.save).mockImplementationOnce(() => new Promise(resolve => { finishSave = resolve }))
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '変更済み' } })
    act(() => { expect(saveKey().defaultPrevented).toBe(true); expect(saveKey().defaultPrevented).toBe(true) })
    expect(masterDataApi.render).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '原稿を準備中…' })).toBeDisabled()
    act(() => expect(saveKey(true).defaultPrevented).toBe(true))
    await act(async () => finishRender({ raw: 'changed', commentsPreserved: true, warnings: [] }))
    expect(screen.getByRole('button', { name: '検証中…' })).toBeDisabled()
    await act(async () => finishValidation({ isValid: true, issues: [] }))
    expect(screen.getByRole('button', { name: '保存中…' })).toBeDisabled()
    act(() => expect(saveKey().defaultPrevented).toBe(true))
    await act(async () => finishSave({ ...original, revision: 'after', raw: 'changed', content: { id: 'sample', name: '変更済み' } }))
    expect(masterDataApi.save).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).toBeDisabled()
    expect(screen.getByText('v1.sample.yml を保存しました。')).toBeVisible()
    act(() => saveKey())
    expect(masterDataApi.save).toHaveBeenCalledTimes(1)
  })

  it('takes a failed form save to the first invalid field while preserving the draft', async () => {
    vi.mocked(masterDataApi.validate).mockResolvedValue({ isValid: false, issues: [
      { severity: 'error', code: 'JSON_SCHEMA', path: '', message: 'Some properties did not match required schema' },
      { severity: 'error', code: 'JSON_SCHEMA', path: '/settings', message: 'Some properties did not match required schema' },
      { severity: 'error', code: 'VALUE', path: '/settings/name', message: '詳細名を修正してください' },
    ] })
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '未保存の名前' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: '/settings/name' })).toHaveFocus())
    expect(masterDataApi.save).not.toHaveBeenCalled()
    expect(screen.getByRole('tab', { name: '未保存の名前 · 未保存' })).toBeVisible()
    expect(screen.getByText('検証エラーがあるため保存しませんでした。')).toBeVisible()
  })

  it('focuses validation feedback when the invalid field does not exist', async () => {
    vi.mocked(masterDataApi.validate).mockResolvedValue({ isValid: false, issues: [
      { severity: 'error', code: 'JSON_SCHEMA', path: '', message: 'Some properties did not match required schema' },
      { severity: 'error', code: 'REQUIRED', path: '/missing', message: '必須項目がありません' },
    ] })
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '未保存' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(document.activeElement?.tagName).toBe('SUMMARY'))
    expect(document.activeElement).toHaveTextContent('検証結果')
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('未保存')
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('keeps raw mode and text intact after a validation error', async () => {
    vi.mocked(masterDataApi.validate).mockResolvedValue({ isValid: false, issues: [{ severity: 'error', code: 'SYNTAX', path: '/', message: '原稿を修正してください' }] })
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '原稿 YAML' }))
    const raw = await screen.findByRole('textbox', { name: 'マスター原稿' })
    fireEvent.change(raw, { target: { value: 'name: [' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(document.activeElement?.tagName).toBe('SUMMARY'))
    expect(raw).toHaveValue('name: [')
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it.each(['', '/settings'])('focuses feedback for missing required properties reported on parent %s', async (path) => {
    vi.mocked(masterDataApi.validate).mockResolvedValue({ isValid: false, issues: [
      { severity: 'error', code: 'JSON_SCHEMA', path, message: 'Required properties are missing' },
    ] })
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '未保存' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await waitFor(() => expect(document.activeElement?.tagName).toBe('SUMMARY'))
    expect(document.activeElement).toHaveTextContent('検証結果')
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('未保存')
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('suppresses browser save in a dialog and keeps unsaved edits after Escape', async () => {
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '未保存' } })
    fireEvent.click(screen.getByRole('button', { name: '差分' }))
    const dialog = await screen.findByRole('dialog')
    act(() => expect(saveKey().defaultPrevented).toBe(true))
    expect(masterDataApi.save).not.toHaveBeenCalled()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('未保存')
  })

  it('unlocks the save action after a failed request and allows retry', async () => {
    vi.mocked(masterDataApi.save).mockRejectedValueOnce(new Error('通信失敗'))
    await openFile()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '変更済み' } })
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await screen.findByText('通信失敗')
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('変更済み')
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await screen.findByText('v1.sample.yml を保存しました。')
    expect(masterDataApi.save).toHaveBeenCalledTimes(2)
  })

  it('protects and saves comment-only YAML edits after returning to the form', async () => {
    const commented = `${original.raw}# 編集したコメント\n`
    vi.mocked(masterDataApi.render).mockResolvedValueOnce({ raw: original.raw, commentsPreserved: true, warnings: [] })
      .mockResolvedValue({ raw: commented, commentsPreserved: true, warnings: [] })
    vi.spyOn(masterDataApi, 'parse').mockResolvedValue({ content: original.content, issues: [] })
    vi.mocked(masterDataApi.save).mockResolvedValue({ ...original, raw: commented, revision: 'after' })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await openFile()
    fireEvent.click(screen.getByRole('button', { name: '原稿 YAML' }))
    fireEvent.change(await screen.findByRole('textbox', { name: 'マスター原稿' }), { target: { value: commented } })
    fireEvent.click(screen.getByRole('button', { name: '日本語フォーム' }))
    await screen.findByRole('textbox', { name: '/name' })
    await waitFor(() => expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).toBeEnabled())
    expect(screen.getByRole('tab', { name: '検証クラス · 未保存' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'v1.sample.yml のタブを閉じる' }))
    expect(confirm).toHaveBeenCalled()
    expect(screen.getByRole('textbox', { name: '/name' })).toBeVisible()
    act(() => saveKey())
    await screen.findByText('v1.sample.yml を保存しました。')
    expect(masterDataApi.render).toHaveBeenLastCalledWith(original.path, original.content, commented)
    expect(masterDataApi.save).toHaveBeenCalledWith(original.path, commented, 'before')
    expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).toBeDisabled()
  })
})
