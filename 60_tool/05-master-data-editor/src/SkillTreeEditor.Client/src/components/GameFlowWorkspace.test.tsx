import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { gameFlowApi } from '../api/gameFlowApi'
import { masterDataApi } from '../api/masterDataApi'
import { gameFlowCategory, gameFlowPath } from '../data/gameFlow'
import { GameFlowWorkspace } from './GameFlowWorkspace'

vi.mock('../api/gameFlowApi', () => ({ gameFlowApi: { context: vi.fn() } }))
vi.mock('../api/masterDataApi', () => ({ masterDataApi: { catalog: vi.fn(), files: vi.fn(), candidates: vi.fn(), file: vi.fn() } }))

const sidebarKey = 'astral.gameFlow.sidebarOpen'
const inspectorKey = 'astral.gameFlow.inspectorOpen'
const flowName = 'テスト用の進行フロー'
const path = gameFlowPath('test')
const jobPath = 'classes/v1.swordman.yml'
const fileMetadata = { format: 'yaml', size: 1, modifiedUtc: '', revision: '1' }

beforeEach(() => {
  vi.mocked(masterDataApi.catalog).mockResolvedValue({ root: '', backups: '', categories: [] })
  vi.mocked(masterDataApi.files).mockResolvedValue([
    { ...fileMetadata, path, category: gameFlowCategory, id: 'test', name: flowName },
    { ...fileMetadata, path: jobPath, category: 'classes', id: 'swordman', name: 'ソードマン' },
  ])
  vi.mocked(masterDataApi.candidates).mockResolvedValue([{ path: jobPath, pointer: '/id', value: 'swordman', label: 'ソードマン', kind: 'class' }])
  vi.mocked(masterDataApi.file).mockResolvedValue({ path, format: 'yaml', raw: '', revision: '1', issues: [],
    content: { id: 'test', name: flowName, nodes: [], edges: [] } })
  vi.mocked(gameFlowApi.context).mockResolvedValue({ shops: [], itemClasses: {} })
})

afterEach(() => {
  localStorage.removeItem(sidebarKey)
  localStorage.removeItem(inspectorKey)
})

it('ボタンと図のショートカットでパネルを開閉し、再表示時にも開閉状態を復元する', async () => {
  const user = userEvent.setup()
  const workspace = render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  await waitFor(() => expect(screen.getByRole('button', { name: 'メモを追加' })).toBeEnabled())
  const sidebar = screen.getByRole('complementary', { name: 'フロー一覧' })
  const inspector = screen.getByRole('complementary', { name: 'プロパティ' })
  const listToggle = screen.getByRole('button', { name: '一覧' })
  const inspectorToggle = screen.getByRole('button', { name: 'プロパティ' })
  const root = workspace.container.querySelector('.game-flow')!
  expect(listToggle).toHaveAttribute('aria-controls', sidebar.id)
  expect(inspectorToggle).toHaveAttribute('aria-controls', inspector.id)

  await user.click(listToggle)
  expect(sidebar).not.toBeVisible()
  expect(listToggle).toHaveAttribute('aria-pressed', 'false')
  expect(root).toHaveAttribute('data-sidebar', 'closed')
  expect(localStorage.getItem(sidebarKey)).toBe('0')
  expect(screen.getByTitle(flowName)).toBeVisible()

  await user.click(inspectorToggle)
  expect(inspector).not.toBeVisible()
  expect(root).toHaveAttribute('data-inspector', 'closed')
  expect(localStorage.getItem(inspectorKey)).toBe('0')

  const canvas = workspace.container.querySelector<HTMLElement>('.game-flow-body')!
  canvas.focus()
  await user.keyboard('{Control>}[[{/Control}')
  expect(sidebar).toBeVisible()
  expect(localStorage.getItem(sidebarKey)).toBe('1')
  await user.keyboard('{Control>}]{/Control}')
  expect(inspector).toBeVisible()
  expect(localStorage.getItem(inspectorKey)).toBe('1')
  await user.keyboard('{Control>}[[]{/Control}')
  workspace.unmount()

  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  await waitFor(() => expect(screen.getByRole('button', { name: 'メモを追加' })).toBeEnabled())
  expect(screen.queryByRole('complementary', { name: 'フロー一覧' })).not.toBeInTheDocument()
  expect(screen.queryByRole('complementary', { name: 'プロパティ' })).not.toBeInTheDocument()
})

it('対象職業の選択に応じて絞り込みを切り替え、職業の変更を元に戻せる', async () => {
  const user = userEvent.setup()
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const inspector = screen.getByRole('complementary', { name: 'プロパティ' })
  const edit = await within(inspector).findByRole('button', { name: '変更' })
  const classButton = screen.getByRole('button', { name: '対象職業: 全職業' })
  expect(screen.getByRole('combobox', { name: '職業で絞り込み' })).toBeVisible()
  expect(within(inspector).queryByRole('checkbox')).not.toBeInTheDocument()
  await user.click(edit)
  const dialog = within(screen.getByRole('dialog', { name: '対象職業' }))
  const swordman = dialog.getByRole('checkbox', { name: 'ソードマン' })
  expect(swordman).toHaveFocus()
  await user.click(swordman)
  expect(classButton).toHaveTextContent('対象職業 1')
  expect(screen.queryByRole('combobox', { name: '職業で絞り込み' })).not.toBeInTheDocument()
  expect(within(inspector).getByText('対象職業: ソードマン')).toBeVisible()
  expect(screen.getByText('未保存')).toBeVisible()

  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog', { name: '対象職業' })).not.toBeInTheDocument()
  expect(classButton).toHaveFocus()
  const filters = within(screen.getByRole('group', { name: '職業で絞り込み' }))
  const chip = filters.getByRole('button', { name: 'ソードマン' })
  await user.click(chip)
  expect(chip).toHaveAttribute('aria-pressed', 'true')
  await user.click(chip)
  expect(filters.getByRole('button', { name: 'すべて' })).toHaveAttribute('aria-pressed', 'true')

  await user.click(screen.getByRole('button', { name: '元に戻す' }))
  expect(screen.getByRole('combobox', { name: '職業で絞り込み' })).toBeVisible()
  expect(screen.queryByText('未保存')).not.toBeInTheDocument()
  await user.click(classButton)
  fireEvent.pointerDown(inspector)
  expect(screen.queryByRole('dialog', { name: '対象職業' })).not.toBeInTheDocument()
  expect(classButton).toHaveFocus()
})
