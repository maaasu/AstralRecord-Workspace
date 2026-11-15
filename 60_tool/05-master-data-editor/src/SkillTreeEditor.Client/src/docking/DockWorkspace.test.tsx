import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { DockPanelContent, useDocking } from './dockPanel'
import { DockWorkspace, workspaceLayoutKey } from './DockWorkspace'

const key = workspaceLayoutKey('C:/docking-fixture')!
afterEach(() => localStorage.removeItem(key))

function Views() {
  const dock = useDocking()!
  return <>
    <DockPanelContent id="master"><label>原稿<input defaultValue="initial" /></label></DockPanelContent>
    <DockPanelContent id="analytics"><p>比較の結果</p></DockPanelContent>
    <DockPanelContent id="navigation"><p>画面一覧</p></DockPanelContent>
    <button type="button" onClick={() => dock.show('analytics')}>比較を開く</button>
    <button type="button" onClick={() => dock.show('master')}>原稿を開く</button>
    <button type="button" onClick={() => dock.visible.has('navigation') ? dock.close('navigation') : dock.show('navigation', false)}>ナビを切り替える</button>
  </>
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
