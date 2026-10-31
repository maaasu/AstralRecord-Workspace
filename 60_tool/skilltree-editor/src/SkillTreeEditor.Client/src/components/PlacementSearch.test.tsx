import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorldPlacementEditor } from './WorldPlacementEditor'
import { SpawnerPlanningWorkspace } from './SpawnerPlanningWorkspace'
import { worldPlacementApi, type WorldPlacement } from '../api/worldPlacementApi'
import { spawnerPlanningApi } from '../api/spawnerPlanningApi'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'

vi.mock('../api/worldPlacementApi', () => ({ worldPlacementApi: { read: vi.fn(), save: vi.fn() } }))
vi.mock('../api/spawnerPlanningApi', () => ({ spawnerPlanningApi: { snapshot: vi.fn(), plan: vi.fn() } }))
vi.mock('./SpawnerPlanningMap', () => ({ SpawnerPlanningMap: ({ plan, region, placements }: { plan: PlanningPlan | null; region: string; placements: WorldPlacement[] }) => <>
  <output data-testid="map-proposal-count">{plan?.proposals.length ?? 0}</output><output data-testid="map-region">{region}</output><output data-testid="map-placement-ids">{placements.map(item => item.placementId).join(',')}</output>
</> }))

const identity = { schemaVersion: 2, worldId: 'eriva', terrainFingerprint: 'terrain', exportRevision: 'revision' }
const snapshot: PlanningSnapshot = {
  manifest: { ...identity, worldName: 'エリヴァ', previewOnly: true, bounds: { minX: -16, minZ: 0, width: 32, height: 16, cellSize: 16 }, spawn: { x: -4, y: 70, z: 3 }, capturedAt: '', radiusOptions: [32, 64], reasons: { covered: 'カバー済み' }, limitations: [], progressionPolicy: {} },
  cells: [-16, 0].map((x, index) => ({ x, z: 0, y: 70, status: 'ok', surface: 'grass', ground: 'dirt', biome: 'plains', safeCount: 256, waterCount: 0, treeCount: 0, candidateX: x + 8, candidateY: 70, candidateZ: 8, minY: 70, maxY: 70, habitat: 'grass', regionId: index ? 'east' : 'west', targetLevel: 15, reasonCode: 'eligible', reason: '' })),
  regions: ['west', 'east'].map((id, index) => ({ id, name: index ? '東の森' : '西の草原', habitat: 'grass', levelMin: 15, levelMax: 15, cellCount: 1, eligibleCells: 1, coveredCellsByRadius: { '32': 1, '64': 1 }, spawnerIds: [], reason: '' })),
}
const placement = (id: string, templateId = 'wolf', x = -4): WorldPlacement => ({ placementId: id, kind: 'spawner', worldId: 'eriva', worldName: 'worlds/eriva', templateId, x, y: 70, z: 3, yaw: 0, pitch: 0, enabled: true, waystone: null })
const catalog = { spawners: [{ id: 'wolf', name: '森&b林&#0088ff狼 Lv.15', radiusMeters: 64, levels: [15] }, { id: 'fox', name: 'キツネ Lv.15', radiusMeters: 32, levels: [15] }], npcs: [{ id: 'guide', name: '森&aの案内人' }], worlds: [{ id: 'eriva', name: 'エリヴァ', baseWorldPath: 'worlds/eriva' }] }
function proposal(id: string, regionId = 'west'): PlanningPlan['proposals'][number] {
  return { proposalId: id, spawnerId: 'wolf-template', x: regionId === 'west' ? -4 : 4, y: 70, z: 3, radiusMeters: 64, originalRadiusMeters: 64, masterUpdateRequired: false, definitionStatus: 'existing', regionId, regionName: regionId === 'west' ? '西の草原' : '東の森', habitat: 'grass', level: 15, spawnMobs: [{ mobId: 'wolf', name: '森&a林&#aabbcc狼', level: 15, weight: 100 }], reason: '', newCoveredCells: 1, coveredCellIndices: [0] }
}
const plan: PlanningPlan = { ...identity, previewOnly: true, radiusMeters: 64, proposals: [proposal('west-wolf'), proposal('east-wolf', 'east'), { ...proposal('west-fox'), spawnerId: 'fox-template', y: 80, spawnMobs: [{ mobId: 'fox', name: 'キツネ', level: 15, weight: 100 }] }], reasonCodes: ['covered', 'covered'], counts: { covered: 2 }, targetCells: 2, coveredCells: 2, uncoveredCells: 0, coveragePercent: 100 }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev', placements: [] })
  vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(snapshot)
  vi.mocked(spawnerPlanningApi.plan).mockResolvedValue(plan)
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => catalog })))
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('placement list search', () => {
  it('combines colored names, region, ID and signed coordinates without changing saved placements', async () => {
    const items = [placement('west-wolf'), placement('east-wolf', 'wolf', 4), { ...placement('guide-position', 'guide'), kind: 'npc' as const }, { ...placement('stone-position'), kind: 'waystone' as const, templateId: null, waystone: { id: 'waystone-home', name: '旅&#aabbcc人の道標', icon: 'LODESTONE', lockEnabled: false, unlockGold: 0, createdAt: null, createdBy: 'fixture' } }]
    vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev', placements: items })
    const visible = vi.fn(), dirty = vi.fn()
    render(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId={null} onSelected={vi.fn()} onPlacements={visible} placing={false} onPlacing={vi.fn()} onDirtyChange={dirty} />)
    await screen.findByText(/4 件を表示/)
    const query = screen.getByRole('textbox', { name: '保存済み配置を検索' })
    const searchFor = (value: string, ids: string[]) => {
      fireEvent.change(query, { target: { value } })
      expect(visible.mock.lastCall?.[0].map((item: WorldPlacement) => item.placementId)).toEqual(ids)
    }
    searchFor('草原　森林狼  WOLF -4', ['west-wolf'])
    expect(screen.getByText(/1 件を表示/)).toBeInTheDocument()
    searchFor('  GUIDE\t案内人 ', ['guide-position'])
    searchFor('旅人 WAYSTONE-HOME', ['stone-position'])
    searchFor('森林狼 ない地域', [])
    fireEvent.click(screen.getByRole('button', { name: /^詳細条件/ }))
    fireEvent.change(screen.getByLabelText('種類'), { target: { value: 'npc' } })
    searchFor('　 \t', ['guide-position'])
    expect(query).toHaveAccessibleDescription(/空白で区切ると/)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
    expect(worldPlacementApi.read).toHaveBeenCalledTimes(1)
    expect(dirty).toHaveBeenLastCalledWith(false)
  })

  it('retains bulk selection and unfinished coordinates when the chosen placement falls outside a multi-word search', async () => {
    vi.mocked(worldPlacementApi.read).mockResolvedValue({ schemaVersion: 1, revision: 'rev', placements: [placement('chosen'), placement('other', 'fox')] })
    const visible = vi.fn()
    render(<WorldPlacementEditor snapshot={snapshot} point={null} selectedId="chosen" onSelected={vi.fn()} onPlacements={visible} placing={false} onPlacing={vi.fn()} />)
    await screen.findByLabelText('X')
    fireEvent.click(screen.getByLabelText(/chosen.*を一括選択$/))
    fireEvent.change(screen.getByLabelText('X'), { target: { value: '-' } })
    fireEvent.change(screen.getByLabelText('保存済み配置を検索'), { target: { value: '草原 キツネ' } })
    expect(screen.getByLabelText('X')).toHaveValue('-')
    expect(screen.getByText(/選択中の配置は絞り込み条件外/)).toBeInTheDocument()
    expect(visible.mock.lastCall?.[0].map((item: WorldPlacement) => item.placementId)).toEqual(['other', 'chosen'])
    expect(screen.getByRole('button', { name: /^一括操作/ })).toHaveTextContent('合計 1 件 · 条件内 0 件 · 条件外 1 件')
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('filters AI proposals across fields while keeping the map and metrics at their region scope', async () => {
    const { container } = render(<SpawnerPlanningWorkspace active />)
    fireEvent.click(screen.getByRole('button', { name: 'AI 配置案' }))
    await screen.findByRole('textbox', { name: '配置案を検索' })
    await screen.findByRole('button', { name: /Lv.15 · 西の草原.*森林狼/ })
    const list = () => within(container.querySelector<HTMLElement>('.sp-proposal-list')!)
    const query = screen.getByRole('textbox', { name: '配置案を検索' })
    fireEvent.change(query, { target: { value: '森林狼　草原 70 WOLF' } })
    expect(list().getAllByRole('button')).toHaveLength(1)
    expect(list().getByRole('button')).toHaveTextContent('X -4 / Y 70 / Z 3')
    expect(screen.getByTestId('map-proposal-count')).toHaveTextContent('3')
    expect(container.querySelector('.sp-metrics')?.textContent).toContain('3全大陸の配置案')
    fireEvent.change(query, { target: { value: 'WEST-FOX 80' } })
    expect(list().getByRole('button')).toHaveTextContent('キツネ')
    fireEvent.change(screen.getByLabelText('地域'), { target: { value: 'east' } })
    expect(list().queryByRole('button')).not.toBeInTheDocument()
    fireEvent.change(query, { target: { value: '　 ' } })
    expect(list().getAllByRole('button')).toHaveLength(1)
    expect(screen.getByTestId('map-region')).toHaveTextContent('east')
    expect(screen.getByTestId('map-proposal-count')).toHaveTextContent('3')
    expect(spawnerPlanningApi.plan).toHaveBeenCalledTimes(1)
    expect(worldPlacementApi.save).not.toHaveBeenCalled()
  })

  it('reuses AI search text during typing and rebuilds it for new plan data', async () => {
    const large = { ...plan, proposals: Array.from({ length: 2000 }, (_, index) => ({ ...proposal(`large-${index}`), spawnerId: `__placement_perf_${index}`, spawnMobs: [{ mobId: 'wolf', name: 'Wolf', level: 15, weight: 100 }] })) }
    vi.mocked(spawnerPlanningApi.plan).mockImplementation(async (_snapshot, radius) => ({ ...large, radiusMeters: radius }))
    const originalLower = String.prototype.toLocaleLowerCase
    let normalized = 0
    vi.spyOn(String.prototype, 'toLocaleLowerCase').mockImplementation(function (this: string, ...args) {
      if (String(this).includes('__placement_perf_')) normalized++
      return originalLower.apply(this, args)
    })
    const { container } = render(<SpawnerPlanningWorkspace active />)
    fireEvent.click(screen.getByRole('button', { name: 'AI 配置案' }))
    await screen.findByText('1–40 / 2,000 件 · 1 / 50 ページ')
    const initial = normalized
    normalized = 0
    for (const value of ['w', 'wo', 'wol', 'wolf']) fireEvent.change(screen.getByLabelText('配置案を検索'), { target: { value } })
    const typing = normalized
    console.info('2000 AI proposals: search text normalization', { initial, fourSearches: typing })
    expect.soft(initial).toBe(2000)
    expect.soft(typing).toBe(0)
    expect(container.querySelectorAll('.sp-proposal-list button')).toHaveLength(40)
    normalized = 0
    fireEvent.change(screen.getByLabelText('地域'), { target: { value: 'west' } })
    expect(normalized).toBe(0)
    vi.mocked(spawnerPlanningApi.plan).mockResolvedValue({ ...plan, radiusMeters: 32, proposals: [{ ...proposal('changed'), spawnerId: 'boar-template', spawnMobs: [{ mobId: 'boar', name: 'イノシシ', level: 15, weight: 100 }] }] })
    fireEvent.change(screen.getByLabelText('出現半径'), { target: { value: '32' } })
    await screen.findByText('この条件の配置案はありません。未配置理由を確認してください。')
    fireEvent.change(screen.getByLabelText('配置案を検索'), { target: { value: 'イノシシ 草原' } })
    await screen.findByRole('button', { name: /Lv.15 · 西の草原.*イノシシ/ })
    expect(screen.getByTestId('map-proposal-count')).toHaveTextContent('1')
  })
})
