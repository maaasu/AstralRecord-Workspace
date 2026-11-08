import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { spawnerPlanningApi } from '../api/spawnerPlanningApi'
import type { PlacementSnapshot, WorldPlacement } from '../api/worldPlacementApi'
import type { PlanningSnapshot } from '../data/spawnerPlanning'
import { SpawnerPlanningWorkspace } from './SpawnerPlanningWorkspace'

vi.mock('../api/spawnerPlanningApi', () => ({ spawnerPlanningApi: { snapshot: vi.fn(), plan: vi.fn(), worlds: vi.fn() } }))
vi.mock('./SpawnerPlanningMap', () => ({ SpawnerPlanningMap: () => <span>取り込み済みの地図</span> }))

const map: PlanningSnapshot = {
  manifest: {
    schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'terrain', exportRevision: 'map-rev',
    previewOnly: true, bounds: { minX: 0, minZ: 0, width: 16, height: 16, cellSize: 16 }, spawn: { x: 4, y: 70, z: 8 },
    capturedAt: '', radiusOptions: [], reasons: {}, limitations: [], progressionPolicy: {},
  },
  cells: [],
  regions: [],
}

function waystone(id: string, name: string, worldId: string, enabled: boolean): WorldPlacement {
  return {
    placementId: `${id}-placement`, kind: 'waystone', worldId, worldName: `worlds/${worldId}`, templateId: null,
    x: 4, y: 70, z: 8, yaw: 45, pitch: 10, enabled,
    waystone: { id, name, lockEnabled: false, unlockGold: 0, icon: 'LODESTONE', createdAt: null, createdBy: 'system' },
  }
}

function mockPlacementApi(placements: WorldPlacement[]) {
  let saved: PlacementSnapshot = { schemaVersion: 1, revision: 'rev-1', placements }
  const read = vi.fn(() => Response.json(saved))
  const write = vi.fn((items: WorldPlacement[]) => {
    saved = { ...saved, revision: 'rev-2', placements: items }
    return Response.json(saved)
  })
  const writeOne = vi.fn((expectedRevision: string, placement: WorldPlacement) => {
    if (expectedRevision !== saved.revision) return Response.json({}, { status: 409 })
    saved = { ...saved, revision: 'rev-2', placements: saved.placements.map(item => item.placementId === placement.placementId ? placement : item) }
    return Response.json(saved)
  })
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/world-placements/catalog') {
      return Response.json({ spawners: [], npcs: [], worlds: [{ id: 'eriva', name: 'エリヴァ', baseWorldPath: 'worlds/eriva' }] })
    }
    if (url === '/api/world-placements/dev' && options?.method === 'GET') return read()
    if (url === '/api/world-placements/dev' && options?.method === 'PUT') return write(JSON.parse(String(options.body)).placements)
    if (url.startsWith('/api/world-placements/dev/') && options?.method === 'PUT') {
      const { expectedRevision, placement } = JSON.parse(String(options.body))
      expect(url).toBe(`/api/world-placements/dev/${encodeURIComponent(placement.placementId)}`)
      return writeOne(expectedRevision, placement)
    }
    throw new Error(`Unexpected request ${options?.method ?? 'GET'} ${url}`)
  }))
  return {
    read,
    write,
    writeOne,
    changeInGame: (items: WorldPlacement[]) => { saved = { ...saved, revision: 'rev-game', placements: items } },
  }
}

const openList = () => fireEvent.click(screen.getByRole('button', { name: 'テレポーター一覧' }))
const reloadList = () => fireEvent.click(screen.getByRole('button', { name: '再読込' }))

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(spawnerPlanningApi.worlds).mockResolvedValue([])
  vi.mocked(spawnerPlanningApi.snapshot).mockRejectedValue(new Error('地図がありません'))
})
afterEach(() => vi.unstubAllGlobals())

it('地図がなくても保存済みの道標だけを表示名順に読み、検索と絞り込みを組み合わせられる', async () => {
  const west = waystone('west_gate', '&a西門', 'eriva', true)
  const east = waystone('east_gate', 'あさひの門', 'verdia', false)
  const { read, write } = mockPlacementApi([west, { ...west, placementId: 'npc-placement', kind: 'npc', templateId: 'npc', waystone: null }, east])

  render(<SpawnerPlanningWorkspace active />)
  expect(await screen.findByRole('alert')).toHaveTextContent('地図がありません')
  openList()

  const table = await screen.findByRole('table')
  expect(within(table).getAllByRole('button', { expanded: false }).map(button => button.textContent)).toEqual(['あさひの門', '西門'])
  expect(within(table).getByRole('row', { name: /あさひの門/ })).toHaveTextContent('無効')
  expect(within(table).getByRole('row', { name: /西門/ })).toHaveTextContent('有効')
  expect(screen.queryByText('取り込み済みの地図')).not.toBeInTheDocument()

  const search = screen.getByRole('searchbox', { name: '名前・道標IDで検索' })
  const world = screen.getByRole('combobox', { name: 'ワールド' })
  const status = screen.getByRole('combobox', { name: '有効状態' })
  fireEvent.change(search, { target: { value: 'WEST_GATE' } })
  expect(within(table).queryByRole('button', { name: 'あさひの門' })).not.toBeInTheDocument()
  fireEvent.change(world, { target: { value: 'verdia' } })
  expect(screen.queryByRole('button', { name: '西門' })).not.toBeInTheDocument()
  fireEvent.change(world, { target: { value: 'eriva' } })
  fireEvent.change(status, { target: { value: 'false' } })
  expect(screen.queryByRole('button', { name: '西門' })).not.toBeInTheDocument()
  fireEvent.change(status, { target: { value: 'true' } })
  fireEvent.change(search, { target: { value: '西門' } })
  fireEvent.click(screen.getByRole('button', { name: '西門' }))
  expect(screen.getByText('&a西門')).toBeVisible()
  expect(screen.getByText('west_gate')).toBeVisible()
  expect(screen.queryByRole('button', { name: '配置編集で開く' })).not.toBeInTheDocument()
  expect(read).toHaveBeenCalledOnce()
  expect(write).not.toHaveBeenCalled()
})

it('地図なしで選んだ道標の設定だけを編集して Dev に保存し、一覧の結果を更新する', async () => {
  const gate = waystone('gate', '既存の門', 'eriva', true)
  gate.waystone!.createdAt = '2026-01-02T03:04:05Z'
  const other = waystone('other', '別の門', 'other', false)
  const { write, writeOne } = mockPlacementApi([gate, other])

  render(<SpawnerPlanningWorkspace active />)
  openList()
  fireEvent.click(await screen.findByRole('button', { name: '既存の門を編集' }))
  expect(screen.getByRole('textbox', { name: '表示名' })).toHaveFocus()
  expect(screen.getByRole('button', { name: 'Dev に保存' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '画像一覧から選択' })).toBeVisible()
  expect(screen.queryByRole('textbox', { name: /道標ID/ })).not.toBeInTheDocument()

  fireEvent.change(screen.getByRole('textbox', { name: '表示名' }), { target: { value: '&b編集した門' } })
  fireEvent.change(screen.getByLabelText('X', { exact: true }), { target: { value: '-12.5' } })
  fireEvent.change(screen.getByLabelText('Y（高さ）'), { target: { value: '71' } })
  fireEvent.change(screen.getByLabelText('Z', { exact: true }), { target: { value: '8.25' } })
  fireEvent.change(screen.getByLabelText('Yaw（左右の向き）'), { target: { value: '180' } })
  fireEvent.change(screen.getByLabelText('Pitch（上下の向き）'), { target: { value: '-5' } })
  fireEvent.click(screen.getByLabelText('配置を有効にする'))
  fireEvent.click(screen.getByLabelText('解放条件を使用'))
  fireEvent.change(screen.getByLabelText('解放費用'), { target: { value: '5000' } })
  fireEvent.click(screen.getByRole('button', { name: '画像一覧から選択' }))
  const picker = await screen.findByRole('dialog', { name: '素材アイコンを選択' })
  fireEvent.change(within(picker).getByRole('searchbox'), { target: { value: 'diamond' } })
  const diamond = within(picker).getByRole('button', { name: 'DIAMOND' })
  expect(diamond.querySelector('img')).toHaveAttribute('src', '/api/minecraft-icons/DIAMOND?revision=0')
  fireEvent.click(diamond)
  expect(screen.getByLabelText('アイコン', { selector: 'input' })).toHaveValue('DIAMOND')
  expect(writeOne).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))

  expect(await screen.findByText(/Dev にテレポーターを保存しました/)).toBeVisible()
  expect(writeOne).toHaveBeenCalledExactlyOnceWith('rev-1', {
    ...gate, x: -12.5, y: 71, z: 8.25, yaw: 180, pitch: -5, enabled: false,
    waystone: { ...gate.waystone, name: '&b編集した門', lockEnabled: true, unlockGold: 5000, icon: 'DIAMOND' },
  })
  expect(write).not.toHaveBeenCalled()
  const row = screen.getByRole('row', { name: /編集した門/ })
  expect(row).toHaveTextContent('5,000 ゴールド')
  expect(row).toHaveTextContent('DIAMOND')
  expect(screen.getByRole('button', { name: '編集した門を編集' })).toHaveFocus()
  expect(screen.getByRole('button', { name: '別の門を編集' })).toBeVisible()
})

it('競合後は原稿を保持し、競合した項目の値を選ぶまで上書きしない', async () => {
  const gate = waystone('gate', '既存の門', 'eriva', true)
  const { read, writeOne, changeInGame } = mockPlacementApi([gate])

  render(<SpawnerPlanningWorkspace active />)
  openList()
  fireEvent.click(await screen.findByRole('button', { name: '既存の門を編集' }))
  fireEvent.change(screen.getByRole('textbox', { name: '表示名' }), { target: { value: '入力した名前' } })
  fireEvent.change(screen.getByLabelText('アイコン', { selector: 'input' }), { target: { value: 'GOLD_INGOT' } })
  changeInGame([{ ...gate, x: 20, waystone: { ...gate.waystone!, name: 'Dev で変更した名前' } }])
  fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('入力内容は保持されています')
  expect(screen.getByRole('textbox', { name: '表示名' })).toHaveValue('入力した名前')
  expect(screen.getByRole('button', { name: 'Dev に保存' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '最新の設定を確認' }))

  expect(await screen.findByText('Dev で変更した名前', { selector: 'code' })).toBeVisible()
  expect(read).toHaveBeenCalledTimes(2)
  expect(writeOne).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button', { name: 'Dev に保存' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '競合項目に Dev の値を使う' }))
  expect(screen.getByRole('textbox', { name: '表示名' })).toHaveValue('Dev で変更した名前')
  expect(screen.getByLabelText('X', { exact: true })).toHaveValue('20')
  expect(screen.getByLabelText('アイコン', { selector: 'input' })).toHaveValue('GOLD_INGOT')
  fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))

  expect(await screen.findByText(/Dev にテレポーターを保存しました/)).toBeVisible()
  expect(writeOne).toHaveBeenLastCalledWith('rev-game', {
    ...gate, x: 20, waystone: { ...gate.waystone, name: 'Dev で変更した名前', icon: 'GOLD_INGOT' },
  })
})

it('競合中に削除された道標を再作成せず、元の原稿を確認できる', async () => {
  const { writeOne, changeInGame } = mockPlacementApi([waystone('gate', '既存の門', 'eriva', true)])

  render(<SpawnerPlanningWorkspace active />)
  openList()
  fireEvent.click(await screen.findByRole('button', { name: '既存の門を編集' }))
  fireEvent.change(screen.getByRole('textbox', { name: '表示名' }), { target: { value: '入力した名前' } })
  changeInGame([])
  fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
  fireEvent.click(await screen.findByRole('button', { name: '最新の設定を確認' }))

  expect(await screen.findByText(/元のテレポーターは削除されたか/)).toBeVisible()
  expect(screen.getByRole('textbox', { name: '表示名' })).toHaveValue('入力した名前')
  expect(screen.getByRole('button', { name: 'Dev に保存' })).toBeDisabled()
  expect(screen.queryByRole('button', { name: '既存の門を編集' })).not.toBeInTheDocument()
  expect(writeOne).toHaveBeenCalledTimes(1)
})

it('座標の空欄と安全整数外の保存済み解放費用を拒み、書き込み失敗後も原稿を維持する', async () => {
  const gate = waystone('gate', '既存の門', 'eriva', true)
  gate.waystone!.unlockGold = Number.MAX_SAFE_INTEGER + 1
  const { writeOne } = mockPlacementApi([gate])
  writeOne.mockReturnValueOnce(Response.json({ message: '接続を確認してください' }, { status: 503 }))

  render(<SpawnerPlanningWorkspace active />)
  openList()
  fireEvent.click(await screen.findByRole('button', { name: '既存の門を編集' }))
  fireEvent.change(screen.getByLabelText('X', { exact: true }), { target: { value: '' } })
  fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
  expect(screen.getByRole('alert')).toHaveTextContent('X は')
  expect(screen.getByLabelText('X', { exact: true })).toHaveValue('')
  expect(writeOne).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('X', { exact: true }), { target: { value: '20' } })
  fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
  expect(screen.getByRole('alert')).toHaveTextContent('解放費用は')
  expect(writeOne).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('解放費用'), { target: { value: '1000' } })
  fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('接続を確認してください')
  expect(screen.getByLabelText('解放費用')).toHaveValue('1000')
  expect(screen.getByRole('button', { name: 'Dev に保存' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
  expect(await screen.findByText(/Dev にテレポーターを保存しました/)).toBeVisible()
  expect(writeOne).toHaveBeenCalledTimes(2)
})

it('地図で追加中や未保存の原稿を保持し、Dev に登録した後は一覧を再読込できる', async () => {
  vi.mocked(spawnerPlanningApi.worlds).mockResolvedValue([{ id: 'eriva', name: 'エリヴァ' }])
  vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(map)
  const { read, write } = mockPlacementApi([waystone('gate', '既存の門', 'eriva', true)])

  render(<SpawnerPlanningWorkspace active />)
  openList()
  expect(await screen.findByRole('button', { name: '既存の門' })).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
  const kind = await screen.findByRole('combobox', { name: '追加する種類' })
  await waitFor(() => expect(kind).toBeEnabled())
  fireEvent.change(kind, { target: { value: 'waystone' } })
  fireEvent.click(screen.getByRole('button', { name: '地図で追加' }))
  openList()
  expect(screen.getByRole('button', { name: '既存の門を編集' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
  const mapAdd = screen.getByRole('button', { name: '地図で追加' })
  expect(mapAdd).toHaveAttribute('aria-pressed', 'true')
  expect(kind).toHaveValue('waystone')
  fireEvent.click(mapAdd)
  fireEvent.click(screen.getByText('追加オプション'))
  fireEvent.click(screen.getByRole('button', { name: '座標を入力して追加' }))
  fireEvent.change(screen.getByLabelText('道標 ID'), { target: { value: 'new_gate' } })
  fireEvent.change(screen.getByRole('textbox', { name: '表示名' }), { target: { value: '新しい門' } })

  openList()
  const readCount = read.mock.calls.length
  expect(screen.queryByRole('button', { name: '新しい門' })).not.toBeInTheDocument()
  expect(write).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
  expect(screen.getByRole('textbox', { name: '表示名' })).toHaveValue('新しい門')
  fireEvent.click(screen.getByRole('button', { name: 'Dev に保存' }))
  expect(await screen.findByText(/Dev 配置を保存しました/)).toBeVisible()
  expect(write).toHaveBeenCalledOnce()

  openList()
  expect(screen.queryByRole('button', { name: '新しい門' })).not.toBeInTheDocument()
  expect(read).toHaveBeenCalledTimes(readCount)
  reloadList()
  fireEvent.click(await screen.findByRole('button', { name: '新しい門' }))
  expect(read).toHaveBeenCalledTimes(readCount + 1)
  fireEvent.click(screen.getByRole('button', { name: '配置編集で開く' }))
  expect(await screen.findByLabelText('道標 ID')).toHaveValue('new_gate')
  expect(write).toHaveBeenCalledOnce()
})

it('ゲーム内で登録した道標を手動で再読込し、同じワールドの配置編集で選択できる', async () => {
  vi.mocked(spawnerPlanningApi.worlds).mockResolvedValue([{ id: 'eriva', name: 'エリヴァ' }])
  vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(map)
  const existing = waystone('gate', '既存の門', 'eriva', true)
  const { read, write, changeInGame } = mockPlacementApi([existing])
  const newId = 'ws-55fd9104-4b90-4bc4-a5b9-720cc38094e0'
  const added = waystone(newId, '&aゲーム内で登録した門', 'eriva', true)
  added.waystone = { ...added.waystone!, unlockGold: 15000, lockEnabled: true }

  render(<SpawnerPlanningWorkspace active />)
  expect(await screen.findByRole('button', { name: /既存の門.*道標/ })).toBeVisible()
  openList()
  expect(await screen.findByRole('button', { name: '既存の門' })).toBeVisible()
  const readCount = read.mock.calls.length
  const updated = { ...existing, waystone: { ...existing.waystone!, unlockGold: 5000, lockEnabled: true } }
  changeInGame([updated, added])
  expect(screen.queryByRole('button', { name: 'ゲーム内で登録した門' })).not.toBeInTheDocument()
  reloadList()

  const newEntry = await screen.findByRole('button', { name: 'ゲーム内で登録した門' })
  expect(screen.getByRole('row', { name: /ゲーム内で登録した門/ })).toHaveTextContent('15,000 ゴールド')
  fireEvent.click(newEntry)
  expect(screen.getByText(newId)).toBeVisible()
  expect(read).toHaveBeenCalledTimes(readCount + 1)

  fireEvent.click(screen.getByRole('button', { name: '既存の門' }))
  fireEvent.click(screen.getByRole('button', { name: '配置編集で開く' }))
  await waitFor(() => expect(screen.getByLabelText('解放費用')).toHaveValue('5000'))

  openList()
  fireEvent.click(screen.getByRole('button', { name: 'ゲーム内で登録した門' }))
  fireEvent.click(screen.getByRole('button', { name: '配置編集で開く' }))
  expect(await screen.findByLabelText('道標 ID')).toHaveValue(newId)
  expect(screen.getByLabelText('解放費用')).toHaveValue('15000')
  expect(write).not.toHaveBeenCalled()
})
