import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./components/MasterDataEditor', () => ({ MasterDataEditor: ({ onDirtyChange }: { onDirtyChange: (value: boolean) => void }) => <label>マスターの下書き<input onChange={() => onDirtyChange(true)} /></label> }))
vi.mock('./components/SkillTreeWorkspace', () => ({ SkillTreeWorkspace: () => <label>ツリーの下書き<input /></label> }))
vi.mock('./components/MasterAnalytics', () => ({ MasterAnalytics: () => <p>比較の作業領域</p> }))
vi.mock('./components/SpawnerPlanningWorkspace', () => ({ SpawnerPlanningWorkspace: () => <p>配置の作業領域</p> }))

afterEach(() => vi.restoreAllMocks())

it('shows the active workspace and retains edited inputs across navigation', async () => {
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<App />)
  const nav = within(screen.getByRole('navigation', { name: '開発ツール' }))
  expect(nav.getAllByRole('button')).toHaveLength(4)
  expect(nav.getByRole('button', { name: 'マスターデータ' })).toHaveAttribute('aria-current', 'page')
  const draft = await screen.findByLabelText('マスターの下書き')
  fireEvent.change(draft, { target: { value: '編集中の値' } })
  expect(nav.getByRole('button', { name: 'マスターデータ ●' })).toHaveAttribute('aria-pressed', 'true')
  fireEvent.click(nav.getByRole('button', { name: 'スキルツリー' }))
  await screen.findByLabelText('ツリーの下書き')
  expect(draft).not.toBeVisible()
  expect(nav.getByRole('button', { name: 'スキルツリー' })).toHaveAttribute('aria-current', 'page')
  expect(nav.getByRole('button', { name: 'マスターデータ ●' })).not.toHaveAttribute('aria-current')
  fireEvent.click(nav.getByRole('button', { name: 'マスターデータ ●' }))
  expect(screen.getByLabelText('マスターの下書き')).toBe(draft)
  expect(draft).toHaveValue('編集中の値')
  expect(draft).toBeVisible()
})
