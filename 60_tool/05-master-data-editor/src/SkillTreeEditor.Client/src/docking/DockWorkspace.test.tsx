import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { DockviewApi } from 'dockview-react'
import { afterEach, expect, it, vi } from 'vitest'
import { DockPanelContent, useDocking } from './dockPanel'
import { DockWorkspace, workspaceLayoutKey } from './DockWorkspace'

const key = workspaceLayoutKey('C:/docking-fixture')!
afterEach(() => {
  localStorage.removeItem(key)
  vi.restoreAllMocks()
})

function Views({ onApi }: { onApi?: (api: DockviewApi) => void }) {
  const dock = useDocking()!
  if (dock.api) onApi?.(dock.api)
  return <>
    <DockPanelContent id="master"><label>原稿<input defaultValue="initial" /></label></DockPanelContent>
    <DockPanelContent id="analytics"><p>比較の結果</p></DockPanelContent>
    <DockPanelContent id="navigation"><p>画面一覧</p></DockPanelContent>
    <button type="button" onClick={() => dock.show('analytics')}>比較を開く</button>
    <button type="button" onClick={() => dock.show('master')}>原稿を開く</button>
    <button type="button" onClick={() => dock.show('skilltree')}>ツリーを開く</button>
    <button type="button" onClick={() => dock.visible.has('navigation') ? dock.close('navigation') : dock.show('navigation', false)}>ナビを切り替える</button>
  </>
}

async function renderWorkspace(layoutKey?: string) {
  let api: DockviewApi | undefined
  const view = render(<DockWorkspace layoutKey={layoutKey}><Views onApi={value => { api = value }} /></DockWorkspace>)
  await waitFor(() => expect(api).toBeDefined())
  return { ...view, api: api! }
}

function tabMenu(tabName: string) {
  fireEvent.contextMenu(screen.getByRole('tab', { name: tabName }), { clientX: 300, clientY: 130 })
  return within(screen.getByRole('menu'))
}

it('retains the editor input while tabbing, splitting, closing and reopening the panel', async () => {
  render(<DockWorkspace><Views /></DockWorkspace>)
  const draft = await screen.findByLabelText('原稿')
  fireEvent.change(draft, { target: { value: '未保存の原稿' } })
  fireEvent.click(screen.getByRole('button', { name: '比較を開く' }))
  await waitFor(() => expect(screen.getByText('比較の結果')).toBeVisible())
  expect(draft).not.toBeVisible()
  const tabGroup = screen.getByRole('region', { name: '比較・成長グラフ' })
  fireEvent.click(within(tabGroup).getByRole('button', { name: 'パネルの操作' }))
  const menu = within(screen.getByRole('menu', { name: 'パネルの操作' }))
  expect(menu.getAllByRole('menuitem')).toHaveLength(4)
  fireEvent.click(menu.getByRole('menuitem', { name: '下に分割' }))
  await waitFor(() => expect(draft).toBeVisible())
  expect(screen.getByText('比較の結果')).toBeVisible()
  fireEvent.click(within(screen.getByRole('region', { name: 'マスターデータ' })).getByRole('button', { name: 'マスターデータパネルを閉じる' }))
  expect(draft).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '原稿を開く' }))
  expect(await screen.findByLabelText('原稿')).toBe(draft)
  expect(draft).toHaveValue('未保存の原稿')
})

it('restores the workspace layout with navigation closed and lets it be reopened', async () => {
  const first = render(<DockWorkspace layoutKey={key}><Views /></DockWorkspace>)
  await screen.findByText('画面一覧')
  fireEvent.click(screen.getByRole('button', { name: 'ナビを切り替える' }))
  fireEvent.click(screen.getByRole('button', { name: '比較を開く' }))
  await waitFor(() => expect(JSON.parse(localStorage.getItem(key)!).panels).toHaveProperty('analytics'))
  first.unmount()

  render(<DockWorkspace layoutKey={key}><Views /></DockWorkspace>)
  expect(await screen.findByText('比較の結果')).toBeVisible()
  expect(screen.queryByText('画面一覧')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'ナビを切り替える' }))
  await waitFor(() => expect(screen.getByText('画面一覧')).toBeVisible())
})

it('keeps the resized main editor group empty across close-all and restores it before opening a new tab', async () => {
  // Match the layout box reported by the test ResizeObserver.
  vi.spyOn(HTMLElement.prototype, 'offsetParent', 'get').mockImplementation(function (this: HTMLElement) {
    return this.classList.contains('dv-shell') ? this.parentElement : null
  })
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.classList.contains('dv-shell') || this.parentElement?.classList.contains('dock-layout') ? 800 : 0
  })
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(function (this: HTMLElement) {
    return this.classList.contains('dv-shell') || this.parentElement?.classList.contains('dock-layout') ? 600 : 0
  })
  const first = await renderWorkspace(key)
  const editor = first.api.getPanel('master')!.group
  const navigation = first.api.getPanel('navigation')!.group
  const files = first.api.getPanel('master-files')!.group
  act(() => {
    navigation.api.setSize({ width: 146 })
    files.api.setSize({ width: 164 })
  })
  const width = editor.api.width
  const navigationWidth = navigation.api.width
  const filesWidth = files.api.width

  fireEvent.click(screen.getByRole('button', { name: '比較を開く' }))
  fireEvent.click(tabMenu('マスターデータ').getByRole('menuitem', { name: 'すべてのタブを閉じる' }))
  expect(screen.queryByRole('tab', { name: 'マスターデータ' })).not.toBeInTheDocument()
  expect(screen.queryByRole('tab', { name: '比較・成長グラフ' })).not.toBeInTheDocument()
  expect(screen.getByText('「パネル」メニューから開く画面を選べます。')).toBeVisible()
  expect(first.api.getGroup(editor.id)).toBe(editor)
  expect(editor.api.width).toBe(width)
  expect(navigation.api.width).toBe(navigationWidth)
  expect(files.api.width).toBe(filesWidth)
  await waitFor(() => {
    const saved = JSON.parse(localStorage.getItem(key)!)
    expect(saved.editorGroupId).toBe(editor.id)
    expect(saved.panels).not.toHaveProperty('master')
  })
  first.unmount()

  const { api } = await renderWorkspace(key)
  const restored = api.getGroup(editor.id)!
  expect(restored.size).toBe(0)
  expect(restored.api.width).toBe(width)
  expect(api.getPanel('navigation')!.group.api.width).toBe(navigationWidth)
  expect(api.getPanel('master-files')!.group.api.width).toBe(filesWidth)
  fireEvent.click(screen.getByRole('button', { name: 'ツリーを開く' }))
  fireEvent.click(screen.getByRole('button', { name: '原稿を開く' }))
  expect(restored.panels.map(panel => panel.id)).toEqual(['skilltree', 'master'])
  expect(restored.api.width).toBe(width)
})

it('recovers the main editor group from a layout saved before tracking its ID', async () => {
  const first = await renderWorkspace()
  const editor = first.api.getPanel('master')!.group
  const oldId = '1-legacy-editor'
  const saved = first.api.toJSON()
  const grid = saved.grid.root.data
  if (!Array.isArray(grid)) throw new Error('Expected a grid branch')
  const editorLeaf = grid.find(node => !Array.isArray(node.data) && node.data.id === editor.id)!
  if (Array.isArray(editorLeaf.data)) throw new Error('Expected an editor leaf')
  editorLeaf.data.id = oldId
  saved.activeGroup = oldId
  localStorage.setItem(key, JSON.stringify(saved))
  first.unmount()

  const { api } = await renderWorkspace(key)
  const restored = api.getGroup(oldId)!
  expect(restored.panels.map(panel => panel.id)).toEqual(['master'])
  const tab = screen.getByRole('tab', { name: 'マスターデータ' })
  fireEvent.click(within(tab).getByRole('button', { name: 'マスターデータパネルを閉じる' }))
  fireEvent.click(screen.getByRole('button', { name: '比較を開く' }))
  expect(restored.panels.map(panel => panel.id)).toEqual(['analytics'])
})

it.each([
  ['タブを閉じる', ['master', 'skilltree']],
  ['他のタブを閉じる', ['analytics']],
  ['右側のタブを閉じる', ['master', 'analytics']],
  ['左側のタブを閉じる', ['analytics', 'skilltree']],
])('applies %s relative to the clicked dock tab within its group', async (command, expectedTabs) => {
  const { api } = await renderWorkspace()
  const editor = api.getPanel('master')!.group
  fireEvent.click(screen.getByRole('button', { name: '比較を開く' }))
  fireEvent.click(screen.getByRole('button', { name: 'ツリーを開く' }))
  expect(api.activePanel?.id).toBe('skilltree')
  fireEvent.click(tabMenu('比較・成長グラフ').getByRole('menuitem', { name: command }))
  expect(editor.panels.map(panel => panel.id)).toEqual(expectedTabs)
  expect(screen.getByRole('tab', { name: '編集ナビ' })).toBeVisible()
})

it('docks a floated editor tab back into the empty main editor group', async () => {
  const { api } = await renderWorkspace()
  const master = api.getPanel('master')!
  const editor = master.group
  act(() => api.addFloatingGroup(master))
  expect(editor.size).toBe(0)
  expect(master.api.location.type).toBe('floating')

  const floating = screen.getByRole('region', { name: 'マスターデータ' })
  fireEvent.click(within(floating).getByRole('button', { name: 'パネルの操作' }))
  fireEvent.click(screen.getByRole('menuitem', { name: '画面内にドッキング' }))
  await waitFor(() => expect(api.groups.every(group => group.size > 0)).toBe(true))
  expect(master.group).toBe(editor)
})

it('keeps the main editor group available while its last panel is in a separate window', async () => {
  const frame = document.body.appendChild(document.createElement('iframe'))
  const popup = frame.contentWindow!
  vi.spyOn(window, 'open').mockReturnValue(popup)
  vi.spyOn(popup, 'focus').mockImplementation(() => {})
  const { api } = await renderWorkspace()
  const master = api.getPanel('master')!
  const editor = master.group
  const main = screen.getByRole('region', { name: 'マスターデータ' })
  fireEvent.click(within(main).getByRole('button', { name: '別ウィンドウで開く' }))
  await act(async () => { popup.dispatchEvent(new Event('load')) })
  await waitFor(() => expect(master.api.location.type).toBe('popout'))
  expect(editor.size).toBe(0)
  expect(editor.api.isVisible).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: '比較を開く' }))
  expect(api.getPanel('analytics')?.group).toBe(editor)

  const popupUi = within(popup.document.body)
  fireEvent.contextMenu(popupUi.getByRole('tab', { name: 'マスターデータ' }))
  expect(popupUi.getByRole('menuitem', { name: '他のタブを閉じる' }).getAttribute('aria-disabled')).toBe('true')
  fireEvent.keyDown(popup, { key: 'Escape' })
  fireEvent.click(popupUi.getByRole('button', { name: 'メインウィンドウに戻す' }))
  await waitFor(() => expect(master.group).toBe(editor))
  expect(api.getPopouts()).toHaveLength(0)
  frame.remove()
})
