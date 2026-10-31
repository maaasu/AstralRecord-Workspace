import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SpawnerPlanningWorkspace } from './SpawnerPlanningWorkspace'
import { spawnerPlanningApi } from '../api/spawnerPlanningApi'
import { worldPlacementApi } from '../api/worldPlacementApi'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'

vi.mock('../api/spawnerPlanningApi', () => ({ spawnerPlanningApi: { snapshot: vi.fn(), plan: vi.fn() } }))
vi.mock('../api/worldPlacementApi', () => ({ worldPlacementApi: { read: vi.fn(), save: vi.fn() } }))
// Exercise the real list, detail, selection, Dev editor and request lifecycle.
// Only the canvas hit-test is replaced by a selectable proposal ID.
vi.mock('./SpawnerPlanningMap', () => ({ SpawnerPlanningMap: ({ plan, onProposal, onCell, onPlacement, region, focus, selectedProposal }: {
  plan: PlanningPlan | null; onProposal: (id: string) => void; onCell: (index: number) => void; onPlacement: (id: string) => void
  region: string; focus: unknown; selectedProposal: string | null
}) => <>
  <label>地図で選ぶ案<input onChange={event => onProposal(event.target.value)} /></label>
  <button onClick={() => onCell(0)}>空地点を選択</button><button onClick={() => onPlacement('dev-one')}>Dev配置を選択</button>
  <output data-testid="map-count">{plan?.proposals.length ?? 0}</output><output data-testid="map-region">{region}</output>
  <output data-testid="map-focus">{JSON.stringify(focus)}</output><output data-testid="map-selected">{selectedProposal}</output>
</> }))

const identity = { schemaVersion: 2, worldId: 'eriva', terrainFingerprint: 'terrain', exportRevision: 'revision' }
const snapshot: PlanningSnapshot = {
  manifest: { ...identity, worldName: 'エリヴァ', previewOnly: true, bounds: { minX: 0, minZ: 0, width: 32, height: 16, cellSize: 16 }, spawn: { x: 0, y: 70, z: 0 }, capturedAt: '', radiusOptions: [32, 64], reasons: { covered: 'カバー済み' }, limitations: [], progressionPolicy: {} },
  cells: [0, 16].map((x, index) => ({ x, z: 0, y: 70, status: 'ok', surface: 'grass', ground: 'dirt', biome: 'plains', safeCount: 256, waterCount: 0, treeCount: 0, candidateX: x + 8, candidateY: 70, candidateZ: 8, minY: 70, maxY: 70, habitat: 'grass', regionId: index ? 'east' : 'west', targetLevel: 15, reasonCode: 'covered', reason: '' })),
  regions: ['west', 'east'].map(id => ({ id, name: id === 'west' ? '西の草原' : '東の森', habitat: 'grass', levelMin: 15, levelMax: 15, cellCount: 1, eligibleCells: 1, coveredCellsByRadius: { '32': 1, '64': 1 }, spawnerIds: [], reason: '' })),
}
const createPlan = (count = 125): PlanningPlan => ({
  ...identity, previewOnly: true, radiusMeters: 64, reasonCodes: ['covered', 'covered'], counts: { covered: 2 }, targetCells: 2, coveredCells: 2, uncoveredCells: 0, coveragePercent: 100,
  proposals: Array.from({ length: count }, (_, index) => ({ proposalId: `proposal-${index}`, spawnerId: 'wolf', x: index, y: 70, z: 0, radiusMeters: 64, originalRadiusMeters: 64, masterUpdateRequired: false, definitionStatus: 'existing', regionId: index % 2 ? 'east' : 'west', regionName: index % 2 ? '東の森' : '西の草原', habitat: 'grass', level: 15, spawnMobs: [{ mobId: 'wolf', name: '森林狼', level: 15, weight: 100 }], reason: '', newCoveredCells: 1, coveredCellIndices: [0] })),
})
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(snapshot)
  vi.mocked(spawnerPlanningApi.plan).mockResolvedValue(createPlan())
  vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev', placements: [
    { placementId: 'dev-one', kind: 'spawner', worldId: 'eriva', worldName: 'worlds/eriva', templateId: 'wolf', x: 3, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true, waystone: null },
  ] })
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ spawners: [{ id: 'wolf', name: '森林狼 Lv.15', radiusMeters: 64, levels: [15] }], npcs: [], worlds: [] }) })))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
async function openAi() {
  const view = render(<SpawnerPlanningWorkspace active />)
  fireEvent.click(screen.getByRole('button', { name: 'AI 配置案' }))
  await waitFor(() => expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeEnabled())
  return view
}
const list = () => within(screen.getByLabelText('配置案の一覧'))
const rows = () => list().queryAllByRole('button')
const selectMap = (id: string) => fireEvent.change(screen.getByLabelText('地図で選ぶ案'), { target: { value: id } })
const reveal = () => screen.getByRole('button', { name: '選択中の案を一覧に表示' })
const next = () => screen.getByRole('button', { name: '次の案ページ' })

describe('AI proposal pages and selected proposal reveal', () => {
  it('keeps at most 40 rows and focuses the first row after page navigation without changing map selection', async () => {
    await openAi()
    expect(rows()).toHaveLength(40)
    expect(screen.getByRole('button', { name: '前の案ページ' })).toBeDisabled()
    expect(reveal()).toBeDisabled()
    selectMap('proposal-0')
    const focus = screen.getByTestId('map-focus').textContent
    fireEvent.click(next())
    expect(rows()[0]).toHaveAttribute('data-proposal-id', 'proposal-40')
    expect(rows()[0]).toHaveFocus()
    fireEvent.click(next())
    fireEvent.click(next())
    expect(rows()).toHaveLength(5)
    expect(rows()[0]).toHaveFocus()
    expect(next()).toBeDisabled()
    expect(screen.getByText('121–125 / 125 件 · 4 / 4 ページ')).toBeInTheDocument()
    expect(screen.getByTestId('map-count')).toHaveTextContent('125')
    expect(screen.getByTestId('map-selected')).toHaveTextContent('proposal-0')
    expect(screen.getByTestId('map-focus')).toHaveTextContent(focus!)
    fireEvent.click(reveal())
    expect(rows()[0]).toHaveFocus()
    expect(rows()[0]).toHaveAttribute('aria-pressed', 'true')
    // Paging does not fetch proposals or save placements.
    expect(spawnerPlanningApi.plan).toHaveBeenCalledTimes(1)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('jumps directly to a deep selected proposal with 40 DOM rows rather than rendering the prefix', async () => {
    vi.mocked(spawnerPlanningApi.plan).mockResolvedValue(createPlan(2000))
    await openAi()
    selectMap('proposal-1999')
    expect(rows()).toHaveLength(40)
    expect(rows().some(row => row.getAttribute('aria-pressed') === 'true')).toBe(false)
    const focus = screen.getByTestId('map-focus').textContent
    fireEvent.click(reveal())
    expect(rows()).toHaveLength(40)
    expect(rows()[39]).toHaveAttribute('data-proposal-id', 'proposal-1999')
    expect(rows()[39]).toHaveFocus()
    expect(screen.getByText('1,961–2,000 / 2,000 件 · 50 / 50 ページ')).toBeInTheDocument()
    expect(screen.getByTestId('map-focus')).toHaveTextContent(focus!)
    console.info('2000 AI proposals: final-row lookup DOM', { previousPrefixRows: 2000, currentRows: rows().length, mapProposals: 2000 })
    expect(spawnerPlanningApi.plan).toHaveBeenCalledTimes(1)
  })

  it('keeps a matching query and resolves the selected index within the filtered results', async () => {
    await openAi()
    fireEvent.change(screen.getByLabelText('配置案を検索'), { target: { value: '東の森' } })
    selectMap('proposal-121')
    fireEvent.click(reveal())
    expect(screen.getByLabelText('配置案を検索')).toHaveValue('東の森')
    expect(screen.getByText('41–62 / 62 件 · 2 / 2 ページ')).toBeInTheDocument()
    expect(document.activeElement).toHaveAttribute('data-proposal-id', 'proposal-121')
  })

  it('explains an excluded selection and clears only list search on explicit reveal', async () => {
    await openAi()
    fireEvent.change(screen.getByLabelText('地域'), { target: { value: 'west' } })
    fireEvent.change(screen.getByLabelText('配置状況'), { target: { value: 'covered' } })
    fireEvent.change(screen.getByLabelText('表示レイヤー'), { target: { value: 'reasons' } })
    fireEvent.change(screen.getByLabelText('配置案を検索'), { target: { value: 'no-match' } })
    selectMap('proposal-120')
    const focus = screen.getByTestId('map-focus').textContent
    expect(rows()).toHaveLength(0)
    expect(screen.getByLabelText('配置案を検索')).toHaveValue('no-match')
    expect(screen.getByText(/選択中の案は検索条件外です/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '検索を解除して選択案を表示' }))
    expect(screen.getByLabelText('配置案を検索')).toHaveValue('')
    expect(screen.getByText('41–63 / 63 件 · 2 / 2 ページ')).toBeInTheDocument()
    expect(document.activeElement).toHaveAttribute('data-proposal-id', 'proposal-120')
    expect(screen.getByLabelText('地域')).toHaveValue('west')
    expect(screen.getByLabelText('配置状況')).toHaveValue('covered')
    expect(screen.getByLabelText('表示レイヤー')).toHaveValue('reasons')
    expect(screen.getByTestId('map-focus')).toHaveTextContent(focus!)
    expect(screen.getByTestId('map-count')).toHaveTextContent('125')
    expect(spawnerPlanningApi.plan).toHaveBeenCalledTimes(1)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('resets pages on search/region changes and clears stale selection on a cell or region change', async () => {
    await openAi()
    selectMap('proposal-120'); fireEvent.click(reveal())
    const search = screen.getByLabelText('配置案を検索')
    search.focus()
    fireEvent.change(search, { target: { value: '森林狼' } })
    expect(rows()[0]).toHaveAttribute('data-proposal-id', 'proposal-0')
    expect(search).toHaveFocus()
    fireEvent.click(reveal())
    fireEvent.change(screen.getByLabelText('地域'), { target: { value: 'east' } })
    expect(rows()[0]).toHaveAttribute('data-proposal-id', 'proposal-1')
    expect(reveal()).toBeDisabled()
    selectMap('proposal-121')
    fireEvent.click(screen.getByRole('button', { name: '空地点を選択' }))
    expect(reveal()).toBeDisabled()
  })

  it('disables reveal during a radius request and failure, and resets after reload to a smaller plan', async () => {
    await openAi()
    selectMap('proposal-120'); fireEvent.click(reveal())
    let reject!: (error: Error) => void
    vi.mocked(spawnerPlanningApi.plan).mockReturnValueOnce(new Promise((_resolve, fail) => { reject = fail }))
    fireEvent.change(screen.getByLabelText('出現半径'), { target: { value: '32' } })
    expect(rows()).toHaveLength(0)
    expect(reveal()).toBeDisabled()
    expect(next()).toBeDisabled()
    await act(async () => reject(new Error('一時失敗')))
    expect(screen.getByRole('alert')).toHaveTextContent('一時失敗')
    expect(reveal()).toBeDisabled()
    vi.mocked(spawnerPlanningApi.plan).mockResolvedValue(createPlan(1))
    fireEvent.click(screen.getByRole('button', { name: '解析結果を再読込' }))
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(screen.getByText('1–1 / 1 件 · 1 / 1 ページ')).toBeInTheDocument()
    expect(reveal()).toBeDisabled()
  })

  it('preserves unfinished Dev input across AI list paging and reveal without saving or reloading Dev', async () => {
    render(<SpawnerPlanningWorkspace active />)
    await screen.findByText(/1 件を表示/)
    fireEvent.click(screen.getByRole('button', { name: 'Dev配置を選択' }))
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '-' } })
    fireEvent.click(screen.getByRole('button', { name: 'AI 配置案' }))
    await waitFor(() => expect(next()).toBeEnabled())
    selectMap('proposal-120'); fireEvent.click(reveal()); fireEvent.click(screen.getByRole('button', { name: '前の案ページ' }))
    fireEvent.click(screen.getByRole('button', { name: '配置編集' }))
    expect(screen.getByLabelText('X')).toHaveValue('-')
    expect(worldPlacementApi.read).toHaveBeenCalledTimes(1)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })
})
