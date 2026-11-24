import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { gameFlowApi } from '../api/gameFlowApi'
import { masterDataApi } from '../api/masterDataApi'
import { gameFlowCategory, gameFlowPath, gameFlowPorts, kindLabels, type GameFlowPort } from '../data/gameFlow'
import type { JsonValue } from '../types/editor'
import { GameFlowWorkspace } from './GameFlowWorkspace'

vi.mock('../api/gameFlowApi', () => ({ gameFlowApi: { context: vi.fn() } }))
vi.mock('../api/masterDataApi', () => ({ masterDataApi: {
  catalog: vi.fn(), files: vi.fn(), candidates: vi.fn(), file: vi.fn(), render: vi.fn(), validate: vi.fn(), save: vi.fn(),
} }))

const path = gameFlowPath('diagram')
const npcPath = '40.features.mob/npc/v1.general_goods_merchant.yml'
const fileMetadata = { format: 'yaml', size: 1, modifiedUtc: '', revision: '1' }
const npcFile = { ...fileMetadata, path: npcPath, category: '40.features.mob', id: 'general_goods_merchant', name: '雑貨商', icon: 'CHEST' }
const content = {
  id: 'diagram', name: 'テスト用の進行フロー',
  nodes: [
    { id: 'buy', role: 'action', label: '買い物する', x: 0, y: 0 },
    { id: 'npc', ref: 'mob:general_goods_merchant', label: '受付へ行く', note: '初回だけ話す', x: 260, y: 200 },
    { id: 'memo', label: '旧メモ', x: 0, y: 200 },
  ],
  edges: [{ from: 'memo', to: 'npc' }],
}

function mockNpcSkin(skinTexture?: string, withIcon = true) {
  vi.mocked(masterDataApi.files).mockResolvedValue([
    { ...fileMetadata, path, category: gameFlowCategory, id: 'diagram', name: content.name },
    { ...npcFile, skinTexture, icon: withIcon ? npcFile.icon : undefined },
  ])
}

const skinProfile = (url: string) => btoa(JSON.stringify({ textures: { SKIN: { url } } }))

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(masterDataApi.catalog).mockResolvedValue({ root: '', backups: '', categories: [] })
  mockNpcSkin()
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
  mockNpcSkin(undefined, false)
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

it('uses the NPC Material as a fallback after the skin fails to load', async () => {
  mockNpcSkin(skinProfile('http://textures.minecraft.net/texture/abc123'))
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)

  const npc = await screen.findByRole('group', { name: 'NPC 受付へ行く' })
  const layers = [...npc.querySelectorAll('.minecraft-head-face img')]
  expect(layers).toHaveLength(2)
  for (const layer of layers) expect(layer).toHaveAttribute('src', '/api/minecraft-npc-skins/abc123')
  expect(npc.querySelector('.game-flow-token-person')).not.toBeInTheDocument()

  fireEvent.error(layers[0])
  expect(npc.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/CHEST?revision=0')

  fireEvent.click(screen.getByRole('button', { name: '再読込' }))
  await waitFor(() => expect(npc.querySelector('.minecraft-head-face img')).toHaveAttribute('src', '/api/minecraft-npc-skins/abc123?revision=1'))
  expect(npc.querySelector('.game-flow-token-person')).not.toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('button', { name: '再読込' })).toBeEnabled())

  mockNpcSkin(skinProfile('https://textures.minecraft.net/texture/def456'))
  fireEvent.click(screen.getByRole('button', { name: '再読込' }))
  await waitFor(() => expect(npc.querySelector('.minecraft-head-face img')).toHaveAttribute('src', '/api/minecraft-npc-skins/def456?revision=2'))
  expect(npc.querySelector('.game-flow-token-person')).not.toBeInTheDocument()
})

it.each(['!', skinProfile('https://example.com/texture/abc123')])('keeps the person icon for an invalid NPC skin', async (texture) => {
  mockNpcSkin(texture, false)
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)

  const npc = await screen.findByRole('group', { name: 'NPC 受付へ行く' })
  expect(npc.querySelector('.game-flow-token-person')).toBeInTheDocument()
  expect(npc.querySelector('img')).not.toBeInTheDocument()
})

it('参照アイコンを表示し、付箋にアイコンを追加しても本文と注釈線を保持する', async () => {
  const references = [
    { kind: 'game_flow', label: '第1章クリア', icon: 'WRITABLE_BOOK', category: gameFlowCategory },
    { kind: 'progression_preset', label: 'クリア時のビルド', icon: 'COMPASS', category: '90.shared.progression_preset' },
    { kind: 'guide', label: '章のガイド', icon: 'BOOK', category: '09.features.guide' },
    { kind: 'mail', label: '受け取る', icon: 'CHEST', category: '05.features.mail' },
  ]
  const files = references.map(({ kind, label, icon, category }) => ({
    ...fileMetadata, path: `${category}/v1.${kind}.yml`, category, id: kind, name: label, icon,
  }))
  const loadedFiles = await masterDataApi.files()
  const loadedCandidates = await masterDataApi.candidates()
  vi.mocked(masterDataApi.files).mockResolvedValue([...loadedFiles, ...files])
  vi.mocked(masterDataApi.candidates).mockResolvedValue([...loadedCandidates, ...files.map(file => ({
    path: file.path, pointer: '/id', value: file.id, label: file.name, kind: file.id,
  }))])
  const nodes = [...content.nodes, ...references.map(({ kind, label }, index) => ({
    id: kind, ref: `${kind}:${kind}`, label, x: 260 * index, y: 400,
  }))]
  vi.mocked(masterDataApi.file).mockResolvedValue({
    path, format: 'yaml', raw: '', revision: '1', issues: [], content: { ...content, nodes },
  })
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  await screen.findByRole('group', { name: 'ゲーム進行フロー 第1章クリア' })
  for (const reference of references) {
    const node = screen.getByRole('group', { name: `${kindLabels[reference.kind]} ${reference.label}` })
    expect(node.querySelector('img')).toHaveAttribute('src', `/api/minecraft-icons/${reference.icon}?revision=0`)
  }

  const mail = screen.getByRole('group', { name: 'メール 受け取る' })
  fireEvent.click(mail)
  fireEvent.change(screen.getByLabelText('アイコンの上書き（任意）'), { target: { value: 'PAPER' } })
  await waitFor(() => expect(mail.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/PAPER?revision=0'))
  fireEvent.change(screen.getByLabelText('アイコンの上書き（任意）'), { target: { value: '' } })
  await waitFor(() => expect(mail.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/CHEST?revision=0'))

  const memo = screen.getByRole('group', { name: 'メモ 旧メモ' })
  fireEvent.click(memo)
  fireEvent.change(screen.getByLabelText('アイコン（任意）'), { target: { value: 'MAP' } })
  await waitFor(() => expect(memo.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/MAP?revision=0'))
  expect(within(memo).getByText('旧メモ')).toBeVisible()
  expect(screen.getByTestId('rf__edge-memo->npc')).toHaveClass('annotation')
  fireEvent.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(masterDataApi.save).toHaveBeenCalled())
  const saved = JSON.parse(vi.mocked(masterDataApi.save).mock.lastCall![1])
  expect(saved.nodes.find((node: { id: string }) => node.id === 'memo')).toMatchObject({ label: '旧メモ', icon: 'MAP' })
  expect(saved.nodes.find((node: { id: string }) => node.id === 'mail')).not.toHaveProperty('icon')
  expect(saved.edges).toEqual(content.edges)
})

it('章で保存したアイコンを全体フローに戻るとすぐに表示する', async () => {
  const chapterPath = gameFlowPath('tutorial')
  const chapter = { id: 'tutorial', name: '第1章の詳細', icon: 'WRITABLE_BOOK', nodes: [], edges: [] }
  const overview = { ...content, nodes: [{ id: 'chapter', ref: 'game_flow:tutorial', label: '第1章クリア', x: 0, y: 0 }], edges: [] }
  const loadedFiles = await masterDataApi.files()
  vi.mocked(masterDataApi.files).mockResolvedValue([...loadedFiles,
    { ...fileMetadata, path: chapterPath, category: gameFlowCategory, id: chapter.id, name: chapter.name, icon: chapter.icon },
  ])
  vi.mocked(masterDataApi.candidates).mockResolvedValue([
    { path: chapterPath, pointer: '/id', value: chapter.id, label: chapter.name, kind: 'game_flow' },
  ])
  vi.mocked(masterDataApi.file).mockImplementation(async requestedPath => ({
    path: requestedPath, format: 'yaml', raw: '', revision: '1', issues: [], content: requestedPath === chapterPath ? chapter : overview,
  }))
  vi.mocked(masterDataApi.save).mockImplementation(async (savedPath, raw) => ({
    path: savedPath, format: 'yaml', raw, revision: '2', issues: [], content: JSON.parse(raw) as JsonValue,
  }))
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)

  const node = await screen.findByRole('group', { name: 'ゲーム進行フロー 第1章クリア' })
  expect(node.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/WRITABLE_BOOK?revision=0')
  fireEvent.click(screen.getByRole('button', { name: /第1章の詳細/ }))
  const field = await screen.findByDisplayValue('WRITABLE_BOOK')
  fireEvent.change(field, { target: { value: 'WRITTEN_BOOK' } })
  fireEvent.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(masterDataApi.save).toHaveBeenCalled())
  expect(JSON.parse(vi.mocked(masterDataApi.save).mock.lastCall![1])).toMatchObject({ icon: 'WRITTEN_BOOK' })
  fireEvent.click(screen.getByRole('button', { name: /テスト用の進行フロー/ }))
  const updated = await screen.findByRole('group', { name: 'ゲーム進行フロー 第1章クリア' })
  expect(updated.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/WRITTEN_BOOK?revision=0')
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

it('snaps the live drag from raw pointer positions and saves that exact position as one edit', async () => {
  const user = userEvent.setup()
  const nodes = content.nodes.map(node => node.id === 'memo' ? { ...node, x: 178 } : node)
  vi.mocked(masterDataApi.file).mockResolvedValueOnce({ path, format: 'yaml', raw: '', revision: '1', issues: [],
    content: { ...content, nodes, edges: [] } })
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'getBoundingClientRect')!
  Object.defineProperty(HTMLElement.prototype, 'getBoundingClientRect', {
    configurable: true,
    value(this: HTMLElement) {
      if (this.classList.contains('game-flow-token-shape')) {
        return this.dataset.shape === 'note' ? new DOMRect(8, 35, 140, 64) : new DOMRect(46, 35, 64, 64)
      }
      return original.value.call(this)
    },
  })
  try {
    const { container } = render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
    const action = await screen.findByRole('group', { name: '操作 買い物する' })
    await waitFor(() => {
      const viewport = container.querySelector<HTMLElement>('.react-flow__viewport')?.style.transform
      expect(viewport).toContain('scale(1)')
      expect(viewport).not.toBe('translate(0px,0px) scale(1)')
    })
    const view = action.ownerDocument.defaultView!
    const position = () => action.style.transform
    const guide = () => container.querySelector('.game-flow-snap-guides line')
    const mouse = (target: HTMLElement | Window, type: string, clientX: number, clientY: number, buttons: number) => {
      const event = new view.MouseEvent(type, { clientX, clientY, buttons, bubbles: true, cancelable: true })
      // jsdom の MouseEvent コンストラクターがテスト環境の Window を変換できないため、D3 の参照先を与える。
      Object.defineProperty(event, 'view', { value: view })
      fireEvent(target, event)
    }
    const move = (x: number, y: number) => mouse(view, 'mousemove', x, y, 1)
    mouse(action, 'mousedown', 200, 150, 1)
    move(202, 150)
    try {
      move(373, 250)
      await waitFor(() => expect(position()).toBe('translate(178px,100px)'))
      expect(guide()).toHaveAttribute('x1', '256')
      expect(screen.getByRole('button', { name: '元に戻す' })).toBeDisabled()

      // The pointer is still in the same 10-unit grid cell after leaving the 8px attraction.
      move(371, 250)
      await waitFor(() => expect(position()).toBe('translate(170px,100px)'))
      expect(guide()).toBeNull()
      move(373, 250)
      await waitFor(() => expect(position()).toBe('translate(178px,100px)'))
    } finally {
      mouse(view, 'mouseup', 373, 250, 0)
    }

    expect(position()).toBe('translate(178px,100px)')
    expect(guide()).toBeNull()
    await user.click(screen.getByRole('button', { name: '元に戻す' }))
    expect(position()).toBe('translate(0px,0px)')
    expect(screen.getByRole('button', { name: '元に戻す' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'やり直す' }))
    expect(position()).toBe('translate(178px,100px)')
    await user.click(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(masterDataApi.save).toHaveBeenCalled())
    const saved = JSON.parse(vi.mocked(masterDataApi.save).mock.lastCall![1])
    expect(saved.nodes.find((node: { id: string }) => node.id === 'buy')).toMatchObject({ x: 178, y: 100 })

    fireEvent.click(action)
    act(() => action.focus())
    await user.keyboard('{ArrowRight}')
    expect(position()).toBe('translate(190px,100px)')
    const saveButton = screen.getByRole('button', { name: '保存' })
    expect(saveButton).toBeEnabled()
    await user.click(saveButton)
    await waitFor(() => expect(masterDataApi.save).toHaveBeenCalledTimes(2))
    const moved = JSON.parse(vi.mocked(masterDataApi.save).mock.lastCall![1])
    expect(moved.nodes.find((node: { id: string }) => node.id === 'buy')).toMatchObject({ x: 190, y: 100 })
  } finally {
    Object.defineProperty(HTMLElement.prototype, 'getBoundingClientRect', original)
  }
})

it('図上で参照ノードの表示名を編集し、IMEと取り消しを保ち、空欄で参照名へ戻す', async () => {
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const npc = await screen.findByRole('group', { name: 'NPC 受付へ行く' })
  fireEvent.doubleClick(npc)
  const field = screen.getByRole('textbox', { name: 'ノードの表示名' })
  expect(field).toHaveFocus()
  expect(field).toHaveValue('受付へ行く')
  fireEvent.compositionStart(field)
  fireEvent.change(field, { target: { value: '雑貨商と話す' } })
  fireEvent.keyDown(field, { key: 'Enter', isComposing: true })
  fireEvent.keyDown(field, { key: 'Delete' })
  expect(field).toHaveFocus()
  expect(npc).toBeInTheDocument()
  fireEvent.compositionEnd(field)
  fireEvent.keyDown(field, { key: 'Enter' })
  expect(await screen.findByRole('group', { name: 'NPC 雑貨商と話す' })).toBeVisible()
  const undo = screen.getByRole('button', { name: '元に戻す' })
  fireEvent.click(undo)
  const original = await screen.findByRole('group', { name: 'NPC 受付へ行く' })
  expect(undo).toBeDisabled()

  fireEvent.click(original)
  fireEvent.keyDown(original, { key: 'F2' })
  const secondEdit = screen.getByRole('textbox', { name: 'ノードの表示名' })
  fireEvent.change(secondEdit, { target: { value: '取消す内容' } })
  fireEvent.keyDown(secondEdit, { key: 'Escape' })
  expect(original).toHaveAttribute('aria-label', 'NPC 受付へ行く')
  expect(undo).toBeDisabled()

  fireEvent.keyDown(original, { key: 'F2' })
  const thirdEdit = screen.getByRole('textbox', { name: 'ノードの表示名' })
  fireEvent.change(thirdEdit, { target: { value: '' } })
  fireEvent.keyDown(thirdEdit, { key: 'Enter' })
  const reference = await screen.findByRole('group', { name: 'NPC 雑貨商' })
  fireEvent.keyDown(reference, { key: 'F2' })
  const inherited = screen.getByRole<HTMLInputElement>('textbox', { name: 'ノードの表示名' })
  expect(inherited).toHaveValue('雑貨商')
  expect(inherited.selectionStart).toBe(0)
  expect(inherited.selectionEnd).toBe('雑貨商'.length)
  fireEvent.keyDown(inherited, { key: 'Enter' })
  fireEvent.click(undo)
  expect(await screen.findByRole('group', { name: 'NPC 受付へ行く' })).toBeVisible()
  expect(undo).toBeDisabled()
})

it('矢印の直接編集を表示切替の前に確定する', async () => {
  const originalBBox = Object.getOwnPropertyDescriptor(SVGElement.prototype, 'getBBox')
  Object.defineProperty(SVGElement.prototype, 'getBBox', {
    configurable: true, value: () => ({ x: 0, y: 0, width: 40, height: 12 }),
  })
  try {
    render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
    const edge = await screen.findByTestId('rf__edge-memo->npc')
    fireEvent.doubleClick(edge, { clientX: 120, clientY: 160 })
    const field = screen.getByRole('textbox', { name: '矢印のラベル' })
    expect(field).toHaveFocus()
    fireEvent.change(field, { target: { value: '入店の前提' } })
    fireEvent.click(screen.getByRole('tab', { name: 'テキスト' }))
    expect(screen.queryByRole('textbox', { name: '矢印のラベル' })).not.toBeInTheDocument()
    expect(screen.getByText(/— 受付へ行く（入店の前提）/)).toBeVisible()
    fireEvent.click(screen.getByRole('tab', { name: '図' }))
    expect(within(edge).getByText('入店の前提')).toBeVisible()
  } finally {
    if (originalBBox) Object.defineProperty(SVGElement.prototype, 'getBBox', originalBBox)
    else Reflect.deleteProperty(SVGElement.prototype, 'getBBox')
  }
})

it('空白から付箋を作り、取消は履歴に残さず、改行した本文を保存できる', async () => {
  const user = userEvent.setup()
  const workspace = render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  await screen.findByRole('group', { name: 'メモ 旧メモ' })
  const pane = workspace.container.querySelector('.react-flow__pane')!
  const createNote = () => fireEvent.doubleClick(pane, { clientX: 420, clientY: 320 })
  createNote()
  await user.type(screen.getByRole('textbox', { name: '付箋の本文' }), '取消すメモ')
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('group', { name: 'メモ 取消すメモ' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '元に戻す' })).toBeDisabled()

  createNote()
  const field = screen.getByRole('textbox', { name: '付箋の本文' })
  await user.type(field, '一行目')
  await user.keyboard('{Shift>}{Enter}{/Shift}')
  await user.type(field, '二行目')
  expect(field).toHaveValue('一行目\n二行目')
  const saveButton = screen.getByRole('button', { name: '保存' })
  expect(saveButton).toBeEnabled()
  fireEvent.click(saveButton)
  await waitFor(() => expect(masterDataApi.save).toHaveBeenCalledTimes(1))
  const saved = JSON.parse(vi.mocked(masterDataApi.save).mock.lastCall![1])
  expect(saved.nodes.at(-1)).toMatchObject({ label: '一行目\n二行目' })
  expect(saved.nodes).toHaveLength(content.nodes.length + 1)
})

it('別フローを新しい図の寸法で全体表示し、同じフローの保存では視点を維持する', async () => {
  const smallPath = gameFlowPath('small')
  const emptyPath = gameFlowPath('z_empty')
  const otherFlows = [
    { ...fileMetadata, path: smallPath, category: gameFlowCategory, id: 'small', name: '小さいフロー' },
    { ...fileMetadata, path: emptyPath, category: gameFlowCategory, id: 'z_empty', name: '空のフロー' },
  ]
  vi.mocked(masterDataApi.files).mockResolvedValue([...(await masterDataApi.files()), ...otherFlows])
  vi.mocked(masterDataApi.file).mockImplementation(async requestedPath => ({
    path: requestedPath, format: 'yaml', raw: '', revision: '1', issues: [],
    content: requestedPath === emptyPath ? { id: 'z_empty', name: '空のフロー', nodes: [], edges: [] }
      : requestedPath === smallPath ? { id: 'small', name: '小さいフロー', nodes: [{ ...content.nodes[0], label: '共通IDの操作' }], edges: [] }
        : { ...content, nodes: content.nodes.map(node => node.id === 'memo' ? { ...node, x: 1600 } : node) },
  }))
  vi.mocked(masterDataApi.save).mockImplementation(async (savedPath, raw) => ({
    path: savedPath, format: 'yaml', raw, revision: '2', issues: [], content: JSON.parse(raw) as JsonValue,
  }))
  const { container } = render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const transform = () => container.querySelector<HTMLElement>('.react-flow__viewport')?.style.transform
  const zoom = () => Number(transform()?.match(/scale\(([^)]+)\)/)?.[1])
  await screen.findByRole('group', { name: '操作 買い物する' })
  await waitFor(() => expect(zoom()).toBeLessThan(1))
  fireEvent.click(screen.getByRole('button', { name: /小さいフロー/ }))
  const node = await screen.findByRole('group', { name: '操作 共通IDの操作' })
  await waitFor(() => expect(zoom()).toBe(1))
  fireEvent.click(container.querySelector('.react-flow__controls-zoomout')!)
  await waitFor(() => expect(zoom()).toBeLessThan(1))
  const manualViewport = transform()
  fireEvent.doubleClick(node)
  fireEvent.change(screen.getByRole('textbox', { name: 'ノードの表示名' }), { target: { value: '保存する操作' } })
  fireEvent.click(screen.getByRole('button', { name: '保存' }))
  await screen.findByText(`${smallPath} を保存しました。`)
  expect(transform()).toBe(manualViewport)
  fireEvent.click(screen.getByRole('button', { name: /空のフロー/ }))
  await waitFor(() => expect(transform()).toBe('translate(0px,0px) scale(1)'))
  expect(screen.queryByRole('group', { name: '操作 保存する操作' })).not.toBeInTheDocument()
})

it.each(['破棄', '再読込'])('%s の応答を待つ間は古い付箋を編集できない', async command => {
  const { container } = render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const note = await screen.findByRole('group', { name: 'メモ 旧メモ' })
  fireEvent.doubleClick(note)
  const field = screen.getByRole('textbox', { name: '付箋の本文' })
  fireEvent.change(field, { target: { value: '破棄するメモ' } })
  fireEvent.keyDown(field, { key: 'Enter' })
  const selected = await screen.findByRole('group', { name: 'メモ 破棄するメモ' })
  expect(selected.querySelector('.game-flow-note-resize')).toBeInTheDocument()
  const inspector = screen.getByRole('complementary', { name: 'プロパティ' })
  const width = within(inspector).getByRole('textbox', { name: '幅' })
  const height = within(inspector).getByRole('textbox', { name: '高さ' })
  let finishRead: (() => void) | undefined
  vi.mocked(masterDataApi.file).mockImplementationOnce(() => new Promise(resolve => {
    finishRead = () => resolve({ path, format: 'yaml', raw: '', revision: '2', issues: [], content: {
      ...content, nodes: content.nodes.map(node => node.id === 'memo' ? { ...node, label: '読み込み後のメモ' } : node),
    } })
  }))
  fireEvent.click(screen.getByRole('button', { name: command }))
  fireEvent.click(within(await screen.findByRole('dialog', { name: '未保存の編集内容' })).getByRole('button', { name: '変更を破棄' }))
  await waitFor(() => expect(finishRead).toBeTypeOf('function'))
  await waitFor(() => expect(selected.querySelector('.game-flow-note-resize')).not.toBeInTheDocument())
  expect(inspector).toHaveAttribute('inert')
  expect(container.querySelector('.game-flow-main')).toHaveAttribute('inert')
  fireEvent.change(width, { target: { value: '240' } })
  fireEvent.keyDown(width, { key: 'Enter' })
  fireEvent.change(height, { target: { value: '120' } })
  fireEvent.blur(height)
  expect(width).toHaveValue('')
  expect(height).toHaveValue('')
  fireEvent.keyDown(selected, { key: 'F2' })
  fireEvent.doubleClick(selected)
  fireEvent.doubleClick(container.querySelector('.react-flow__pane')!)
  expect(screen.queryByRole('textbox', { name: '付箋の本文' })).not.toBeInTheDocument()

  await act(async () => finishRead?.())
  const updated = await screen.findByRole('group', { name: 'メモ 読み込み後のメモ' })
  fireEvent.doubleClick(updated)
  expect(screen.getByRole('textbox', { name: '付箋の本文' })).toHaveValue('読み込み後のメモ')
})

it('同じフローの保存中に始めた図上編集を応答後も保持する', async () => {
  render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const node = await screen.findByRole('group', { name: '操作 買い物する' })
  fireEvent.doubleClick(node)
  const first = screen.getByRole('textbox', { name: 'ノードの表示名' })
  fireEvent.change(first, { target: { value: '保存する操作' } })
  fireEvent.keyDown(first, { key: 'Enter' })
  let finishSave: (() => void) | undefined
  vi.mocked(masterDataApi.save).mockImplementationOnce((savedPath, raw) => new Promise(resolve => {
    finishSave = () => resolve({ path: savedPath, format: 'yaml', raw, revision: '2', issues: [], content: JSON.parse(raw) as JsonValue })
  }))
  fireEvent.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(finishSave).toBeTypeOf('function'))
  const pending = screen.getByRole('group', { name: '操作 保存する操作' })
  fireEvent.keyDown(pending, { key: 'F2' })
  const second = screen.getByRole('textbox', { name: 'ノードの表示名' })
  fireEvent.change(second, { target: { value: '保存中に追記した操作' } })
  await act(async () => finishSave?.())
  expect(second).toHaveValue('保存中に追記した操作')
  expect(second).toHaveFocus()
  fireEvent.keyDown(second, { key: 'Enter' })
  expect(await screen.findByRole('group', { name: '操作 保存中に追記した操作' })).toBeVisible()
  expect(screen.getByRole('button', { name: '保存' })).toBeEnabled()
})

it('保存応答をまたぐ付箋のリサイズを一度の編集として確定する', async () => {
  const { container } = render(<GameFlowWorkspace onOpenMaster={vi.fn()} />)
  const note = await screen.findByRole('group', { name: 'メモ 旧メモ' })
  await waitFor(() => {
    const viewport = container.querySelector<HTMLElement>('.react-flow__viewport')?.style.transform
    expect(viewport).toContain('scale(1)')
    expect(viewport).not.toBe('translate(0px,0px) scale(1)')
  })
  fireEvent.doubleClick(note)
  const field = screen.getByRole('textbox', { name: '付箋の本文' })
  fireEvent.change(field, { target: { value: '保存するメモ' } })
  fireEvent.keyDown(field, { key: 'Enter' })

  let finishSave: (() => void) | undefined
  vi.mocked(masterDataApi.save).mockImplementationOnce((savedPath, raw) => new Promise(resolve => {
    finishSave = () => resolve({ path: savedPath, format: 'yaml', raw, revision: '2', issues: [], content: JSON.parse(raw) as JsonValue })
  }))
  fireEvent.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(finishSave).toBeTypeOf('function'))
  const handle = screen.getByRole('button', { name: '付箋のサイズを変更' })
  const token = handle.closest('.game-flow-token')
  const width = screen.getByRole('textbox', { name: '幅' })
  const height = screen.getByRole('textbox', { name: '高さ' })
  fireEvent.pointerDown(handle, { button: 0, pointerId: 4, clientX: 100, clientY: 100 })
  fireEvent.pointerMove(window, { pointerId: 4, clientX: 120, clientY: 110 })
  expect(token).toHaveStyle({ '--note-width': '160px', '--note-height': '74px' })
  expect(width).toHaveValue('')
  expect(height).toHaveValue('')

  await act(async () => finishSave?.())
  expect(handle).toBeInTheDocument()
  expect(token).toHaveStyle({ '--note-width': '160px', '--note-height': '74px' })
  fireEvent.pointerUp(window, { pointerId: 4, clientX: 140, clientY: 120 })
  expect(width).toHaveValue('180')
  expect(height).toHaveValue('84')
  const save = screen.getByRole('button', { name: '保存' })
  expect(save).toBeEnabled()

  fireEvent.click(screen.getByRole('button', { name: '元に戻す' }))
  expect(width).toHaveValue('')
  expect(height).toHaveValue('')
  expect(save).toBeDisabled()
  expect(await screen.findByRole('group', { name: 'メモ 保存するメモ' })).toBeVisible()
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
