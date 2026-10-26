import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./components/MasterDataEditor', () => ({ MasterDataEditor: () => <div>マスター画面</div> }))
vi.mock('./components/SpawnerPlanningWorkspace', () => ({
  SpawnerPlanningWorkspace: ({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) =>
    <div><button onClick={() => onDirtyChange(true)}>Dev 配置を編集</button><span>配置画面を保持</span></div>,
}))
afterEach(() => vi.restoreAllMocks())

it('未保存の配置を保持して画面切替を確認する', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  render(<App />)
  fireEvent.click(screen.getByRole('button', { name: 'ワールド配置計画' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Dev 配置を編集' }))
  fireEvent.click(screen.getByRole('button', { name: 'マスターデータ' }))
  expect(confirm).toHaveBeenCalledOnce()
  expect(screen.getByText('配置画面を保持')).toBeVisible()
  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: 'マスターデータ' }))
  fireEvent.click(screen.getByRole('button', { name: 'ワールド配置計画' }))
  expect(screen.getByText('配置画面を保持')).toBeVisible()
})
