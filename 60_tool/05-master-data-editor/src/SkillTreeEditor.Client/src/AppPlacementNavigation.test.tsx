import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./components/MasterDataEditor', () => ({ MasterDataEditor: () => <div>マスター画面</div> }))
vi.mock('./components/SpawnerPlanningWorkspace', () => ({
  SpawnerPlanningWorkspace: ({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) =>
    <div><button onClick={() => onDirtyChange(true)}>Dev 配置を編集</button><span>配置画面を保持</span></div>,
}))
afterEach(() => vi.restoreAllMocks())

it('未保存の配置を保持したまま確認なしで画面を切り替える', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  render(<App />)
  fireEvent.click(await screen.findByRole('button', { name: 'ワールド配置・編集' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Dev 配置を編集' }))
  fireEvent.click(screen.getByRole('button', { name: 'マスターデータ' }))
  expect(confirm).not.toHaveBeenCalled()
  expect(await screen.findByText('マスター画面')).toBeVisible()
  confirm.mockReturnValue(true)
  fireEvent.click(screen.getByRole('button', { name: 'マスターデータ' }))
  fireEvent.click(screen.getByRole('button', { name: 'ワールド配置・編集 ●' }))
  await waitFor(() => expect(screen.getByText('配置画面を保持')).toBeVisible())
})
