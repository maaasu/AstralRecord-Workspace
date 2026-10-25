import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SpawnerPlanningWorkspace } from './SpawnerPlanningWorkspace'
import { spawnerPlanningApi } from '../api/spawnerPlanningApi'
import type { PlanningPlan, PlanningSnapshot } from '../data/spawnerPlanning'

vi.mock('../api/spawnerPlanningApi', () => ({ spawnerPlanningApi: { snapshot: vi.fn(), plan: vi.fn() } }))
vi.mock('./SpawnerPlanningMap', () => ({ SpawnerPlanningMap: ({ onCell }: { onCell: (index: number) => void }) => <button onClick={() => onCell(1)}>解析失敗区画を選択</button> }))
const identity = { schemaVersion: 2, worldId: 'test', terrainFingerprint: 'terrain', exportRevision: 'revision-a' }
const snapshot: PlanningSnapshot = {
  manifest: { ...identity, worldName: 'テスト大陸', previewOnly: true, bounds: { minX: 0, minZ: 0, width: 48, height: 16, cellSize: 16 }, spawn: { x: 0, y: 70, z: 0 }, capturedAt: '2026-10-25T00:00:00Z', radiusOptions: [32, 48, 64], reasons: { covered: '新しい配置案でカバー', failed: '地形の読込・計測に失敗', absent: '未保存の区画' }, limitations: ['実配置は行いません'], progressionPolicy: {} },
  cells: [0, 1, 2].map((index) => ({ x: index * 16, z: 0, y: 70, status: index === 1 ? 'failed' : index === 2 ? 'absent' : 'ok', surface: 'minecraft:sand', ground: 'minecraft:sand', biome: 'minecraft:desert', safeCount: index === 0 ? 256 : 0, waterCount: 0, treeCount: 0, candidateX: index * 16 + 8, candidateY: 70, candidateZ: 8, minY: 70, maxY: 70, habitat: 'dry', regionId: 'east-dry', targetLevel: 38, reasonCode: index === 1 ? 'failed' : index === 2 ? 'absent' : 'covered', reason: index === 1 ? 'チャンク読込が失敗しました' : '' })),
  regions: [{ id: 'east-dry', name: '東部乾燥地', habitat: 'dry', levelMin: 38, levelMax: 38, cellCount: 3, eligibleCells: 1, coveredCellsByRadius: { '32': 1, '48': 1, '64': 1 }, spawnerIds: ['draft-sand'], reason: '乾燥地の野外モブを選定' }],
}
const plan: PlanningPlan = { ...identity, previewOnly: true, radiusMeters: 64, proposals: [{ proposalId: 'proposal', spawnerId: 'draft-sand', x: 8, y: 70, z: 8, radiusMeters: 64, originalRadiusMeters: null, masterUpdateRequired: true, definitionStatus: 'draft', regionId: 'east-dry', regionName: '東部乾燥地', habitat: 'dry', level: 38, spawnMobs: [{ mobId: 'sand-gecko', name: '砂ヤモリ', level: 38, weight: 100 }], reason: '乾燥地の実在Lv38を選定', newCoveredCells: 1, coveredCellIndices: [0] }], reasonCodes: ['covered', 'failed', 'absent'], counts: { covered: 1, failed: 1, absent: 1 }, targetCells: 1, coveredCells: 1, uncoveredCells: 0, coveragePercent: 100 }
beforeEach(() => { vi.resetAllMocks(); vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(snapshot); vi.mocked(spawnerPlanningApi.plan).mockResolvedValue(plan) })

describe('マスタエディタ内の配置計画', () => {
  it('未登録案と解析失敗を表示し、公開操作を用意しない', async () => {
    render(<SpawnerPlanningWorkspace active />)
    fireEvent.click(await screen.findByRole('button', { name: /Lv.38 · 東部乾燥地/ }))
    expect(screen.getByText('未登録のスポナーマスタ案')).toBeInTheDocument()
    expect(screen.getByText('draft-sand')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '解析失敗区画を選択' }))
    expect(screen.getByText('チャンク読込が失敗しました')).toBeInTheDocument()
    expect(screen.getByText('未完成・解析不可区画').parentElement).toHaveTextContent('1')
    expect(screen.queryByRole('button', { name: /公開|適用|実配置/ })).not.toBeInTheDocument()
  })
  it('解析が未準備のとき、空の成功結果として表示しない', async () => {
    vi.mocked(spawnerPlanningApi.snapshot).mockRejectedValue(new Error('配置案はまだありません'))
    render(<SpawnerPlanningWorkspace active />)
    expect(await screen.findByRole('alert')).toHaveTextContent('配置案はまだありません')
    expect(screen.queryByText('100.0%')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '解析結果を再読込' })).toBeEnabled()
  })
  it('道路モードでは後続地域の保留と道路距離を表示する', async () => {
    const roadSnapshot = structuredClone(snapshot)
    roadSnapshot.manifest.progressionPolicy = { mode: 'road', maxLevel: 45, levelStepMeters: 300, road: { nodes: [], edges: [], maxDistanceMeters: 1400, corridorMeters: 384, candidateCenterMeters: 320 } }
    roadSnapshot.cells[1].roadDistanceMeters = 648
    roadSnapshot.cells[1].distanceToRoadMeters = 32
    vi.mocked(spawnerPlanningApi.snapshot).mockResolvedValue(roadSnapshot)
    render(<SpawnerPlanningWorkspace active />)
    expect(await screen.findByText(/北東のLv.100超予定地/)).toBeInTheDocument()
    expect(screen.getByText(/道路距離300mごと/)).toHaveTextContent('未到達の距離帯は配置しません')
    fireEvent.click(screen.getByRole('button', { name: '解析失敗区画を選択' }))
    expect(screen.getByText(/道に沿った距離:/)).toHaveTextContent('648m')
    expect(screen.getByText(/道からの距離:/)).toHaveTextContent('32m')
  })
  it('半径切替の読込中は前の案の詳細と保存を消す', async () => {
    render(<SpawnerPlanningWorkspace active />)
    fireEvent.click(await screen.findByRole('button', { name: /Lv.38 · 東部乾燥地/ }))
    vi.mocked(spawnerPlanningApi.plan).mockReturnValue(new Promise(() => {}))
    fireEvent.change(screen.getByRole('combobox', { name: '出現半径' }), { target: { value: '32' } })
    await waitFor(() => expect(screen.queryByText('draft-sand')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: '案をJSON保存' })).toBeDisabled()
  })
})
