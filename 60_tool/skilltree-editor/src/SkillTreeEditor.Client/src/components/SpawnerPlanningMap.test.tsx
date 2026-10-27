import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
