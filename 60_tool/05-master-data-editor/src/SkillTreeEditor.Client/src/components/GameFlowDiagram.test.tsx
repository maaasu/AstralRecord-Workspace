import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { gameFlowApi } from '../api/gameFlowApi'
import { masterDataApi } from '../api/masterDataApi'
import { gameFlowCategory, gameFlowPath, gameFlowPorts, type GameFlowPort } from '../data/gameFlow'
import type { JsonValue } from '../types/editor'
import { GameFlowWorkspace } from './GameFlowWorkspace'

vi.mock('../api/gameFlowApi', () => ({ gameFlowApi: { context: vi.fn() } }))
vi.mock('../api/masterDataApi', () => ({ masterDataApi: {
  catalog: vi.fn(), files: vi.fn(), candidates: vi.fn(), file: vi.fn(), render: vi.fn(), validate: vi.fn(), save: vi.fn(),
} }))

const path = gameFlowPath('diagram')
const npcPath = '40.features.mob/npc/v1.general_goods_merchant.yml'
const fileMetadata = { format: 'yaml', size: 1, modifiedUtc: '', revision: '1' }
const content = {
  id: 'diagram', name: 'テスト用の進行フロー',
  nodes: [
    { id: 'buy', role: 'action', label: '買い物する', x: 0, y: 0 },
    { id: 'npc', ref: 'mob:general_goods_merchant', label: '受付へ行く', note: '初回だけ話す', x: 260, y: 200 },
    { id: 'memo', label: '旧メモ', x: 0, y: 200 },
  ],
  edges: [{ from: 'memo', to: 'npc' }],
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterDataApi.catalog).mockResolvedValue({ root: '', backups: '', categories: [] })
  vi.mocked(masterDataApi.files).mockResolvedValue([
    { ...fileMetadata, path, category: gameFlowCategory, id: 'diagram', name: content.name },
    { ...fileMetadata, path: npcPath, category: '40.features.mob', id: 'general_goods_merchant', name: '雑貨商' },
  ])
  vi.mocked(masterDataApi.candidates).mockResolvedValue([{
    path: npcPath, pointer: '/id', value: 'general_goods_merchant', label: '雑貨商', kind: 'mob',
  }])
  vi.mocked(masterDataApi.file).mockResolvedValue({ path, format: 'yaml', raw: '', revision: '1', issues: [], content })
  vi.mocked(masterDataApi.render).mockImplementation(async (_path, snapshot) => ({
    raw: JSON.stringify(snapshot), commentsPreserved: true, warnings: [],
  }))
  vi.mocked(masterDataApi.validate).mockResolvedValue({ isValid: true, issues: [] })
  vi.mocked(masterDataApi.save).mockImplementation(async (_path, raw) => ({
    path, format: 'yaml', raw, revision: '2', issues: [], content: JSON.parse(raw) as JsonValue,
  }))
  vi.mocked(gameFlowApi.context).mockResolvedValue({ shops: [], itemClasses: {} })
})

it('renders NPC hover and keyboard details outside the canvas, with four reusable ports and a separate sticky note', async () => {
  const user = userEvent.setup()
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const npc = await screen.findByRole('group', { name: 'NPC 受付へ行く' })
  const npcPorts = [...npc.querySelectorAll('.game-flow-port.connectable')]
  expect(npcPorts.map(port => port.getAttribute('data-handleid'))).toEqual(gameFlowPorts)
  expect(npc.querySelector('.game-flow-token-person')).toBeInTheDocument()
  const memo = screen.getByRole('group', { name: 'メモ 旧メモ' })
  expect(memo.querySelector('[data-shape="note"]')).toBeInTheDocument()
  expect(memo.querySelector('.game-flow-port.connectable')).toBeNull()
  expect(screen.queryByRole('button', { name: 'カード' })).toBeNull()
  const ownedEdge = await screen.findByTestId('rf__edge-memo->npc')
  expect(ownedEdge).toBeInTheDocument()

  fireEvent.mouseEnter(npc)
  const tooltip = await screen.findByRole('tooltip')
  expect(tooltip.parentElement).toBe(document.body)
  expect(within(tooltip).getByText('雑貨商')).toBeVisible()
  expect(within(tooltip).getByText('mob:general_goods_merchant')).toBeVisible()
  expect(within(tooltip).getByText('初回だけ話す')).toBeVisible()
  expect(within(tooltip).getByText('全職業共通')).toBeVisible()
  fireEvent.pointerDown(npcPorts[0])
  expect(screen.queryByRole('tooltip')).toBeNull()
  fireEvent.mouseLeave(npc)

  act(() => screen.getByRole('group', { name: '操作 買い物する' }).focus())
  await user.tab()
  expect(npc).toHaveFocus()
  expect(screen.getByRole('tooltip')).toHaveTextContent('雑貨商')
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('tooltip')).toBeNull()
})

it('keeps explicitly dragged endpoints through undo, redo, reversal, save and reload', async () => {
  const user = userEvent.setup()
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const action = await screen.findByRole('group', { name: '操作 買い物する' })
  const npc = screen.getByRole('group', { name: 'NPC 受付へ行く' })
  expect(action.querySelector('.game-flow-token')).toHaveAttribute('data-label-position', 'top')
  const getPort = (node: HTMLElement, port: GameFlowPort) => node.querySelector<HTMLElement>(`[data-handleid="${port}"]`)!
  const origin = getPort(action, 'top')
  const destination = getPort(npc, 'right')
  const originalElementFromPoint = Object.getOwnPropertyDescriptor(document, 'elementFromPoint')
  Object.defineProperty(document, 'elementFromPoint', { value: () => destination, configurable: true })
  try {
    fireEvent.mouseDown(origin, { button: 0, buttons: 1, clientX: 80, clientY: 80 })
    fireEvent.mouseMove(document, { buttons: 1, clientX: 500, clientY: 250 })
    fireEvent.mouseUp(document, { button: 0, clientX: 500, clientY: 250 })
  } finally {
    if (originalElementFromPoint) Object.defineProperty(document, 'elementFromPoint', originalElementFromPoint)
    else Reflect.deleteProperty(document, 'elementFromPoint')
  }
  const edge = await screen.findByTestId('rf__edge-buy->npc')
  expect(edge).toBeInTheDocument()
  expect(action.querySelector('.game-flow-token')).toHaveAttribute('data-label-position', 'bottom')
  await user.click(screen.getByRole('button', { name: '元に戻す' }))
  expect(screen.queryByTestId('rf__edge-buy->npc')).toBeNull()
  await user.click(screen.getByRole('button', { name: 'やり直す' }))
  const restored = await screen.findByTestId('rf__edge-buy->npc')

  fireEvent.contextMenu(restored)
  await user.click(screen.getByRole('menuitem', { name: '向きを反転' }))
  expect(await screen.findByTestId('rf__edge-npc->buy')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(masterDataApi.save).toHaveBeenCalled())
  const saved = vi.mocked(masterDataApi.save).mock.lastCall![1]
  expect(JSON.parse(saved).edges).toEqual([
    { from: 'memo', to: 'npc' },
    { from: 'npc', to: 'buy', fromPort: 'right', toPort: 'top' },
  ])
  const openFile = vi.mocked(masterDataApi.file)
  openFile.mockClear()
  openFile.mockResolvedValue({ path, format: 'yaml', raw: saved, revision: '2', issues: [], content: JSON.parse(saved) as JsonValue })
  await waitFor(() => expect(screen.getByRole('button', { name: '再読込' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: '再読込' }))
  await waitFor(() => expect(openFile).toHaveBeenCalledExactlyOnceWith(path))
  expect(await screen.findByTestId('rf__edge-npc->buy')).toBeInTheDocument()
})

it('adds an action and places an independent note near an existing node', async () => {
  const user = userEvent.setup()
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const npc = await screen.findByRole('group', { name: 'NPC 受付へ行く' })
  fireEvent.contextMenu(npc)
  await user.click(screen.getByRole('menuitem', { name: '近くにメモを置く' }))
  expect(await screen.findByRole('group', { name: 'メモ メモ' })).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '操作を追加' }))
  expect(await screen.findByRole('group', { name: '操作 操作' })).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(masterDataApi.save).toHaveBeenCalled())
  const saved = JSON.parse(vi.mocked(masterDataApi.save).mock.lastCall![1])
  expect(saved.nodes.slice(-2)).toMatchObject([{ label: 'メモ' }, { label: '操作', role: 'action' }])
  expect(saved.nodes.at(-2)).not.toHaveProperty('role')
  expect(saved.edges).toEqual(content.edges)
})
