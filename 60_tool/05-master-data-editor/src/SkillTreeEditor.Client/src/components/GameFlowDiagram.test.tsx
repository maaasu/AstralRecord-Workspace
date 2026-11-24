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

function drawConnection(from: HTMLElement, fromPort: GameFlowPort, to: HTMLElement, toPort: GameFlowPort) {
  const handle = (node: HTMLElement, port: GameFlowPort) => node.querySelector<HTMLElement>(`[data-handleid="${port}"]`)!
  const destination = handle(to, toPort)
  const originalElementFromPoint = Object.getOwnPropertyDescriptor(document, 'elementFromPoint')
  Object.defineProperty(document, 'elementFromPoint', { value: () => destination, configurable: true })
  try {
    fireEvent.mouseDown(handle(from, fromPort), { button: 0, buttons: 1, clientX: 80, clientY: 80 })
    fireEvent.mouseMove(document, { buttons: 1, clientX: 500, clientY: 250 })
    fireEvent.mouseUp(document, { button: 0, clientX: 500, clientY: 250 })
  } finally {
    if (originalElementFromPoint) Object.defineProperty(document, 'elementFromPoint', originalElementFromPoint)
    else Reflect.deleteProperty(document, 'elementFromPoint')
  }
}

const visiblePath = (edge: HTMLElement) => edge.querySelector('.react-flow__edge-path')

it('renders NPC hover and keyboard details outside the canvas, with four reusable ports and a separate sticky note', async () => {
  const user = userEvent.setup()
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const npc = await screen.findByRole('group', { name: 'NPC 受付へ行く' })
  const npcPorts = [...npc.querySelectorAll('.game-flow-port.connectable')]
  expect(npcPorts.map(port => port.getAttribute('data-handleid'))).toEqual(gameFlowPorts)
  expect(npc.querySelector('.game-flow-token-person')).toBeInTheDocument()
  const memo = screen.getByRole('group', { name: 'メモ 旧メモ' })
  expect(memo.querySelector('[data-shape="note"]')).toBeInTheDocument()
  expect([...memo.querySelectorAll('.game-flow-port.connectable')].map(port => port.getAttribute('data-handleid'))).toEqual(gameFlowPorts)
  expect(screen.queryByRole('button', { name: 'カード' })).toBeNull()
  const ownedEdge = await screen.findByTestId('rf__edge-memo->npc')
  expect(ownedEdge).toHaveClass('annotation')
  expect(visiblePath(ownedEdge)).not.toHaveAttribute('marker-end')

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
  drawConnection(action, 'top', npc, 'right')
  const edge = await screen.findByTestId('rf__edge-buy->npc')
  expect(visiblePath(edge)).toHaveAttribute('marker-end')
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

it('adds an action and connects a nearby annotation from the node menu', async () => {
  const user = userEvent.setup()
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const npc = await screen.findByRole('group', { name: 'NPC 受付へ行く' })
  fireEvent.contextMenu(npc)
  await user.click(screen.getByRole('menuitem', { name: '注釈メモをつなぐ' }))
  expect(await screen.findByRole('group', { name: 'メモ メモ' })).toBeInTheDocument()
  expect(await screen.findByTestId('rf__edge-npc->node')).toHaveClass('annotation')
  await user.click(screen.getByRole('button', { name: '操作を追加' }))
  expect(await screen.findByRole('group', { name: '操作 操作' })).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(masterDataApi.save).toHaveBeenCalled())
  const saved = JSON.parse(vi.mocked(masterDataApi.save).mock.lastCall![1])
  expect(saved.nodes.slice(-2)).toMatchObject([{ label: 'メモ' }, { label: '操作', role: 'action' }])
  expect(saved.nodes.at(-2)).not.toHaveProperty('role')
  expect(saved.edges).toEqual([...content.edges, { from: 'npc', to: saved.nodes.at(-2).id }])
})

it.each([
  ['memo', 'buy', 'メモ 旧メモ', '操作 買い物する'],
  ['buy', 'memo', '操作 買い物する', 'メモ 旧メモ'],
])('allows annotations from %s to %s, preserves ports, and deletes them from the menu', async (from, to, fromName, toName) => {
  const user = userEvent.setup()
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const source = await screen.findByRole('group', { name: fromName })
  const target = screen.getByRole('group', { name: toName })
  drawConnection(source, 'left', target, 'bottom')
  const edge = await screen.findByTestId(`rf__edge-${from}->${to}`)
  expect(edge).toHaveClass('annotation')
  expect(visiblePath(edge)).not.toHaveAttribute('marker-end')
  expect(screen.getByRole('heading', { name: '注釈線' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(masterDataApi.save).toHaveBeenCalled())
  const saved = JSON.parse(vi.mocked(masterDataApi.save).mock.lastCall![1])
  expect(saved.edges.at(-1)).toEqual({ from, to, fromPort: 'left', toPort: 'bottom' })

  fireEvent.contextMenu(edge)
  expect(screen.getByRole('menuitem', { name: '注釈のつながりをコピー' })).toBeVisible()
  expect(screen.queryByRole('menuitem', { name: '向きを反転' })).toBeNull()
  await user.click(screen.getByRole('menuitem', { name: '注釈線を削除' }))
  expect(screen.queryByTestId(`rf__edge-${from}->${to}`)).toBeNull()
  expect(screen.getByTestId('rf__edge-memo->npc')).toHaveClass('annotation')
})

it('renders a single bridge and removes the lower mask when the crossing edge is deleted', async () => {
  const user = userEvent.setup()
  const nodes = [
    ['left', 0, 100], ['right', 400, 100], ['top', 200, 0], ['bottom', 200, 200],
  ].map(([id, x, y]) => ({ id, x, y, role: 'action', label: String(id) }))
  const edges = [
    { from: 'left', to: 'right', fromPort: 'right', toPort: 'left', label: '移動' },
    { from: 'top', to: 'bottom', fromPort: 'bottom', toPort: 'top' },
  ]
  vi.mocked(masterDataApi.file).mockResolvedValueOnce({ path, format: 'yaml', raw: '', revision: '1', issues: [],
    content: { ...content, nodes, edges } })
  const originalWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth')!.get!
  const originalHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')!.get!
  const width = vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function (this: HTMLElement) {
    return this.classList.contains('game-flow-port') ? 12 : originalWidth.call(this)
  })
  const height = vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function (this: HTMLElement) {
    return this.classList.contains('game-flow-port') ? 12 : originalHeight.call(this)
  })
  const originalBBox = Object.getOwnPropertyDescriptor(SVGElement.prototype, 'getBBox')
  Object.defineProperty(SVGElement.prototype, 'getBBox', {
    configurable: true, value: () => ({ x: 0, y: 0, width: 30, height: 12 }),
  })
  try {
    render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
    const horizontal = await screen.findByTestId('rf__edge-left->right')
    const vertical = screen.getByTestId('rf__edge-top->bottom')
    await waitFor(() => expect(visiblePath(horizontal)).toHaveAttribute('d', expect.stringContaining('A')))
    expect(visiblePath(vertical)).toHaveAttribute('d', expect.not.stringContaining('A'))
    expect(visiblePath(vertical)).toHaveAttribute('mask', expect.stringMatching(/^url\(#game-flow-jump-/))
    expect(visiblePath(vertical)).toHaveAttribute('pointer-events', 'none')
    const interaction = vertical.querySelector('.react-flow__edge-interaction')
    expect(interaction).not.toHaveAttribute('mask')
    expect(interaction).toHaveAttribute('stroke-width', '20')
    expect(interaction?.parentElement).toHaveAttribute('clip-path', expect.stringMatching(/^url\(#game-flow-jump-.*-hit-0\)$/))
    expect(vertical.querySelector('clipPath path')).toHaveAttribute('clip-rule', 'evenodd')
    expect(visiblePath(horizontal)).toHaveAttribute('marker-end')
    expect(within(horizontal).getByText('移動')).toBeVisible()

    const movedLabelTransform = horizontal.querySelector('.react-flow__edge-textwrapper')?.getAttribute('transform')
    fireEvent.contextMenu(vertical)
    await user.click(screen.getByRole('menuitem', { name: '矢印を削除' }))
    await waitFor(() => expect(visiblePath(horizontal)).toHaveAttribute('d', expect.not.stringContaining('A')))
    expect(horizontal.querySelector('.react-flow__edge-textwrapper')?.getAttribute('transform')).not.toBe(movedLabelTransform)

    await user.click(screen.getByRole('button', { name: '元に戻す' }))
    const restoredVertical = await screen.findByTestId('rf__edge-top->bottom')
    await waitFor(() => expect(visiblePath(restoredVertical)).toHaveAttribute('mask'))
    fireEvent.contextMenu(horizontal)
    await user.click(screen.getByRole('menuitem', { name: '矢印を削除' }))
    await waitFor(() => expect(visiblePath(restoredVertical)).not.toHaveAttribute('mask'))
  } finally {
    width.mockRestore()
    height.mockRestore()
    if (originalBBox) Object.defineProperty(SVGElement.prototype, 'getBBox', originalBBox)
    else Reflect.deleteProperty(SVGElement.prototype, 'getBBox')
  }
})
