import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SpawnerPlanningMap } from './SpawnerPlanningMap'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'
import type { WorldPlacement } from '../api/worldPlacementApi'
import * as pointIndex from '../data/mapPointIndex'

const snapshot: PlanningSnapshot = {
  manifest: { schemaVersion: 2, worldId: 'eriva', worldName: 'エリヴァ', terrainFingerprint: 'terrain', exportRevision: 'revision', previewOnly: true,
    bounds: { minX: -2000, minZ: -2000, width: 4000, height: 4000, cellSize: 16 }, spawn: { x: 0, y: 64, z: 0 }, capturedAt: '', radiusOptions: [64], reasons: {}, limitations: [], progressionPolicy: {} }, cells: [], regions: [],
}
const placement: WorldPlacement = { placementId: 'first', kind: 'spawner', worldId: 'eriva', worldName: 'eriva', templateId: 'first', x: 0, y: 64, z: 0, yaw: 0, pitch: 0, enabled: true, waystone: null }
const proposal = { proposalId: 'first', spawnerId: 'first', x: 0, y: 64, z: 0, radiusMeters: 64, originalRadiusMeters: null, masterUpdateRequired: false, definitionStatus: 'draft' as const,
  regionId: 'first', regionName: '草原', habitat: 'grass', level: 15, spawnMobs: [{ mobId: 'wolf', name: '先の狼', level: 15, weight: 1 }], reason: '', newCoveredCells: 0, coveredCellIndices: [] }
const plan: PlanningPlan = { ...snapshot.manifest, previewOnly: true, radiusMeters: 64, proposals: [proposal], reasonCodes: [], counts: {}, targetCells: 0, coveredCells: 0, uncoveredCells: 0, coveragePercent: 0 }
const props = { snapshot, plan: null, layer: 'terrain' as const, region: '', reason: '', radii: false, active: true, selectedCell: null, selectedProposal: null, focus: { key: 1, x: .5, z: .5 },
  onCell: vi.fn(), onProposal: vi.fn(), onPlacement: vi.fn(), placementCatalog: { spawners: [{ id: 'first', name: '先の狼', levels: [15], radiusMeters: 64 }, { id: 'last', name: '後の狼', levels: [21], radiusMeters: 64 }], npcs: [], worlds: [] } }
// Far points ensure the spatial-query path is exercised instead of the small-data fallback.
const farPlacements = Array.from({ length: 2500 }, (_, i) => ({ ...placement, placementId: `far-${i}`, x: 400 + i % 50 * 64, z: 400 + Math.floor(i / 50) * 64 }))
const farProposals = Array.from({ length: 1500 }, (_, i) => ({ ...proposal, proposalId: `far-${i}`, x: -400 - i % 50 * 64, z: -400 - Math.floor(i / 50) * 64 }))
let frames: Map<number, FrameRequestCallback>
const canvas = () => screen.getByLabelText(/エリヴァ全大陸の地図|保存済み Dev 配置の地図/)
function hover(x = 203, y = 250) {
  fireEvent.pointerMove(canvas(), { clientX: x, clientY: y, pointerType: 'mouse', buttons: 0 })
  act(() => { const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback(0)) })
}
function click(x = 203, y = 250) {
  Object.defineProperty(canvas(), 'setPointerCapture', { value: vi.fn(), configurable: true })
  fireEvent.pointerDown(canvas(), { pointerId: 1, clientX: x, clientY: y })
  fireEvent.pointerUp(canvas(), { pointerId: 1, clientX: x, clientY: y })
}
beforeEach(() => {
  frames = new Map(); let frameId = 0
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { frames.set(++frameId, callback); return frameId })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, width: 406, height: 500 } as DOMRect)
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.clearAllMocks() })

it('keeps last-drawn/selected placement priority and refreshes edited/world-filtered positions', () => {
  const placements = [...farPlacements, placement, { ...placement, placementId: 'last', templateId: 'last' }]
  const { rerender } = render(<SpawnerPlanningMap {...props} placements={placements} />)
  hover(); expect(screen.getByRole('tooltip')).toHaveTextContent('後の狼')
  click(); expect(props.onPlacement).toHaveBeenLastCalledWith('last')
  rerender(<SpawnerPlanningMap {...props} placements={placements} selectedPlacement="first" />)
  hover(); expect(screen.getByRole('tooltip')).toHaveTextContent('先の狼')
  click(); expect(props.onPlacement).toHaveBeenLastCalledWith('first')
  rerender(<SpawnerPlanningMap {...props} placements={[...farPlacements, { ...placement, x: 500 }, { ...placement, placementId: 'other', worldId: 'other' }]} />)
  hover(); expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  rerender(<SpawnerPlanningMap {...props} placements={[...farPlacements, placement]} />)
  hover(); expect(screen.getByRole('tooltip')).toHaveTextContent('先の狼')
})

it('keeps AI hover-last/click-first ties, region filtering, and Dev priority including NPCs', () => {
  const proposals = [...farProposals, proposal, { ...proposal, proposalId: 'last', regionId: 'last', spawnMobs: [{ ...proposal.spawnMobs[0], name: '後の狼' }] }]
  const mapProps = { ...props, plan: { ...plan, proposals }, placements: farPlacements }
  const { rerender } = render(<SpawnerPlanningMap {...mapProps} />)
  hover(); expect(screen.getByRole('tooltip')).toHaveTextContent('後の狼')
  click(); expect(props.onProposal).toHaveBeenLastCalledWith('first')
  rerender(<SpawnerPlanningMap {...mapProps} region="first" />)
  hover(); expect(screen.getByRole('tooltip')).toHaveTextContent('先の狼')
  rerender(<SpawnerPlanningMap {...mapProps} placements={[...farPlacements, { ...placement, kind: 'npc' }]} />)
  hover(); expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  click(); expect(props.onPlacement).toHaveBeenLastCalledWith('first')
  rerender(<SpawnerPlanningMap {...mapProps} showProposals={false} />)
  hover(); expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
})

it.each(['placement', 'proposal'] as const)('preserves the strict screen-distance boundary for %s hover and click', kind => {
  // Focus gives .7px/m; the centered point makes the pixel boundary exact.
  render(<SpawnerPlanningMap {...props} placements={kind === 'placement' ? [...farPlacements, placement] : farPlacements} plan={kind === 'proposal' ? { ...plan, proposals: [...farProposals, proposal] } : null} />)
  const radius = kind === 'placement' ? 8 : 6, callback = kind === 'placement' ? props.onPlacement : props.onProposal
  hover(203 + radius); expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  click(203 + radius); expect(callback).not.toHaveBeenCalled()
  hover(203 + radius - .001); expect(screen.getByRole('tooltip')).toHaveTextContent('先の狼')
  click(203 + radius - .001); expect(callback).toHaveBeenCalledWith('first')
})

it.each([.249, .25, .251])('keeps the AI click zoom threshold at scale %s while allowing hover', scale => {
  const width = 374 / scale
  const viewSnapshot = { ...snapshot, manifest: { ...snapshot.manifest, bounds: { ...snapshot.manifest.bounds, minX: -width / 2, minZ: -width / 2, width, height: width } } }
  render(<SpawnerPlanningMap {...props} snapshot={viewSnapshot} focus={null} placements={farPlacements} plan={{ ...plan, proposals: [...farProposals, { ...proposal, x: -.5, z: -.5 }] }} />)
  hover(); expect(screen.getByRole('tooltip')).toHaveTextContent('先の狼')
  click()
  expect(props.onProposal).toHaveBeenCalledTimes(scale > .25 ? 1 : 0)
})

it('reduces hit-test work for both sources and retains hits after pointer movement, pan, or zoom', () => {
  const buildIndex = vi.spyOn(pointIndex, 'createMapPointIndex')
  render(<SpawnerPlanningMap {...props} placements={farPlacements} plan={{ ...plan, proposals: [...farProposals, proposal] }} />)
  expect(buildIndex).toHaveBeenCalledTimes(2)
  buildIndex.mockClear()
  const hypot = vi.spyOn(Math, 'hypot')
  for (let i = 0; i < 30; i++) hover(203 + i / 100)
  const calls = hypot.mock.calls.length
  expect(calls).toBeGreaterThan(0)
  expect(calls).toBeLessThan(300)
  expect(screen.getByRole('tooltip')).toHaveTextContent('先の狼')
  fireEvent.keyDown(canvas(), { key: 'ArrowRight' }); hover(143)
  expect(screen.getByRole('tooltip')).toHaveTextContent('先の狼')
  fireEvent.keyDown(canvas(), { key: '+' }); hover(119)
  expect(screen.getByRole('tooltip')).toHaveTextContent('先の狼')
  expect(buildIndex).not.toHaveBeenCalled()
  console.info(`Map hit-test: 30 frames / 2500 placements + 1501 proposals, distances=${calls}, full scan=120030`)
})
