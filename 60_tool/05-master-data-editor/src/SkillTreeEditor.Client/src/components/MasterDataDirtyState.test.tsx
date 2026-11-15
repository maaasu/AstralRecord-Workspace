import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { masterDataApi } from '../api/masterDataApi'
import { readMasterDraft } from '../state/masterDataRecovery'
import type { MasterCatalog, MasterDocument } from '../types/masterData'
import { MasterDataEditor } from './MasterDataEditor'

const original: MasterDocument = { path: '20.features.class/v1.dirty_fixture.yml', format: 'yaml', revision: 'before', raw: 'id: dirty-fixture\nname: 変更判定クラス\norder: 1\n', content: { id: 'dirty-fixture', name: '変更判定クラス', order: 1 }, issues: [] }
const catalog: MasterCatalog = { root: '/dirty-state-fixture', backups: '/backups', categories: [{ id: 'class', label: 'クラス', directory: '20.features.class', fileCount: 1, fields: [], documents: [], jsonSchemas: [], templates: [] }] }

beforeEach(() => {
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue(catalog)
  vi.spyOn(masterDataApi, 'files').mockResolvedValue([{ path: original.path, category: 'class', format: 'yaml', name: '変更判定クラス', id: 'dirty-fixture', revision: 'before', size: 80, modifiedUtc: '' }])
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'file').mockResolvedValue(original)
  vi.spyOn(masterDataApi, 'references').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'render').mockResolvedValue({ raw: original.raw.replace('変更判定クラス', '編集中'), commentsPreserved: true, warnings: [] })
  vi.spyOn(masterDataApi, 'validate').mockResolvedValue({ isValid: true, issues: [] })
  vi.spyOn(masterDataApi, 'save').mockResolvedValue({ ...original, revision: 'after', raw: original.raw.replace('変更判定クラス', '編集中'), content: { ...original.content as object, name: '編集中' } })
})
afterEach(() => vi.restoreAllMocks())

async function open() {
  const view = render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /変更判定クラス/ }))
  await screen.findByRole('textbox', { name: '/name' })
  await waitFor(() => expect(screen.getByRole('button', { name: '原稿 YAML' })).toBeEnabled())
  return view
}

describe('dirty state without repeated whole-document serialization', () => {
  it('reuses a large unchanged document comparison across parent renders', async () => {
    const large = { ...original, content: { ...original.content as object, entries: Array.from({ length: 10000 }, (_, id) => ({ id, name: `検証用エントリ ${id}`, description: '大きい原稿の変更判定を検証するための説明文'.repeat(3) })) } }
    vi.mocked(masterDataApi.file).mockResolvedValue(large)
    const bytes = new TextEncoder().encode(JSON.stringify(large.content)).length
    const view = await open()
    const stringify = vi.spyOn(JSON, 'stringify')
    const started = performance.now()
    for (let i = 0; i < 100; i++) view.rerender(<MasterDataEditor active={i % 2 === 0} />)
    const elapsedMs = performance.now() - started
    const documentSerializations = stringify.mock.calls.filter(([value]) => value && typeof value === 'object' && 'id' in value && value.id === 'dirty-fixture').length
    console.info(JSON.stringify({ bytes, parentRenders: 100, documentSerializations, elapsedMs: Math.round(elapsedMs) }))
    expect(documentSerializations).toBe(0)
    expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).toBeDisabled()
    expect(masterDataApi.file).toHaveBeenCalledTimes(1)
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('updates dirty state for edits, undo, redo, reverting a value, and a successful save', async () => {
    await open()
    const name = screen.getByRole('textbox', { name: '/name' })
    const save = () => screen.getByRole('button', { name: '保存 (Ctrl+S)' })
    expect(save()).toBeDisabled()
    fireEvent.change(name, { target: { value: '編集中' } })
    expect(save()).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
    expect(name).toHaveValue('変更判定クラス')
    expect(save()).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'やり直す Ctrl+Y' }))
    expect(name).toHaveValue('編集中')
    expect(save()).toBeEnabled()
    fireEvent.change(name, { target: { value: '変更判定クラス' } })
    expect(save()).toBeDisabled()
    fireEvent.change(name, { target: { value: '編集中' } })
    fireEvent.click(save())
    await screen.findByText('v1.dirty_fixture.yml を保存しました。')
    expect(save()).toBeDisabled()
    expect(readMasterDraft(catalog.root, original.path)).toBeNull()
    fireEvent.change(name, { target: { value: '変更判定クラス' } })
    expect(save()).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '元に戻す Ctrl+Z' }))
    expect(name).toHaveValue('編集中')
    expect(save()).toBeDisabled()
  })

  it('preserves invalid numeric input even though parsed content stays unchanged', async () => {
    await open()
    fireEvent.change(screen.getByRole('textbox', { name: '/order' }), { target: { value: '-' } })
    expect(screen.getByRole('tab', { name: '変更判定クラス · 未保存' })).toBeInTheDocument()
    expect(readMasterDraft(catalog.root, original.path)?.invalidValues).toEqual({ '/order': '-' })
    expect(screen.getByRole('button', { name: '保存 (Ctrl+S)' })).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: '/order' }), { target: { value: '1' } })
    expect(screen.getByRole('tab', { name: '変更判定クラス' })).toBeInTheDocument()
    expect(readMasterDraft(catalog.root, original.path)).toBeNull()
  })
})
