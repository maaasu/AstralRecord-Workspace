import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { masterDataApi } from '../api/masterDataApi'
import type { MasterCatalog, MasterDocument, MasterReference } from '../types/masterData'
import { MasterDataEditor } from './MasterDataEditor'

const first: MasterDocument = { path: '20.features.class/v1.first.yml', format: 'yaml', revision: 'r1', raw: 'id: first\nname: 最初のクラス\n', content: { id: 'first', name: '最初のクラス' }, issues: [] }
const second: MasterDocument = { ...first, path: '20.features.class/v1.second.yml', raw: 'id: second\nname: 次のクラス\n', content: { id: 'second', name: '次のクラス' } }
const catalog: MasterCatalog = { root: '/reference-test', backups: '/backups', categories: [{ id: 'class', label: 'クラス', directory: '20.features.class', fileCount: 2, documents: [], fields: [], jsonSchemas: [], templates: [] }] }
const reference = (label: string): MasterReference => ({ path: second.path, pointer: '/unlock/classId', kind: 'class', value: 'first', label })
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
beforeEach(() => {
  vi.restoreAllMocks()
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue(catalog)
  vi.spyOn(masterDataApi, 'files').mockResolvedValue([first, second].map((document) => ({ path: document.path, category: 'class', format: 'yaml', name: (document.content as { name: string }).name, revision: document.revision, size: 40, modifiedUtc: '' })))
  vi.spyOn(masterDataApi, 'file').mockImplementation(async (path) => path === first.path ? first : second)
  vi.spyOn(masterDataApi, 'references').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'render').mockResolvedValue({ raw: second.raw, warnings: [], commentsPreserved: true })
  vi.spyOn(masterDataApi, 'validate').mockResolvedValue({ isValid: true, issues: [] })
})
async function openFirst() {
  const view = render(<MasterDataEditor />)
  fireEvent.click(await screen.findByRole('button', { name: /最初のクラス.*v1.first/ }))
  await screen.findByRole('textbox', { name: '/name' })
  await waitFor(() => expect(screen.getByRole('button', { name: 'ファイルを再読込' })).toBeEnabled())
  return view
}
const toggleReferences = () => fireEvent.click(screen.getByRole('button', { name: /^参照一覧/ }))
const panel = () => within(screen.getByRole('region', { name: '保存済みデータの参照元' }))

describe('on-demand references', () => {
  it('does not fetch for file opens or draft edits, and reuses one pending or completed request on reopen', async () => {
    const request = deferred<MasterReference[]>()
    vi.mocked(masterDataApi.references).mockReturnValue(request.promise)
    await openFirst()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '未保存の名前' } })
    expect(masterDataApi.references).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '参照一覧' })).toHaveAttribute('aria-expanded', 'false')
    toggleReferences()
    expect(panel().getByRole('status')).toHaveTextContent('読み込んでいます')
    expect(panel().queryByText('検出された参照元はありません。')).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '/name' })).toBeEnabled()
    toggleReferences()
    toggleReferences()
    expect(masterDataApi.references).toHaveBeenCalledTimes(1)
    await act(async () => request.resolve([reference('参照するクラス')]))
    expect(screen.getByRole('button', { name: '参照一覧 (1)' })).toBeInTheDocument()
    expect(panel().getByRole('cell', { name: /参照するクラス/ })).toBeInTheDocument()
    toggleReferences()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: 'さらに編集' } })
    toggleReferences()
    expect(masterDataApi.references).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('さらに編集')
  })

  it('distinguishes failure from zero references and retries or refreshes without reloading the draft', async () => {
    vi.mocked(masterDataApi.references).mockRejectedValueOnce(new Error('一時的な接続失敗')).mockResolvedValueOnce([]).mockResolvedValueOnce([reference('新しい参照元')])
    await openFirst()
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '保持する原稿' } })
    toggleReferences()
    expect(await panel().findByRole('alert')).toHaveTextContent('一時的な接続失敗')
    expect(screen.getByRole('button', { name: '参照一覧' })).toBeInTheDocument()
    expect(panel().queryByText('検出された参照元はありません。')).not.toBeInTheDocument()
    toggleReferences()
    toggleReferences()
    expect(masterDataApi.references).toHaveBeenCalledTimes(1)
    fireEvent.click(panel().getByRole('button', { name: '参照元を再試行' }))
    await screen.findByRole('button', { name: '参照一覧 (0)' })
    expect(panel().getByText('検出された参照元はありません。')).toBeInTheDocument()
    fireEvent.click(panel().getByRole('button', { name: '参照元を更新' }))
    expect(await panel().findByRole('cell', { name: /新しい参照元/ })).toBeInTheDocument()
    expect(masterDataApi.file).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('保持する原稿')
  })

  it('discards an older response after reloading the same saved revision', async () => {
    const old = deferred<MasterReference[]>()
    const latest = deferred<MasterReference[]>()
    vi.mocked(masterDataApi.references).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    await openFirst()
    toggleReferences()
    fireEvent.click(screen.getByRole('button', { name: 'ファイルを再読込' }))
    await waitFor(() => expect(masterDataApi.references).toHaveBeenCalledTimes(2))
    await act(async () => latest.resolve([reference('最新の参照元')]))
    await act(async () => old.resolve([reference('古い参照元')]))
    expect(panel().getByRole('cell', { name: /最新の参照元/ })).toBeInTheDocument()
    expect(panel().queryByText('古い参照元')).not.toBeInTheDocument()
  })

  it('ignores failure from an invalidated request after a successful new request', async () => {
    const old = deferred<MasterReference[]>()
    vi.mocked(masterDataApi.references).mockReturnValueOnce(old.promise).mockResolvedValueOnce([reference('有効な参照元')])
    await openFirst()
    toggleReferences()
    fireEvent.click(screen.getByRole('button', { name: 'ファイルを再読込' }))
    await panel().findByRole('cell', { name: /有効な参照元/ })
    await act(async () => old.reject(new Error('古い通信の失敗')))
    expect(panel().queryByRole('alert')).not.toBeInTheDocument()
    expect(panel().getByRole('cell', { name: /有効な参照元/ })).toBeInTheDocument()
  })

  it('invalidates results on list refresh but defers fetching while the editor is inactive', async () => {
    vi.mocked(masterDataApi.references).mockResolvedValueOnce([reference('更新前')]).mockResolvedValueOnce([reference('更新後')])
    const view = await openFirst()
    toggleReferences()
    await panel().findByRole('cell', { name: /更新前/ })
    view.rerender(<MasterDataEditor active={false} />)
    fireEvent.click(screen.getByRole('button', { name: '一覧と定義を再読込' }))
    await waitFor(() => expect(masterDataApi.files).toHaveBeenCalledTimes(2))
    expect(masterDataApi.references).toHaveBeenCalledTimes(1)
    view.rerender(<MasterDataEditor active />)
    expect(await panel().findByRole('cell', { name: /更新後/ })).toBeInTheDocument()
    expect(masterDataApi.references).toHaveBeenCalledTimes(2)
  })

  it('invalidates a hidden tab after another file is saved even if list refresh fails', async () => {
    vi.mocked(masterDataApi.references).mockResolvedValueOnce([reference('更新前')]).mockResolvedValueOnce([reference('別タブ保存後')])
    vi.spyOn(masterDataApi, 'save').mockResolvedValue({ ...second, revision: 'r2' })
    await openFirst()
    toggleReferences()
    await panel().findByRole('cell', { name: /更新前/ })
    fireEvent.click(screen.getByRole('button', { name: /次のクラス.*v1.second/ }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('次のクラス'))
    await waitFor(() => expect(screen.getByRole('textbox', { name: '/name' })).toBeEnabled())
    fireEvent.change(screen.getByRole('textbox', { name: '/name' }), { target: { value: '変更したクラス' } })
    vi.mocked(masterDataApi.files).mockRejectedValueOnce(new Error('一覧だけ取得失敗'))
    fireEvent.click(screen.getByRole('button', { name: '保存 (Ctrl+S)' }))
    await screen.findByText(/保存済みです。一覧を再読込できませんでした/)
    expect(masterDataApi.references).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('tab', { name: '最初のクラス' }))
    expect(await panel().findByRole('cell', { name: /別タブ保存後/ })).toBeInTheDocument()
    expect(masterDataApi.references).toHaveBeenCalledTimes(2)
  })

  it('keeps closed reference panels lazy after explicit document reload', async () => {
    await openFirst()
    toggleReferences()
    await screen.findByRole('button', { name: '参照一覧 (0)' })
    toggleReferences()
    fireEvent.click(screen.getByRole('button', { name: 'ファイルを再読込' }))
    await waitFor(() => expect(masterDataApi.file).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByRole('button', { name: 'ファイルを再読込' })).toBeEnabled())
    expect(masterDataApi.references).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: '参照一覧' })).toBeInTheDocument()
    toggleReferences()
    await screen.findByRole('button', { name: '参照一覧 (0)' })
    expect(masterDataApi.references).toHaveBeenCalledTimes(2)
  })

  it('invalidates other tabs when a failed initial file load is explicitly retried', async () => {
    vi.mocked(masterDataApi.references).mockResolvedValueOnce([reference('再読込前')]).mockResolvedValueOnce([reference('再読込後')])
    await openFirst()
    toggleReferences()
    await panel().findByRole('cell', { name: /再読込前/ })
    vi.mocked(masterDataApi.file).mockRejectedValueOnce(new Error('初回の通信失敗'))
    fireEvent.click(screen.getByRole('button', { name: /次のクラス.*v1.second/ }))
    await screen.findByText('初回の通信失敗')
    fireEvent.click(screen.getByRole('button', { name: 'ファイルを再読込' }))
    await screen.findByRole('textbox', { name: '/name' })
    expect(masterDataApi.references).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('tab', { name: '最初のクラス' }))
    expect(await panel().findByRole('cell', { name: /再読込後/ })).toBeInTheDocument()
    expect(masterDataApi.references).toHaveBeenCalledTimes(2)
  })

  it('leaves server-side deletion protection effective without fetching the reference panel', async () => {
    vi.spyOn(masterDataApi, 'delete').mockRejectedValue(new Error('参照されているため削除できません'))
    await openFirst()
    fireEvent.click(screen.getByRole('button', { name: '削除' }))
    await screen.findByText('参照されているため削除できません')
    expect(masterDataApi.delete).toHaveBeenCalledWith(first.path, first.revision)
    expect(masterDataApi.references).not.toHaveBeenCalled()
    expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('最初のクラス')
  })
})
