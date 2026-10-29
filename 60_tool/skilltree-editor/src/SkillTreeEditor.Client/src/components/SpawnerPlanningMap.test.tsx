import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SpawnerPlanningMap } from './SpawnerPlanningMap'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'
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

it('詳細地形は表示中の地形レイヤーを拡大したときだけ取得し、往復しても画像を再取得しない', async () => {
  const request = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ schemaVersion: 1,
    terrainFingerprint: 'fingerprint', ...bounds, blocksPerPixel: 1 }) })
  const sources: string[] = []
  vi.stubGlobal('fetch', request)
  vi.stubGlobal('Image', class {
    naturalWidth = bounds.width; naturalHeight = bounds.height
    onload: (() => void) | null = null
    set src(value: string) { sources.push(value); if (value) queueMicrotask(() => this.onload?.()) }
  })
  const { rerender } = render(<SpawnerPlanningMap {...props} active={false} />)
  expect(request).not.toHaveBeenCalled()
  rerender(<SpawnerPlanningMap {...props} />)
  expect(request).not.toHaveBeenCalled()
  rerender(<SpawnerPlanningMap {...props} layer="levels" />)
  for (let index = 0; index < 5; index++) fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(request).not.toHaveBeenCalled()
  rerender(<SpawnerPlanningMap {...props} layer="reasons" />)
  expect(request).not.toHaveBeenCalled()
  rerender(<SpawnerPlanningMap {...props} active={false} />)
  expect(request).not.toHaveBeenCalled()
  rerender(<SpawnerPlanningMap {...props} />)
  await waitFor(() => expect(screen.getByText(/1m地形/)).toBeInTheDocument())
  rerender(<SpawnerPlanningMap {...props} active={false} />)
  rerender(<SpawnerPlanningMap {...props} layer="levels" />)
  rerender(<SpawnerPlanningMap {...props} />)
  for (let index = 0; index < 5; index++) fireEvent.click(screen.getByRole('button', { name: '地図を縮小' }))
  for (let index = 0; index < 5; index++) fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(request).toHaveBeenCalledTimes(1)
  expect(sources).toEqual(['/api/spawner-planning/terrain-detail/image?revision=revision'])
})

it('詳細地形の取得中は非表示で中断し、古い応答を採用せず再表示で再試行できる', async () => {
  let resolveOld!: (value: unknown) => void
  const pending = new Promise(resolve => { resolveOld = resolve })
  const request = vi.fn().mockReturnValueOnce(pending).mockResolvedValue({ ok: false })
  const image = vi.fn()
  vi.stubGlobal('fetch', request); vi.stubGlobal('Image', image)
  const { rerender } = render(<SpawnerPlanningMap {...props} snapshot={hoverSnapshot} />)
  await waitFor(() => expect(request).toHaveBeenCalledTimes(1))
  const signal = request.mock.calls[0][1].signal as AbortSignal
  rerender(<SpawnerPlanningMap {...props} snapshot={hoverSnapshot} active={false} />)
  expect(signal.aborted).toBe(true)
  await act(async () => resolveOld({ ok: true, json: async () => ({ schemaVersion: 1,
    terrainFingerprint: 'fingerprint', ...hoverSnapshot.manifest.bounds, blocksPerPixel: 1 }) }))
  expect(image).not.toHaveBeenCalled()
  rerender(<SpawnerPlanningMap {...props} snapshot={hoverSnapshot} />)
  await waitFor(() => expect(request).toHaveBeenCalledTimes(2))
  expect(screen.getByText(/px\/m · 16m区画/)).toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...props} snapshot={hoverSnapshot} layer="levels" />)
  rerender(<SpawnerPlanningMap {...props} snapshot={hoverSnapshot} />)
  await waitFor(() => expect(request).toHaveBeenCalledTimes(3))
})

it('詳細画像はrevision・地形fingerprint変更で無効化し、読み込み途中の画像も採用しない', async () => {
  const images: { onload: (() => void) | null; src: string }[] = []
  let identity = hoverSnapshot.manifest
  const request = vi.fn().mockImplementation(async () => ({ ok: true, json: async () => ({ schemaVersion: 1,
    terrainFingerprint: identity.terrainFingerprint, ...identity.bounds, blocksPerPixel: 1 }) }))
  vi.stubGlobal('fetch', request)
  vi.stubGlobal('Image', class {
    naturalWidth = identity.bounds.width; naturalHeight = identity.bounds.height
    onload: (() => void) | null = null
    src = ''
    constructor() { images.push(this) }
  })
  const { rerender } = render(<StrictMode><SpawnerPlanningMap {...props} snapshot={hoverSnapshot} /></StrictMode>)
  await waitFor(() => expect(images).toHaveLength(1))
  const lateLoad = images[0].onload!
  identity = { ...identity, exportRevision: 'revision-b' }
  rerender(<StrictMode><SpawnerPlanningMap {...props} snapshot={{ ...hoverSnapshot, manifest: identity }} /></StrictMode>)
  await waitFor(() => expect(images).toHaveLength(2))
  expect(images[0].src).toBe('')
  act(() => lateLoad())
  expect(screen.getByText(/px\/m · 16m区画/)).toBeInTheDocument()
  act(() => images[1].onload?.())
  expect(screen.getByText(/1m地形/)).toBeInTheDocument()
  identity = { ...identity, terrainFingerprint: 'terrain-b' }
  rerender(<StrictMode><SpawnerPlanningMap {...props} snapshot={{ ...hoverSnapshot, manifest: identity }} /></StrictMode>)
  expect(screen.getByText(/px\/m · 16m区画/)).toBeInTheDocument()
  await waitFor(() => expect(images).toHaveLength(3))
  act(() => images[2].onload?.())
  expect(screen.getByText(/1m地形/)).toBeInTheDocument()
  expect(request).toHaveBeenCalledTimes(3)
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

const hoverSnapshot: PlanningSnapshot = { ...snapshot, manifest: { ...snapshot.manifest,
  bounds: { minX: 0, minZ: 0, width: 400, height: 400, cellSize: 16 } } }
const hoverPlacement: WorldPlacement = { placementId: 'boars', kind: 'spawner', worldId: 'eriva', worldName: 'eriva',
  templateId: 'field', x: 199.5, y: 70, z: 199.5, yaw: 0, pitch: 0, enabled: true, waystone: null }
const hoverCatalog = { spawners: [{ id: 'field', name: '&aグラスボア Lv.15 / §b草狼 Lv.21', radiusMeters: 64, levels: [15, 21] }], npcs: [], worlds: [] }
const hoverProps = { ...props, snapshot: hoverSnapshot, placements: [hoverPlacement], placementCatalog: hoverCatalog }
const moveMouse = (canvas: HTMLCanvasElement, x = 203, y = 250) => fireEvent.pointerMove(canvas, { clientX: x, clientY: y, pointerType: 'mouse', buttons: 0 })

it('全大陸表示のホバーで各モブの日本語名と実レベルを表示し、選択やスクロールを発生させない', () => {
  const onPlacement = vi.fn(), onCell = vi.fn(), onProposal = vi.fn()
  const { container } = render(<SpawnerPlanningMap {...hoverProps} showProposals={false} onPlacement={onPlacement} onCell={onCell} onProposal={onProposal} />)
  const canvas = container.querySelector('canvas')!
  const event = new PointerEvent('pointermove', { bubbles: true, cancelable: true, clientX: 203, clientY: 250, pointerType: 'mouse', buttons: 0 })
  fireEvent(canvas, event)
  expect(screen.getByRole('tooltip')).toHaveTextContent('グラスボア Lv.15 / 草狼 Lv.21')
  expect(screen.getByRole('tooltip')).toHaveTextContent('Dev スポナー · 有効')
  expect(screen.getByRole('tooltip').textContent).not.toMatch(/[&§][ab]/)
  expect(event.defaultPrevented).toBe(false)
  expect(onPlacement).not.toHaveBeenCalled()
  expect(onCell).not.toHaveBeenCalled()
  expect(onProposal).not.toHaveBeenCalled()
  moveMouse(canvas, 215, 250)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  moveMouse(canvas)
  fireEvent.pointerLeave(canvas)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
})

it('表示対象の変更、無効配置、未解決マスター、旧カタログに追従する', () => {
  const { container, rerender } = render(<SpawnerPlanningMap {...hoverProps} />)
  const canvas = container.querySelector('canvas')!
  moveMouse(canvas)
  rerender(<SpawnerPlanningMap {...hoverProps} placements={[{ ...hoverPlacement, enabled: false }]} />)
  expect(screen.getByRole('tooltip')).toHaveTextContent('無効')
  expect(screen.getByRole('tooltip')).toHaveTextContent('草狼 Lv.21')
  rerender(<SpawnerPlanningMap {...hoverProps} placements={[]} />)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...hoverProps} placements={[{ ...hoverPlacement, templateId: 'missing' }]} />)
  expect(screen.getByRole('tooltip')).toHaveTextContent('出現モブ不明 (missing)')
  expect(screen.getByRole('tooltip')).toHaveTextContent('Lv.不明')
  rerender(<SpawnerPlanningMap {...hoverProps} placementCatalog={{ ...hoverCatalog, spawners: [{ id: 'field', name: '草狼 Lv.21', radiusMeters: 64 } as typeof hoverCatalog.spawners[number]] }} />)
  expect(screen.getByRole('tooltip')).toHaveTextContent('草狼 Lv.21')
  expect(screen.getByRole('tooltip')).toHaveTextContent('Lv.不明')
})

it('ドラッグ、追加・移動指定、非表示、タッチ、外枠への移動、キャンセルでホバーを残さない', () => {
  const { container, rerender } = render(<SpawnerPlanningMap {...hoverProps} />)
  const canvas = container.querySelector('canvas')!
  canvas.setPointerCapture = vi.fn()
  moveMouse(canvas)
  fireEvent.pointerDown(canvas, { clientX: 203, clientY: 250, pointerId: 1 })
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  fireEvent.pointerMove(canvas, { clientX: 223, clientY: 270, pointerType: 'mouse', buttons: 1 })
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  fireEvent.pointerCancel(canvas)
  moveMouse(canvas, 223, 270)
  expect(screen.getByRole('tooltip')).toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...hoverProps} placing />)
  moveMouse(canvas, 223, 270)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...hoverProps} active={false} />)
  moveMouse(canvas, 223, 270)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...hoverProps} />)
  fireEvent.pointerMove(canvas, { clientX: 223, clientY: 270, pointerType: 'touch', buttons: 0 })
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  moveMouse(canvas, 223, 270)
  fireEvent.blur(window)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  moveMouse(canvas, -1, 270)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
})

it('AI案は各モブのレベルを使い、地域条件と表示モード、重なるDev配置の描画順を尊重する', () => {
  const proposal = { proposalId: 'ai', spawnerId: 'ai-field', x: 199.5, y: 70, z: 199.5, radiusMeters: 64,
    originalRadiusMeters: null, masterUpdateRequired: false, definitionStatus: 'draft' as const, regionId: 'field', regionName: '草原', habitat: 'grass', level: 99,
    spawnMobs: [{ mobId: 'mob:boar', name: '&aグラスボア Lv.15', level: 15, weight: 1 }, { mobId: 'mob:wolf', name: '草狼 Lv.7', level: 21, weight: 1 }], reason: '', newCoveredCells: 0, coveredCellIndices: [] }
  const plan: PlanningPlan = { ...snapshot.manifest, previewOnly: true, radiusMeters: 64, proposals: [proposal], reasonCodes: [], counts: {}, targetCells: 0, coveredCells: 0, uncoveredCells: 0, coveragePercent: 0 }
  const mapProps = { ...hoverProps, plan, placements: [] }
  const { container, rerender } = render(<SpawnerPlanningMap {...mapProps} />)
  const canvas = container.querySelector('canvas')!
  moveMouse(canvas)
  expect(screen.getByRole('tooltip')).toHaveTextContent('AI 配置案')
  expect(screen.getByRole('tooltip')).toHaveTextContent('グラスボア · Lv.15')
  expect(screen.getByRole('tooltip')).toHaveTextContent('草狼 Lv.7 · 出現 Lv.21')
  expect(screen.getByRole('tooltip')).not.toHaveTextContent('Lv.15 · Lv.15')
  expect(screen.getByRole('tooltip')).not.toHaveTextContent('99')
  rerender(<SpawnerPlanningMap {...mapProps} region="other" />)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...mapProps} showProposals={false} />)
  moveMouse(canvas)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  const npc = { ...hoverPlacement, placementId: 'npc', kind: 'npc' as const }
  rerender(<SpawnerPlanningMap {...mapProps} placements={[hoverPlacement, npc]} />)
  moveMouse(canvas)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...mapProps} placements={[hoverPlacement, npc]} selectedPlacement="boars" />)
  expect(screen.getByRole('tooltip')).toHaveTextContent('Dev スポナー')
})

it('ズーム・パンでカーソルの下を再判定し、地図端のツールチップを枠内へ配置する', () => {
  vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockImplementation(function(this: HTMLElement) {
    return { left: 0, top: 0, width: this.getAttribute('role') === 'tooltip' ? 220 : 406,
      height: this.getAttribute('role') === 'tooltip' ? 100 : 500 } as DOMRect
  })
  const edge = { ...hoverPlacement, x: 398.5, z: 458.5 }
  const { container } = render(<SpawnerPlanningMap {...hoverProps} placements={[edge]} />)
  const canvas = container.querySelector('canvas')!
  moveMouse(canvas, 389.065, 492.165)
  const tooltip = screen.getByRole('tooltip')
  expect(parseFloat(tooltip.style.left)).toBeGreaterThanOrEqual(8)
  expect(parseFloat(tooltip.style.left) + 220).toBeLessThanOrEqual(398)
  expect(parseFloat(tooltip.style.top) + 100).toBeLessThanOrEqual(492)
  fireEvent.wheel(canvas, { deltaY: -1, clientX: 0, clientY: 0 })
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  fireEvent.keyDown(canvas, { key: 'Home' })
  expect(screen.getByRole('tooltip')).toBeInTheDocument()
  fireEvent.keyDown(canvas, { key: 'ArrowRight' })
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
})

it('長いモブ一覧は全文を確認する操作を案内し、選択詳細に全内容を残す', () => {
  const longName = 'グラスボア Lv.15 / 草狼 Lv.21 / '.repeat(100)
  vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(2000)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(100)
  const onPlacement = vi.fn()
  const mapProps = { ...hoverProps, onPlacement, placementCatalog: { ...hoverCatalog,
    spawners: [{ ...hoverCatalog.spawners[0], name: longName }] } }
  const { container, rerender } = render(<SpawnerPlanningMap {...mapProps} />)
  const canvas = container.querySelector('canvas')!
  moveMouse(canvas)
  expect(screen.getByRole('tooltip')).toHaveTextContent('クリック・一覧の詳細で全文を確認')
  canvas.setPointerCapture = vi.fn()
  fireEvent.pointerDown(canvas, { clientX: 203, clientY: 250, pointerId: 1 })
  fireEvent.pointerUp(canvas, { clientX: 203, clientY: 250, pointerId: 1 })
  expect(onPlacement).toHaveBeenCalledWith('boars')
  rerender(<SpawnerPlanningMap {...mapProps} selectedPlacement="boars" />)
  expect(screen.getByText(/^選択中:/).textContent).toContain(longName)
})

it.each([
  [406, 500, 398, 492], [406, 500, 8, 8], [406, 500, 398, 8], [406, 500, 8, 492], [180, 360, 172, 352],
])('初回ホバーの幅をleftに依存させず、四隅・狭幅でも枠内に収める (%i×%i / %i,%i)', (width, height, px, pz) => {
  const expectedWidth = Math.min(360, width - 16)
  vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockImplementation(function(this: HTMLElement) {
    if (this.getAttribute('role') === 'tooltip') return { left: 0, top: 0,
      // An auto-width absolute box shrinks to the space after its initial left.
      width: parseFloat(this.style.width) || Math.max(40, width - parseFloat(this.style.left) - 8), height: 120 } as DOMRect
    return { left: 0, top: 0, width, height } as DOMRect
  })
  const scale = Math.min((width - 32) / 400, (height - 32) / 400)
  const point = { ...hoverPlacement, x: (px - (width - 400 * scale) / 2) / scale - .5,
    z: (pz - (height - 400 * scale) / 2) / scale - .5 }
  const { container } = render(<SpawnerPlanningMap {...hoverProps} placements={[point]} />)
  const canvas = container.querySelector('canvas')!
  moveMouse(canvas, px, pz)
  const tooltip = screen.getByRole('tooltip')
  expect(tooltip).toHaveStyle({ width: `${expectedWidth}px` })
  expect(parseFloat(tooltip.style.left)).toBeGreaterThanOrEqual(8)
  expect(parseFloat(tooltip.style.left) + expectedWidth).toBeLessThanOrEqual(width - 8)
  expect(parseFloat(tooltip.style.top)).toBeGreaterThanOrEqual(8)
  expect(parseFloat(tooltip.style.top) + 120).toBeLessThanOrEqual(height - 8)
  moveMouse(canvas, px - 1, pz - 1)
  expect(screen.getByRole('tooltip')).toHaveStyle({ width: `${expectedWidth}px` })
})

function drawingContext() {
  const context = { strokeStyle: '', setTransform: vi.fn(), clearRect: vi.fn(), drawImage: vi.fn(), beginPath: vi.fn(), arc: vi.fn(),
    moveTo: vi.fn(), lineTo: vi.fn(), closePath: vi.fn(), rect: vi.fn(), stroke: vi.fn(), fill: vi.fn(),
    fillRect: vi.fn(), strokeRect: vi.fn(), fillText: vi.fn(), measureText: () => ({ width: 120 }) }
  vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(context as unknown as CanvasRenderingContext2D)
  return context
}
const rangeCalls = (context: ReturnType<typeof drawingContext>, radius: number) => context.arc.mock.calls.filter(call => Math.abs(call[2] - radius) < 1e-6)
const hoverPlan: PlanningPlan = { ...snapshot.manifest, previewOnly: true, radiusMeters: 32, proposals: [{
  proposalId: 'ai-one', spawnerId: 'field', x: 199.5, y: 70, z: 199.5, radiusMeters: 32, originalRadiusMeters: 64,
  masterUpdateRequired: true, definitionStatus: 'existing', regionId: 'field', regionName: '草原', habitat: 'grass', level: 15,
  spawnMobs: [{ mobId: 'mob:boar', name: 'グラスボア', level: 15, weight: 1 }], reason: '', newCoveredCells: 0, coveredCellIndices: [],
}], reasonCodes: [], counts: {}, targetCells: 0, coveredCells: 0, uncoveredCells: 0, coveragePercent: 0 }

it('Devホバーは実際の中心とマスター半径で円を描き、同じ点内の移動では再描画しない', () => {
  const context = drawingContext()
  const { container } = render(<SpawnerPlanningMap {...hoverProps} />)
  const canvas = container.querySelector('canvas')!
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
  context.arc.mockClear()
  moveMouse(canvas)
  expect(rangeCalls(context, 64 * .935)).toEqual([[203, 250, 64 * .935, 0, Math.PI * 2]])
  expect(screen.getByRole('tooltip')).toHaveTextContent('出現半径: 64 m')
  context.clearRect.mockClear()
  context.arc.mockClear()
  moveMouse(canvas, 204, 251)
  expect(context.clearRect).not.toHaveBeenCalled()
  expect(context.arc).not.toHaveBeenCalled()
  fireEvent.wheel(canvas, { deltaY: -1, clientX: 203, clientY: 250 })
  expect(rangeCalls(context, 64 * .935 * 1.18)).toHaveLength(1)
  expect(rangeCalls(context, 64 * .935 * 1.18)[0].slice(0, 2)).toEqual([203, 250])
  context.arc.mockClear()
  fireEvent.pointerLeave(canvas)
  expect(rangeCalls(context, 64 * .935 * 1.18)).toHaveLength(0)
})

it('同じDevマスターの別配置へ移ると配置IDに対応する1件だけの円へ切り替える', () => {
  const context = drawingContext()
  const { container } = render(<SpawnerPlanningMap {...hoverProps} placements={[hoverPlacement, { ...hoverPlacement, placementId: 'other', x: 299.5 }]} />)
  const canvas = container.querySelector('canvas')!
  context.arc.mockClear()
  moveMouse(canvas)
  expect(rangeCalls(context, 64 * .935)).toEqual([[203, 250, 64 * .935, 0, Math.PI * 2]])
  context.arc.mockClear()
  moveMouse(canvas, 296.5, 250)
  expect(rangeCalls(context, 64 * .935)).toEqual([[296.5, 250, 64 * .935, 0, Math.PI * 2]])
})

it.each(['selected', 'all'] as const)('Devの%s円はキャッシュを保ち、ホバーでは未選択の1件だけを強調する', mode => {
  const context = drawingContext()
  const { container } = render(<SpawnerPlanningMap {...hoverProps} selectedPlacement={mode === 'selected' ? 'boars' : null} radii={mode === 'all'} />)
  const canvas = container.querySelector('canvas')!
  expect(rangeCalls(context, 64 * .935)).not.toHaveLength(0)
  context.arc.mockClear()
  moveMouse(canvas)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(mode === 'all' ? 1 : 0)
  context.arc.mockClear()
  fireEvent.pointerLeave(canvas)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
  expect(context.drawImage).toHaveBeenLastCalledWith(expect.any(HTMLCanvasElement), 0, 0, 406, 500)
})

it('AIホバーは元マスターでなく配置案の半径を使い、同じマスターの別案とは区別する', () => {
  const context = drawingContext()
  const plan = { ...hoverPlan, proposals: [hoverPlan.proposals[0], { ...hoverPlan.proposals[0], proposalId: 'ai-two', x: 299.5 }] }
  const { container } = render(<SpawnerPlanningMap {...hoverProps} placements={[]} plan={plan} />)
  const canvas = container.querySelector('canvas')!
  context.arc.mockClear()
  moveMouse(canvas)
  expect(rangeCalls(context, 32 * .935)).toEqual([[203, 250, 32 * .935, 0, Math.PI * 2]])
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
  expect(screen.getByRole('tooltip')).toHaveTextContent('出現半径: 32 m')
  context.arc.mockClear()
  moveMouse(canvas, 296.5, 250)
  expect(rangeCalls(context, 32 * .935)).toEqual([[296.5, 250, 32 * .935, 0, Math.PI * 2]])
  context.arc.mockClear()
  fireEvent.pointerLeave(canvas)
  expect(rangeCalls(context, 32 * .935)).toHaveLength(0)
})

it.each(['selected', 'all'] as const)('AIの%s円はキャッシュを保ち、ホバーでは未選択の1件だけを強調する', mode => {
  const context = drawingContext()
  const { container } = render(<SpawnerPlanningMap {...hoverProps} placements={[]} plan={hoverPlan} selectedProposal={mode === 'selected' ? 'ai-one' : null} radii={mode === 'all'} />)
  const canvas = container.querySelector('canvas')!
  expect(rangeCalls(context, 32 * .935)).not.toHaveLength(0)
  context.arc.mockClear()
  moveMouse(canvas)
  expect(rangeCalls(context, 32 * .935)).toHaveLength(mode === 'all' ? 1 : 0)
  context.arc.mockClear()
  fireEvent.pointerLeave(canvas)
  expect(rangeCalls(context, 32 * .935)).toHaveLength(0)
  expect(context.drawImage).toHaveBeenLastCalledWith(expect.any(HTMLCanvasElement), 0, 0, 406, 500)
})

it('ホバー円はドラッグ・配置操作・非表示・フィルター除外で解除する', () => {
  const context = drawingContext()
  const { container, rerender } = render(<SpawnerPlanningMap {...hoverProps} />)
  const canvas = container.querySelector('canvas')!
  canvas.setPointerCapture = vi.fn()
  moveMouse(canvas)
  context.arc.mockClear()
  fireEvent.pointerDown(canvas, { clientX: 203, clientY: 250, pointerId: 1 })
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
  fireEvent.pointerCancel(canvas)
  moveMouse(canvas)
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...hoverProps} placing />)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
  rerender(<SpawnerPlanningMap {...hoverProps} />)
  moveMouse(canvas)
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...hoverProps} active={false} />)
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...hoverProps} />)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
  moveMouse(canvas)
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...hoverProps} placements={[]} />)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
  rerender(<SpawnerPlanningMap {...hoverProps} placements={[]} plan={hoverPlan} />)
  moveMouse(canvas)
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...hoverProps} placements={[]} plan={hoverPlan} region="other" />)
  expect(rangeCalls(context, 32 * .935)).toHaveLength(0)
})

it.each([null, undefined, Number.NaN, Number.POSITIVE_INFINITY, -1, 0])('未知・不正な半径 %s からDev・AIの円を捏造しない', radius => {
  const context = drawingContext()
  const catalog = { ...hoverCatalog, spawners: [{ ...hoverCatalog.spawners[0], radiusMeters: radius as number }] }
  const { container, rerender } = render(<SpawnerPlanningMap {...hoverProps} placementCatalog={catalog} />)
  const canvas = container.querySelector('canvas')!
  context.arc.mockClear()
  moveMouse(canvas)
  expect(screen.getByRole('tooltip')).toHaveTextContent('出現半径: 不明')
  expect(context.arc).not.toHaveBeenCalled()
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...hoverProps} placements={[]} plan={{ ...hoverPlan, proposals: [{ ...hoverPlan.proposals[0], radiusMeters: radius as number }] }} />)
  expect(screen.getByRole('tooltip')).toHaveTextContent('出現半径: 不明')
  expect(context.arc.mock.calls.map(call => call[2])).toEqual([48 * .935, 5])
})

const coverageSnapshot: PlanningSnapshot = { ...snapshot, manifest: { ...snapshot.manifest,
  bounds: { minX: 0, minZ: 0, width: 48, height: 16, cellSize: 16 } },
  cells: Array.from({ length: 3 }, (_, index) => ({ x: index * 16, z: 0, y: 70, status: 'ok', surface: 'grass', ground: 'dirt', biome: 'plains',
    safeCount: 256, waterCount: 0, treeCount: 0, candidateX: index * 16, candidateY: 70, candidateZ: 0,
    minY: 70, maxY: 70, habitat: 'grass', regionId: index === 2 ? 'other' : 'field', targetLevel: 15, reasonCode: 'eligible', reason: '' })),
}
const coveragePlacement = { ...hoverPlacement, x: 7.5, z: 7.5 }
const coverageCatalog = { ...hoverCatalog, spawners: [{ ...hoverCatalog.spawners[0], radiusMeters: 16 }] }
const coverageProps = { ...hoverProps, snapshot: coverageSnapshot, placements: [coveragePlacement], placementCatalog: coverageCatalog, showProposals: false }

it('未カバー・重複区画を巡回し、条件変更後も対象区画だけへ移動する', () => {
  const onCell = vi.fn()
  const placements = [coveragePlacement, { ...coveragePlacement, placementId: 'second' }]
  const { rerender } = render(<SpawnerPlanningMap {...coverageProps} placements={placements} onCell={onCell} />)
  fireEvent.click(screen.getByRole('button', { name: '次の区画' }))
  expect(onCell).toHaveBeenLastCalledWith(2)
  fireEvent.change(screen.getByLabelText('確認する区画'), { target: { value: '2' } })
  fireEvent.click(screen.getByRole('button', { name: '次の区画' }))
  expect(onCell).toHaveBeenLastCalledWith(0)
  rerender(<SpawnerPlanningMap {...coverageProps} placements={placements} onCell={onCell} selectedCell={0} />)
  fireEvent.click(screen.getByRole('button', { name: '前の区画' }))
  expect(onCell).toHaveBeenLastCalledWith(1)
  rerender(<SpawnerPlanningMap {...coverageProps} placements={placements} onCell={onCell} selectedCell={0} region="other" />)
  expect(screen.getByRole('button', { name: '次の区画' })).toBeDisabled()
  expect(screen.getByLabelText('選択区画の出現範囲')).toHaveTextContent('表示条件外')
  fireEvent.change(screen.getByLabelText('確認する区画'), { target: { value: '0' } })
  fireEvent.click(screen.getByRole('button', { name: '次の区画' }))
  expect(onCell).toHaveBeenLastCalledWith(2)
  rerender(<SpawnerPlanningMap {...coverageProps} onCell={onCell} reason="protected" />)
  expect(screen.getByRole('button', { name: '次の区画' })).toBeDisabled()
})

it('Dev区画には届く有効スポナーの実件数と日本語名を表示し、その配置を選択できる', () => {
  const onPlacement = vi.fn()
  render(<SpawnerPlanningMap {...coverageProps} selectedCell={1} onPlacement={onPlacement} placements={[
    coveragePlacement, { ...coveragePlacement, placementId: 'second' }, { ...coveragePlacement, placementId: 'third' },
    { ...coveragePlacement, placementId: 'disabled', enabled: false }, { ...coveragePlacement, placementId: 'npc', kind: 'npc' },
    { ...coveragePlacement, placementId: 'other', worldId: 'other' },
  ]} />)
  const detail = screen.getByLabelText('選択区画の出現範囲')
  expect(detail).toHaveTextContent('既知範囲3件が重複')
  expect(detail).toHaveTextContent('半径 16m')
  const buttons = within(detail).getAllByRole('button')
  expect(buttons).toHaveLength(3)
  expect(buttons[0]).toHaveTextContent('Lv.')
  fireEvent.click(buttons[2])
  expect(onPlacement).toHaveBeenCalledWith('third')
})

it('AI区画の詳細は新規被覆記録にない重複元も含め、Devを混ぜず案へ移動する', () => {
  const onProposal = vi.fn(), proposal = { ...hoverPlan.proposals[0], x: 7.5, z: 7.5, radiusMeters: 16, coveredCellIndices: [] }
  const plan = { ...hoverPlan, proposals: [proposal, { ...proposal, proposalId: 'second' }] }
  render(<SpawnerPlanningMap {...coverageProps} selectedCell={1} showProposals plan={plan} onProposal={onProposal} />)
  const detail = screen.getByLabelText('選択区画の出現範囲')
  expect(detail).toHaveTextContent('既知範囲2件が重複')
  fireEvent.click(within(detail).getAllByRole('button')[1])
  expect(onProposal).toHaveBeenCalledWith('second')
})

it('不明半径は断定を避け、未取得・OFF・追加中は区画巡回を行わない', () => {
  const onCell = vi.fn(), unknownCatalog = { ...coverageCatalog, spawners: [{ ...coverageCatalog.spawners[0], radiusMeters: null }] }
  const { rerender } = render(<SpawnerPlanningMap {...coverageProps} selectedCell={0} onCell={onCell} placementCatalog={unknownCatalog} />)
  expect(screen.getByLabelText('選択区画の出現範囲')).toHaveTextContent('未カバーとは断定できません')
  rerender(<SpawnerPlanningMap {...coverageProps} onCell={onCell} placing />)
  fireEvent.click(screen.getByRole('button', { name: '次の区画' }))
  expect(onCell).not.toHaveBeenCalled()
  rerender(<SpawnerPlanningMap {...coverageProps} onCell={onCell} placementsReady={false} />)
  expect(screen.queryByRole('button', { name: '次の区画' })).not.toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...coverageProps} onCell={onCell} />)
  fireEvent.click(screen.getByRole('checkbox', { name: '重複・未カバーを色で確認' }))
  expect(screen.queryByRole('button', { name: '次の区画' })).not.toBeInTheDocument()
})

it('既定ONの色と文字件数で重複・未カバーを示し、無効・NPC・別worldを除いて編集とフィルターを反映する', () => {
  drawingContext()
  const { rerender } = render(<SpawnerPlanningMap {...coverageProps} placements={[coveragePlacement,
    { ...coveragePlacement, placementId: 'disabled', enabled: false }, { ...coveragePlacement, placementId: 'npc', kind: 'npc' },
    { ...coveragePlacement, placementId: 'other-world', worldId: 'other' }]} />)
  const key = screen.getByLabelText('出現範囲の重複と未カバー')
  expect(screen.getByRole('checkbox', { name: '重複・未カバーを色で確認' })).toBeChecked()
  expect(key).toHaveTextContent('未カバー（0件） · 1区画')
  expect(key).toHaveTextContent('1件の範囲内 · 2区画')
  expect(key).toHaveTextContent('重複（2件以上） · 0区画')
  rerender(<SpawnerPlanningMap {...coverageProps} placements={[coveragePlacement, { ...coveragePlacement, placementId: 'second' }]} />)
  expect(key).toHaveTextContent('重複（2件以上） · 2区画')
  rerender(<SpawnerPlanningMap {...coverageProps} placements={[{ ...coveragePlacement, x: 39.5 }]} />)
  expect(key).toHaveTextContent('未カバー（0件） · 1区画')
  rerender(<SpawnerPlanningMap {...coverageProps} placements={[]} />)
  expect(key).toHaveTextContent('未カバー（0件） · 3区画')
})

it('AIはDevと二重計数せず案の半径で計算し、地域・状況を反映して未取得は判定しない', () => {
  drawingContext()
  const plan = { ...hoverPlan, proposals: [{ ...hoverPlan.proposals[0], x: 7.5, z: 7.5, radiusMeters: 16, coveredCellIndices: [] }] }
  const { rerender } = render(<SpawnerPlanningMap {...coverageProps} showProposals plan={plan} />)
  const key = screen.getByLabelText('出現範囲の重複と未カバー')
  expect(key).toHaveTextContent('判定: 選択地域の AI 配置案のみ')
  expect(key).toHaveTextContent('1件の範囲内 · 2区画')
  expect(key).toHaveTextContent('重複（2件以上） · 0区画')
  rerender(<SpawnerPlanningMap {...coverageProps} showProposals plan={plan} region="other" />)
  expect(key).toHaveTextContent('未カバー（0件） · 1区画')
  rerender(<SpawnerPlanningMap {...coverageProps} showProposals plan={plan} reason="covered" />)
  expect(key).toHaveTextContent('1件の範囲内 · 0区画')
  rerender(<SpawnerPlanningMap {...coverageProps} showProposals plan={null} />)
  expect(key).toHaveTextContent('AI 配置案を取得できていないため')
  expect(key).not.toHaveTextContent('区画 ·')
  expect(key).not.toHaveTextContent('0件） · 3区画')
})

it('未取得のDevを0件とせず、不明半径・未解析地形を未カバーと断定しない', () => {
  drawingContext()
  const { rerender } = render(<SpawnerPlanningMap {...coverageProps} placementsReady={false} />)
  const key = screen.getByLabelText('出現範囲の重複と未カバー')
  expect(key).toHaveTextContent('Dev 配置とマスターの取得が完了していないため')
  expect(key).not.toHaveTextContent('0件） · 1区画')
  rerender(<SpawnerPlanningMap {...coverageProps} placementCatalog={{ ...coverageCatalog, spawners: [{ ...coverageCatalog.spawners[0], radiusMeters: null }] }} />)
  expect(key).toHaveTextContent('既知範囲0件 · 3区画')
  expect(key).toHaveTextContent('0件の場所も未カバーとは断定できません')
  rerender(<SpawnerPlanningMap {...coverageProps} placements={[]} snapshot={{ ...coverageSnapshot, cells: coverageSnapshot.cells.map(item => ({ ...item, status: 'failed' })) }} selectedCell={0} />)
  expect(key).toHaveTextContent('未カバー（0件） · 0区画')
  expect(key).toHaveTextContent('選択区画: 判定対象外')
})

it('色をOFFにしても円設定を保持し、ホバー・パン・ズームでは被覆の再計算を増やさない', () => {
  const context = drawingContext()
  const { container, rerender } = render(<SpawnerPlanningMap {...coverageProps} radii />)
  const key = screen.getByLabelText('出現範囲の重複と未カバー')
  const canvas = container.querySelector('canvas')!
  context.fillRect.mockClear()
  moveMouse(canvas, 86, 250)
  expect(context.fillRect).not.toHaveBeenCalled()
  fireEvent.keyDown(canvas, { key: 'ArrowLeft' })
  fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  // Scene labels/scale may use fillRect, but the 1x1 coverage raster remains cached.
  expect(context.fillRect.mock.calls.filter(call => call[2] === 1 && call[3] === 1)).toHaveLength(0)
  fireEvent.click(screen.getByRole('checkbox', { name: '重複・未カバーを色で確認' }))
  expect(key).not.toHaveTextContent('0件） · 1区画')
  rerender(<SpawnerPlanningMap {...coverageProps} radii showProposals plan={{ ...hoverPlan, proposals: [{ ...hoverPlan.proposals[0], x: 7.5, z: 7.5 }] }} />)
  expect(screen.getByRole('checkbox', { name: '重複・未カバーを色で確認' })).not.toBeChecked()
  expect(rangeCalls(context, 32 * (374 / 48) * 1.5).length).toBeGreaterThan(0)
})

it('2,500配置の全範囲を1色1strokeで加え、ホバーでは地形・点・全円を再描画しない', () => {
  const context = drawingContext()
  const placements = Array.from({ length: 2500 }, (_, index) => ({ ...hoverPlacement, placementId: `dense-${index}`,
    x: 5 + index % 50 * 7.8, z: 5 + Math.floor(index / 50) * 7.8 }))
  const mapProps = { ...hoverProps, placements, showProposals: false }
  const { container, rerender } = render(<SpawnerPlanningMap {...mapProps} />)
  context.arc.mockClear(); context.stroke.mockClear()
  rerender(<SpawnerPlanningMap {...mapProps} radii />)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(2500)
  const strokesWithRanges = context.stroke.mock.calls.length
  context.arc.mockClear(); context.stroke.mockClear()
  rerender(<SpawnerPlanningMap {...mapProps} radii={false} />)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
  expect(strokesWithRanges - context.stroke.mock.calls.length).toBe(1)
  rerender(<SpawnerPlanningMap {...mapProps} radii />)
  context.arc.mockClear(); context.fillText.mockClear(); context.drawImage.mockClear(); context.stroke.mockClear()
  const canvas = container.querySelector('canvas')!
  moveMouse(canvas, 202.53, 249.53)
  expect(context.arc).toHaveBeenCalledTimes(1)
  expect(context.stroke).toHaveBeenCalledTimes(1)
  expect(context.fillText).not.toHaveBeenCalled()
  expect(context.drawImage).toHaveBeenCalledTimes(1)
  expect(context.strokeStyle).toBe('#9ee9ef')
  context.arc.mockClear(); context.drawImage.mockClear()
  fireEvent.pointerLeave(canvas)
  expect(context.arc).not.toHaveBeenCalled()
  expect(context.drawImage).toHaveBeenCalledTimes(1)
  console.info('Map load: 2,500 visible placements; all 2,500 ranges add 1 stroke; hover rebuilds 0 terrain/marker/range paths and draws 1 highlight; leave draws 0 arcs.')
})

it('一括円は画面外の中心も半径で判定し、パン・ズーム・編集・色・DPR変更でキャッシュを更新する', () => {
  const context = drawingContext()
  const placements = [hoverPlacement, { ...hoverPlacement, placementId: 'edge', x: -50.5 },
    { ...hoverPlacement, placementId: 'outside', x: -100.5 }, { ...hoverPlacement, placementId: 'other-world', worldId: 'other' }]
  const mapProps = { ...hoverProps, placements, radii: true }
  const { container, rerender } = render(<SpawnerPlanningMap {...mapProps} />)
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...mapProps} reason="filtered" />)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(2)
  context.arc.mockClear()
  const edited = [{ ...hoverPlacement, x: 99.5 }]
  rerender(<SpawnerPlanningMap {...mapProps} placements={edited} />)
  expect(rangeCalls(context, 64 * .935)).toEqual([[109.5, 250, 64 * .935, 0, Math.PI * 2]])
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...mapProps} placements={edited} placementCatalog={{ ...hoverCatalog, spawners: [{ ...hoverCatalog.spawners[0], radiusMeters: 32 }] }} />)
  expect(rangeCalls(context, 32 * .935)).toHaveLength(1)
  context.arc.mockClear()
  fireEvent.change(screen.getByRole('combobox', { name: 'スポナーの色分け' }), { target: { value: 'levels' } })
  expect(rangeCalls(context, 32 * .935)).toHaveLength(1)
  context.arc.mockClear()
  const canvas = container.querySelector('canvas')!
  fireEvent.keyDown(canvas, { key: 'ArrowLeft' })
  expect(rangeCalls(context, 32 * .935)[0][0]).toBeCloseTo(169.5)
  context.arc.mockClear()
  fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(rangeCalls(context, 32 * .935 * 1.5)).toHaveLength(1)
  context.arc.mockClear()
  vi.stubGlobal('devicePixelRatio', 2)
  fireEvent(window, new Event('resize'))
  expect(canvas.width).toBe(812)
  expect(rangeCalls(context, 32 * .935 * 1.5)).toHaveLength(1)
})

it('AIは一覧の40件制限なく地域内全1,500案の半径を描き、モードと地域変更で残像を残さない', () => {
  const context = drawingContext()
  const proposals = Array.from({ length: 1500 }, (_, index) => ({ ...hoverPlan.proposals[0], proposalId: `proposal-${index}`,
    x: 5 + index % 50 * 7.8, z: 5 + Math.floor(index / 50) * 7.8 }))
  const plan = { ...hoverPlan, proposals: [...proposals, { ...hoverPlan.proposals[0], proposalId: 'other-region', regionId: 'other', radiusMeters: 64 }] }
  const mapProps = { ...hoverProps, placements: [], plan, radii: true, region: 'field' }
  const { rerender } = render(<SpawnerPlanningMap {...mapProps} showProposals={false} />)
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...mapProps} />)
  expect(rangeCalls(context, 32 * .935)).toHaveLength(1500)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...mapProps} region="other" />)
  expect(rangeCalls(context, 32 * .935)).toHaveLength(0)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(1)
  context.arc.mockClear()
  rerender(<SpawnerPlanningMap {...mapProps} showProposals={false} />)
  expect(rangeCalls(context, 32 * .935)).toHaveLength(0)
  expect(rangeCalls(context, 64 * .935)).toHaveLength(0)
})
