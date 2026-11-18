import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App'
import { editorApi } from './api/editorApi'
import { masterDataApi } from './api/masterDataApi'
import { toolJobApi } from './api/toolJobApi'
import type { MasterDocument } from './types/masterData'

const first: MasterDocument = {
  path: 'skills/a.yml', format: 'yaml', revision: 'first', raw: 'name: First\n',
  content: { name: 'First' }, issues: [],
}
const second: MasterDocument = { ...first, path: 'skills/b.yml', revision: 'second', raw: 'name: Second\n', content: { name: 'Second' } }
const openFile = (name: string) => fireEvent.click(screen.getByRole('button', { name: new RegExp(name) }))
const waitForEditor = () => screen.findByRole('heading', { name: 'マスターデータ' }, { timeout: 5_000 })

beforeEach(() => {
  window.history.replaceState(null, '', '/?mobile=android')
  vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({
    root: '/filebase', backups: '/backups',
    categories: [{ id: 'skills', label: 'Skills', directory: 'skills', fileCount: 2, fields: [], documents: [], jsonSchemas: [], templates: [] }],
  })
  vi.spyOn(masterDataApi, 'files').mockResolvedValue([first, second].map(document => ({
    path: document.path, category: 'skills', name: (document.content as { name: string }).name,
    revision: document.revision, format: 'yaml', size: 12, modifiedUtc: '',
  })))
  vi.spyOn(masterDataApi, 'filterIndex').mockResolvedValue({ sources: [], skillTags: [], tags: [] })
  vi.spyOn(masterDataApi, 'candidates').mockResolvedValue([])
  vi.spyOn(masterDataApi, 'file').mockImplementation(async path => structuredClone(path === first.path ? first : second))
  vi.spyOn(editorApi, 'metadata').mockResolvedValue({} as Awaited<ReturnType<typeof editorApi.metadata>>)
  vi.spyOn(toolJobApi, 'capabilities').mockResolvedValue({ enabled: false, reason: null, workspaceRoot: '/desktop', activeRunId: null })
})

afterEach(() => {
  window.history.replaceState(null, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  delete window.astralMobileSessionExpired
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('keeps the folder, list, and editor panes available while pinning tabs and editing on Android', async () => {
  const postMessage = vi.fn()
  vi.stubGlobal('chrome', { webview: { postMessage } })
  render(<App />)
  await waitForEditor()
  const folders = screen.getByRole('region', { name: 'フォルダ一覧' })
  const results = screen.getByRole('region', { name: 'ファイル検索と結果' })
  const editor = screen.getByRole('region', { name: 'マスターの編集領域' })
  expect(folders).toBeVisible()
  expect(results).toBeVisible()
  expect(editor).toBeVisible()
  expect(screen.getByRole('separator', { name: 'フォルダと検索結果の幅を変更' })).toBeVisible()
  expect(screen.getByRole('separator', { name: 'ファイル一覧と編集領域の幅を変更' })).toBeVisible()
  expect(screen.queryByRole('button', { name: 'Android接続' })).not.toBeInTheDocument()
  expect(screen.queryByText('AIアシスト')).not.toBeInTheDocument()
  expect(editorApi.metadata).not.toHaveBeenCalled()
  expect(toolJobApi.capabilities).not.toHaveBeenCalled()
  expect(window.astralEditorBack?.()).toBe(false)

  openFile('First')
  const firstName = await screen.findByRole('textbox', { name: '/name' })
  expect(folders).toBeVisible()
  expect(results).toBeVisible()
  expect(screen.getByRole('button', { name: '保存' }).closest('.master-main-toolbar')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'a.yml のタブを保持' }))
  expect(screen.getByRole('tab', { name: 'First' })).toBeInTheDocument()

  openFile('Second')
  const secondName = await screen.findByRole('textbox', { name: '/name' })
  fireEvent.change(secondName, { target: { value: 'Edited' } })
  expect(window.astralEditorHasUnsavedChanges?.()).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'b.yml のタブを閉じる' }))
  expect(await screen.findByRole('dialog', { name: '未保存の編集内容' })).toBeInTheDocument()
  act(() => expect(window.astralEditorBack?.()).toBe(true))
  expect(secondName).toHaveValue('Edited')
  expect(firstName).toHaveValue('First')
  expect(window.astralEditorBack?.()).toBe(false)
  expect(window.astralEditorHasUnsavedChanges?.()).toBe(true)
  expect(secondName).toBeVisible()

  const toggle = screen.getByRole('button', { name: 'フォルダとファイル一覧' })
  fireEvent.click(toggle)
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  expect(folders).not.toBeVisible()
  expect(results).not.toBeVisible()
  expect(secondName).toHaveValue('Edited')
  act(() => expect(window.astralEditorBack?.()).toBe(true))
  expect(toggle).toHaveAttribute('aria-expanded', 'true')
  expect(folders).toBeVisible()
  expect(results).toBeVisible()
  expect(secondName).toHaveValue('Edited')
})

it('supports PC quick search in the Android header while retaining the filtered list', async () => {
  render(<App />)
  await waitForEditor()
  const listQuery = screen.getByRole('textbox', { name: 'マスター検索' })
  fireEvent.change(listQuery, { target: { value: 'First' } })
  const quick = screen.getByRole('combobox', { name: 'マスターをクイック検索' })
  fireEvent.focus(quick)
  fireEvent.change(quick, { target: { value: 'Second' } })
  fireEvent.click(await screen.findByRole('option', { name: /Second/ }))
  await waitFor(() => expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('Second'))
  expect(listQuery).toHaveValue('First')
})

it('restores a persisted Android draft after a fresh editor mounts', async () => {
  const view = render(<App />)
  await waitForEditor()
  openFile('First')
  fireEvent.change(await screen.findByRole('textbox', { name: '/name' }), { target: { value: 'Persisted' } })
  const draftKey = 'astralrecord.master-draft.v1:%2Ffilebase:skills%2Fa.yml'
  expect(window.localStorage.getItem(draftKey)).toContain('Persisted')
  expect(window.sessionStorage.getItem(draftKey)).toBeNull()
  view.unmount()
  render(<App />)
  await screen.findByText('復元できる未保存の下書き · 1 件')
  openFile('First')
  fireEvent.click(await screen.findByRole('button', { name: '下書きを復元' }))
  await waitFor(() => expect(screen.getByRole('textbox', { name: '/name' })).toHaveValue('Persisted'))
})

it('signals an expired Android session on auth errors without retrying writes', async () => {
  const fetch = vi.spyOn(window, 'fetch').mockImplementation(async () => new Response(null, { status: 401 }))
  await expect(masterDataApi.save(first.path, first.raw, first.revision)).rejects.toMatchObject({ status: 401 })
  expect(window.astralMobileSessionExpired).toBe(true)
  expect(fetch).toHaveBeenCalledTimes(1)
  window.astralMobileSessionExpired = false
  await expect(editorApi.listClasses()).rejects.toMatchObject({ status: 401 })
  expect(window.astralMobileSessionExpired).toBe(true)
  window.history.replaceState(null, '', '/')
  window.astralMobileSessionExpired = false
  await expect(editorApi.listClasses()).rejects.toMatchObject({ status: 401 })
  expect(window.astralMobileSessionExpired).toBe(false)
})

it('routes Android pairing only through the desktop WebView bridge', async () => {
  window.history.replaceState(null, '', '/')
  const web = render(<App />)
  await screen.findByRole('button', { name: '編集ナビゲーション' })
  expect(screen.queryByRole('button', { name: 'Android接続' })).not.toBeInTheDocument()
  web.unmount()
  const postMessage = vi.fn()
  vi.stubGlobal('chrome', { webview: { postMessage } })
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'Android接続' }))
  expect(postMessage).toHaveBeenCalledWith('android-connections')
})
