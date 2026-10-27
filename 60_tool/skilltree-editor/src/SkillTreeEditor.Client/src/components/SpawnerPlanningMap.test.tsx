import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SpawnerPlanningMap } from './SpawnerPlanningMap'
import type { PlanningSnapshot } from '../data/spawnerPlanning'
import type { WorldPlacement } from '../api/worldPlacementApi'

const bounds = { minX: -2400, minZ: -2400, width: 4736, height: 4688, cellSize: 16 }
const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', terrainFingerprint: 'fingerprint', exportRevision: 'revision',
    worldName: 'エリヴァ', previewOnly: true, bounds, spawn: { x: 0, y: 64, z: 0 }, capturedAt: '',
    radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} },
  cells: [], regions: [],
}
const props = { snapshot, plan: null, layer: 'terrain' as const, region: '', reason: '', radii: false,
  active: true, selectedCell: null, selectedProposal: null, focus: null, onCell: vi.fn(), onProposal: vi.fn() }

beforeEach(() => {
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 406, height: 500 } as DOMRect)
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('fits the measured narrow map, keeps zoom after resize, and falls back without a detail image', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  const { rerender } = render(<SpawnerPlanningMap {...props} />)
  expect(screen.getByText(/0\.08 px\/m/)).toHaveTextContent('16m区画')
  fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(screen.getByText(/0\.12 px\/m/)).toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...props} active={false} />)
  vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockReturnValue({ left: 0, top: 0, width: 500, height: 500 } as DOMRect)
  rerender(<SpawnerPlanningMap {...props} active />)
  expect(screen.getByText(/0\.12 px\/m/)).toHaveTextContent('16m区画')
})

it('uses matching 1m terrain once the map is close enough and can zoom to block scale', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ schemaVersion: 1,
    terrainFingerprint: 'fingerprint', minX: bounds.minX, minZ: bounds.minZ, width: bounds.width, height: bounds.height, blocksPerPixel: 1 }) }))
  vi.stubGlobal('Image', class {
    naturalWidth = bounds.width
    naturalHeight = bounds.height
    onload: (() => void) | null = null
    set src(_value: string) { setTimeout(() => this.onload?.(), 0) }
  })
  render(<SpawnerPlanningMap {...props} />)
  for (let index = 0; index < 5; index++) fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  await waitFor(() => expect(screen.getByText(/1m地形/)).toBeInTheDocument())
  for (let index = 0; index < 12; index++) fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(screen.getByText(/16\.0 px\/m/)).toBeInTheDocument()
})

it('地図のクリックは区画代表地点の X/Y/Z を配置へ渡す', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  const onPlacePoint = vi.fn()
  const local = structuredClone(snapshot)
  local.manifest.bounds = { minX: 0, minZ: 0, width: 16, height: 16, cellSize: 16 }
  local.cells = [{ x: 0, z: 0, y: 70, status: 'ok', surface: 'grass', ground: 'dirt', biome: 'plains', safeCount: 256, waterCount: 0, treeCount: 0,
    candidateX: 12, candidateY: 72, candidateZ: 13, minY: 70, maxY: 72, habitat: 'grass', regionId: 'field', targetLevel: 10, reasonCode: 'eligible', reason: '' }]
  const { container } = render(<SpawnerPlanningMap {...props} snapshot={local} placing onPlacePoint={onPlacePoint} />)
  const canvas = container.querySelector('canvas')!
  canvas.setPointerCapture = vi.fn()
  fireEvent.pointerDown(canvas, { clientX: 203, clientY: 250, pointerId: 1 })
  fireEvent.pointerUp(canvas, { clientX: 203, clientY: 250, pointerId: 1 })
  expect(onPlacePoint).toHaveBeenCalledWith(12, 72, 13)
})

it('全大陸表示でも保存済み配置の点を選択できる', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  const onPlacement = vi.fn()
  const { container } = render(<SpawnerPlanningMap {...props} showProposals={false} placements={[{ placementId: 'saved', kind: 'spawner', worldId: 'eriva', worldName: 'eriva', templateId: 'wolf', x: 0, y: 70, z: 0, yaw: 0, pitch: 0, enabled: true, waystone: null }]} onPlacement={onPlacement} />)
  const canvas = container.querySelector('canvas')!
  expect(canvas).toHaveAccessibleName(/点をクリックして配置を選択/)
  canvas.setPointerCapture = vi.fn()
  const x = 16 + (2400.5 * (374 / 4736))
  const z = (500 - 4688 * (374 / 4736)) / 2 + 2400.5 * (374 / 4736)
  fireEvent.pointerDown(canvas, { clientX: x, clientY: z, pointerId: 1 })
  fireEvent.pointerUp(canvas, { clientX: x, clientY: z, pointerId: 1 })
  expect(onPlacement).toHaveBeenCalledWith('saved')
})

it('表示条件を変えても一覧と共通の番号を保ち、実レベルと無効状態を確認できる', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  const placement: WorldPlacement = { placementId: 'wolf-point', kind: 'spawner', worldId: 'eriva', worldName: 'eriva', templateId: 'wolf', x: 0, y: 70, z: 0, yaw: 0, pitch: 0, enabled: false, waystone: null }
  const catalog = { spawners: [{ id: 'wolf', name: '&aオオカミ', radiusMeters: 64, levels: [30, 15] }, { id: 'slime', name: 'スライム', radiusMeters: 32, levels: [15] }], npcs: [], worlds: [] }
  const { rerender } = render(<SpawnerPlanningMap {...props} placementCatalog={catalog} placements={[placement, { ...placement, placementId: 'slime-point', templateId: 'slime' }, { ...placement, placementId: 'other', worldId: 'other' }]} selectedPlacement="wolf-point" />)
  expect(screen.getByText(/選択中: #2 オオカミ · Lv.15–30 · 無効/)).toBeInTheDocument()
  expect(screen.getByText(/表示中のスポナーマスター \(2\)/)).toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...props} placementCatalog={catalog} placements={[placement]} selectedPlacement="wolf-point" />)
  expect(screen.getByText(/選択中: #2 オオカミ/)).toBeInTheDocument()
  expect(screen.getByText(/表示中のスポナーマスター \(1\)/)).toBeInTheDocument()
  fireEvent.change(screen.getByRole('combobox', { name: 'スポナーの色分け' }), { target: { value: 'levels' } })
  expect(screen.getByText('複数レベル・不明')).toBeInTheDocument()
  expect(screen.getByText(/青灰: 未解析・解析失敗/)).toBeInTheDocument()
})

it('密集した点でも選択した配置を最後に描き、名前をラベルへ残す', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  const context = { setTransform: vi.fn(), clearRect: vi.fn(), drawImage: vi.fn(), beginPath: vi.fn(), arc: vi.fn(),
    moveTo: vi.fn(), lineTo: vi.fn(), closePath: vi.fn(), rect: vi.fn(), stroke: vi.fn(), fill: vi.fn(),
    fillRect: vi.fn(), strokeRect: vi.fn(), fillText: vi.fn(), measureText: () => ({ width: 120 }) }
  vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(context as unknown as CanvasRenderingContext2D)
  const placement: WorldPlacement = { placementId: 'selected', kind: 'spawner', worldId: 'eriva', worldName: 'eriva', templateId: 'wolf', x: 0, y: 70, z: 0, yaw: 0, pitch: 0, enabled: true, waystone: null }
  const mapProps = { ...props, showProposals: false, selectedPlacement: 'selected', placements: [placement, { ...placement, placementId: 'neighbor' }], placementCatalog: { spawners: [{ id: 'wolf', name: '&aオオカミ', radiusMeters: 64, levels: [15] }], npcs: [], worlds: [] } }
  const { rerender } = render(<SpawnerPlanningMap {...mapProps} />)
  const markerRadii = context.arc.mock.calls.map(call => call[2])
  expect(markerRadii.lastIndexOf(8)).toBeGreaterThan(markerRadii.lastIndexOf(2.8))
  expect(context.fillText).toHaveBeenCalledWith('#1 オオカミ · Lv.15', expect.any(Number), expect.any(Number), expect.any(Number))
  context.fillText.mockClear()
  rerender(<SpawnerPlanningMap {...mapProps} focus={{ key: 1, x: 0, z: 0 }} />)
  expect(screen.getByText(/0\.70 px\/m/)).toBeInTheDocument()
  expect(context.fillText.mock.calls.filter(call => String(call[0]).startsWith('#'))).toEqual([
    ['#1 オオカミ · Lv.15', expect.any(Number), expect.any(Number), expect.any(Number)],
  ])
})

it('カタログ外マスターは選択時と拡大時の描画ラベルも番号不明を表示する', () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  const context = { setTransform: vi.fn(), clearRect: vi.fn(), drawImage: vi.fn(), beginPath: vi.fn(), arc: vi.fn(),
    moveTo: vi.fn(), lineTo: vi.fn(), closePath: vi.fn(), rect: vi.fn(), stroke: vi.fn(), fill: vi.fn(),
    fillRect: vi.fn(), strokeRect: vi.fn(), fillText: vi.fn(), measureText: () => ({ width: 180 }) }
  vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(context as unknown as CanvasRenderingContext2D)
  const placement: WorldPlacement = { placementId: 'unknown', kind: 'spawner', worldId: 'eriva', worldName: 'eriva', templateId: 'missing-spawner', x: 0, y: 70, z: 0, yaw: 0, pitch: 0, enabled: true, waystone: null }
  const mapProps = { ...props, showProposals: false, placements: [placement], placementCatalog: { spawners: [], npcs: [], worlds: [] } }
  const { rerender } = render(<SpawnerPlanningMap {...mapProps} selectedPlacement="unknown" />)
  expect(context.fillText).toHaveBeenCalledWith('#? missing-spawner · Lv.不明', expect.any(Number), expect.any(Number), expect.any(Number))
  expect(screen.getByText(/選択中: #\? missing-spawner/)).toBeInTheDocument()
  context.fillText.mockClear()
  rerender(<SpawnerPlanningMap {...mapProps} selectedPlacement={null} focus={{ key: 1, x: 0, z: 0 }} />)
  for (let index = 0; index < 3; index++) fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(context.fillText).toHaveBeenCalledWith('#? Lv.不明', expect.any(Number), expect.any(Number), expect.any(Number))
})
