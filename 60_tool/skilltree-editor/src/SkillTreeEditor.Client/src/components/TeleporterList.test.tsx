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
  vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/api/world-placements/catalog') {
      return Response.json({ spawners: [], npcs: [], worlds: [{ id: 'eriva', name: 'エリヴァ', baseWorldPath: 'worlds/eriva' }] })
    }
    if (url === '/api/world-placements/dev' && options?.method === 'GET') return read()
    if (url === '/api/world-placements/dev' && options?.method === 'PUT') return write(JSON.parse(String(options.body)).placements)
    throw new Error(`Unexpected request ${options?.method ?? 'GET'} ${url}`)
  }))
  return {
    read,
    write,
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
  expect(within(table).getAllByRole('button').map(button => button.textContent)).toEqual(['あさひの門', '西門'])
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

it('未保存の追加を一覧に混ぜず、Dev に登録後は一覧の再読込で取得し直して配置編集に戻れる', async () => {
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
  fireEvent.click(screen.getByText('追加オプション'))
  fireEvent.click(screen.getByRole('button', { name: '座標を入力して追加' }))
  fireEvent.change(screen.getByLabelText('道標 ID'), { target: { value: 'new_gate' } })
  fireEvent.change(screen.getByLabelText('表示名'), { target: { value: '新しい門' } })

  openList()
  const readCount = read.mock.calls.length
  expect(screen.queryByRole('button', { name: '新しい門' })).not.toBeInTheDocument()
  expect(write).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
  expect(screen.getByLabelText('表示名')).toHaveValue('新しい門')
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
