import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SpawnerMasterLegend } from './SpawnerMasterLegend'
import { SpawnerPlanningMap } from './SpawnerPlanningMap'
import { spawnerLevels, spawnerMasterColor, spawnerMasterNumbers } from '../data/spawnerMapStyle'
import type { PlanningSnapshot } from '../data/spawnerPlanning'
import type { WorldPlacement } from '../api/worldPlacementApi'

const summary = () => screen.getByText(/^表示中のスポナーマスター/)
const details = () => summary().closest('details')!
function toggle(open: boolean) { act(() => { details().open = open; fireEvent(details(), new Event('toggle')) }) }
const rows = () => [...(details().querySelector('.sp-master-key-list')?.children ?? [])] as HTMLElement[]
let frames: Map<number, FrameRequestCallback>
beforeEach(() => {
  frames = new Map(); let frameId = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { frames.set(++frameId, callback); return frameId })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 406, height: 500 } as DOMRect)
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

it('builds rows only on expansion and updates the count while closed without formatting hidden names', () => {
  let nameReads = 0
  const ids = Array.from({ length: 1000 }, (_, i) => `master-${String(i).padStart(4, '0')}`)
  const masters = new Map(ids.map(id => [id, { id, get name() { nameReads++; return `&a狼 ${id}` }, radiusMeters: 64, levels: [15] }]))
  const numbers = spawnerMasterNumbers(ids)
  const { rerender } = render(<SpawnerMasterLegend ids={ids} masters={masters} numbers={numbers} mode="masters" />)
  expect(summary()).toHaveTextContent('(1000)')
  expect(rows()).toHaveLength(0); expect(nameReads).toBe(0)
  rerender(<SpawnerMasterLegend ids={ids.slice(0, 2)} masters={masters} numbers={numbers} mode="masters" />)
  expect(summary()).toHaveTextContent('(2)'); expect(nameReads).toBe(0)
  toggle(true)
  expect(rows()).toHaveLength(2); expect(nameReads).toBe(2)
  expect(rows()[0]).toHaveTextContent('#1狼 master-0000Lv.15 · master-0000')
  toggle(false)
  expect(details().querySelector('.sp-master-key-list')).toBeNull()
  toggle(true)
  expect(rows()).toHaveLength(2); expect(nameReads).toBe(4)
})

it('keeps numbers, colors, unknown/null fallbacks, and live catalog/filter updates while open', () => {
  const ids = ['slime', 'wolf', 'missing', null]
  const masters = new Map([
    ['slime', { id: 'slime', name: 'スライム', radiusMeters: 32, levels: [15] }],
    ['wolf', { id: 'wolf', name: '&aオオカミ', radiusMeters: 64, levels: [30, 15] }],
  ])
  const numbers = spawnerMasterNumbers([...masters.keys()])
  const { rerender } = render(<SpawnerMasterLegend ids={ids} masters={masters} numbers={numbers} mode="masters" />)
  toggle(true)
  const original = rows()
  expect(original.map(row => row.textContent)).toEqual(['#1スライムLv.15 · slime', '#2オオカミLv.15–30 · wolf', '#?missingLv.不明 · missing', '#?マスター未選択Lv.不明 · マスター未選択'])
  expect(original[2].querySelector('b')).toHaveStyle({ borderColor: spawnerMasterColor('missing') })
  expect(original[3].querySelector('b')).toHaveStyle({ borderColor: '#b0bbc8' })
  rerender(<SpawnerMasterLegend ids={ids} masters={masters} numbers={numbers} mode="levels" />)
  expect(details().open).toBe(true)
  expect(rows()[0]).toBe(original[0])
  expect(rows()[0].querySelector('b')).toHaveStyle({ borderColor: spawnerLevels([15]).color })
  expect(rows()[1].querySelector('b')).toHaveStyle({ borderColor: '#b0bbc8' })
  const changed = new Map(masters)
  changed.set('alpha', { id: 'alpha', name: '新しいモブ', radiusMeters: 64, levels: [5] })
  changed.set('wolf', { id: 'wolf', name: '§c火山狼', radiusMeters: 64, levels: [45] })
  rerender(<SpawnerMasterLegend ids={['wolf', 'missing']} masters={changed} numbers={spawnerMasterNumbers([...changed.keys()])} mode="levels" />)
  expect(details().open).toBe(true)
  expect(rows()).toHaveLength(2)
  expect(rows()[0]).toHaveTextContent('#3火山狼Lv.45 · wolf')
  expect(rows()[0].querySelector('b')).toHaveStyle({ borderColor: spawnerLevels([45]).color })
  rerender(<SpawnerMasterLegend ids={[]} masters={changed} numbers={numbers} mode="masters" />)
  expect(details().open).toBe(true); expect(rows()).toHaveLength(0); expect(summary()).toHaveTextContent('(0)')
})

const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'a', exportRevision: 'b', previewOnly: true,
    bounds: { minX: -2000, minZ: -2000, width: 4000, height: 4000, cellSize: 16 }, spawn: { x: 0, y: 70, z: 0 }, capturedAt: '', radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} }, cells: [], regions: [],
}
function mapFixture() {
  let reads = 0
  const spawners = Array.from({ length: 1000 }, (_, i) => ({ id: `master-${i}`, get name() { reads++; return `&a狼 ${i}` }, radiusMeters: 64, levels: [15, 30] }))
  const placements: WorldPlacement[] = spawners.map((master, i) => ({ placementId: `placement-${i}`, kind: 'spawner', worldId: 'eriva', worldName: 'eriva', templateId: master.id,
    x: 5000 + i % 40 * 64, y: 70, z: 5000 + Math.floor(i / 40) * 64, yaw: 0, pitch: 0, enabled: false, waystone: null }))
  return { props: { snapshot, plan: null, layer: 'terrain' as const, region: '', reason: '', radii: false, active: true, selectedCell: null, selectedProposal: null, focus: null,
    onCell: vi.fn(), onProposal: vi.fn(), placements, placementCatalog: { spawners, npcs: [], worlds: [] }, placementsReady: false, showProposals: false },
  reads: () => reads, clear: () => { reads = 0 } }
}

it('keeps the map legend initially closed without attaching 1000 hidden rows', () => {
  render(<SpawnerPlanningMap {...mapFixture().props} />)
  expect(summary()).toHaveTextContent('(1000)')
  expect(details().open).toBe(false)
  expect(rows()).toHaveLength(0)
})

it.each([false, true])('does not reformat 1000 legend names on 30 pointer frames, coordinate input, pan or zoom (expanded=%s)', expanded => {
  const fixture = mapFixture()
  render(<SpawnerPlanningMap {...fixture.props} />)
  if (expanded) toggle(true)
  const row = rows()[0]
  fixture.clear()
  const canvas = screen.getByLabelText(/^保存済み Dev 配置の地図/)
  for (let i = 0; i < 30; i++) {
    fireEvent.pointerMove(canvas, { clientX: 2 + i / 100, clientY: 2, pointerType: 'mouse', buttons: 0 })
    act(() => { const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback(0)) })
  }
  expect(fixture.reads()).toBe(0)
  fireEvent.change(screen.getByLabelText('移動先X'), { target: { value: '-500' } })
  fireEvent.keyDown(canvas, { key: 'ArrowRight' })
  fireEvent.click(screen.getByRole('button', { name: '地図を拡大' }))
  expect(fixture.reads()).toBe(0)
  expect(details().open).toBe(expanded)
  expect(rows()).toHaveLength(expanded ? 1000 : 0)
  if (expanded) {
    expect(rows()[0]).toBe(row)
    expect(within(row).getByText(/狼 0/)).toBeInTheDocument()
  }
})
