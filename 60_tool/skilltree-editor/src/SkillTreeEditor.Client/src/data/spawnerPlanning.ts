export interface PlanningIdentity { schemaVersion: number; worldId: string; terrainFingerprint: string; exportRevision: string }
export interface PlanningBounds { minX: number; minZ: number; width: number; height: number; cellSize: number }
export interface PlanningRoad {
  nodes: { x: number; z: number; distanceMeters: number; kind?: 'road' | 'terrain' }[]
  edges: [number, number][]
  maxDistanceMeters: number; corridorMeters: number; candidateCenterMeters: number
  terrainExtension?: { maxDistanceMeters: number }
}
export function planningRoad(manifest: PlanningManifest): PlanningRoad | null {
  const policy = manifest.progressionPolicy as { mode?: string; road?: PlanningRoad } | null
  return policy?.mode === 'road' && policy.road ? policy.road : null
}
export function planningLevelStepMeters(manifest: PlanningManifest): number | null {
  const policy = manifest.progressionPolicy as { mode?: string; levelStepMeters?: number } | null
  const step = policy?.levelStepMeters
  return policy?.mode === 'road' && typeof step === 'number' && Number.isInteger(step) && step > 0 ? step : null
}
export interface PlanningManifest extends PlanningIdentity {
  worldName: string
  previewOnly: boolean
  bounds: PlanningBounds
  cellSize?: number
  spawn: { x: number; y: number; z: number }
  capturedAt: string
  radiusOptions: number[]
  reasons: Record<string, string>
  limitations: string[]
  progressionPolicy: unknown
}
export interface PlanningCell {
  x: number; z: number; y: number | null
  status: string; surface: string | null; ground: string | null; biome: string | null
  safeCount: number | null; waterCount: number | null; treeCount: number | null
  candidateX: number | null; candidateY: number | null; candidateZ: number | null
  minY: number | null; maxY: number | null
  habitat: string; regionId: string; targetLevel: number | null; reasonCode: string; reason: string
  roadDistanceMeters?: number | null; distanceToRoadMeters?: number | null
}
export interface PlanningRegion {
  id: string; name: string; habitat: string; levelMin: number | null; levelMax: number | null
  cellCount: number; eligibleCells: number; coveredCellsByRadius: Record<string, number>
  spawnerIds: string[]; reason: string
}
export interface PlanningMob { mobId: string; name: string; level: number; weight: number }
export interface PlanningProposal {
  proposalId: string; spawnerId: string; x: number; y: number; z: number; radiusMeters: number
  originalRadiusMeters: number | null; masterUpdateRequired: boolean
  definitionStatus: 'existing' | 'draft'; draftDefinition?: unknown
  regionId: string; regionName: string; habitat: string; level: number
  spawnMobs: PlanningMob[]; reason: string; newCoveredCells: number; coveredCellIndices: number[]
}
export interface PlanningPlan extends PlanningIdentity {
  previewOnly: boolean; radiusMeters: number; proposals: PlanningProposal[]; reasonCodes: string[]
  counts: Record<string, number>; targetCells: number; coveredCells: number; uncoveredCells: number; coveragePercent: number
}
export interface PlanningCells extends PlanningIdentity { cells: PlanningCell[] }
export interface PlanningRegions extends PlanningIdentity { regions: PlanningRegion[] }
export interface PlanningSnapshot { manifest: PlanningManifest; cells: PlanningCell[]; regions: PlanningRegion[] }

export const habitatNames: Record<string, string> = {
  grass: '草原', forest: '森林', snow: '雪原・雪山', dry: '乾燥地', desert: '砂漠',
  swamp: '湿地', water: '水域', volcanic: '火山', rocky: '岩山', other: '未分類', unknown: '未解析',
}
export const reasonColors: Record<string, string> = {
  covered: '#89bb6c', eligible: '#7ba26c', protected: '#ddc56f', water: '#3c6586',
  absent: '#29323f', unknown: '#df6278', failed: '#df6278', unsafe: '#8b7d72', steep: '#b19682',
  unsupported: '#bc87b7', unsupported_habitat: '#bc87b7', unsupported_level: '#c294d5',
  uncovered: '#efa65c', budget: '#e58660', boundary: '#e7a970',
  reserved_future: '#58657b',
  terrain_barrier: '#9e7c75',
}
export function reasonColor(code: string) {
  return reasonColors[code] ?? (code.includes('level') ? '#c294d5' : code.includes('habitat') || code.includes('master') ? '#bc87b7' : '#999ab2')
}
export function levelColor(level: number | null) {
  if (level == null) return '#646e7c'
  return `hsl(${Math.max(0, Math.min(135, 135 - (level - 15) * 135 / 35))} 45% 52%)`
}
export function terrainColor(cell: PlanningCell) {
  if (cell.status === 'absent') return '#1c2634'
  if (cell.status !== 'ok') return '#cf6074'
  const material = cell.surface ?? ''
  if (material.includes('water')) return '#365b81'
  if (material.includes('lava')) return '#de7e39'
  if (/snow|ice/.test(material)) return '#d0dce0'
  if (material.includes('cherry')) return '#d5a7c5'
  if (/leaves|moss/.test(material)) return '#456e43'
  if (/sand|terracotta/.test(material)) return material.includes('red') || material.includes('orange') ? '#bb854a' : '#c3b582'
  if (/basalt|blackstone|obsidian/.test(material)) return '#494857'
  if (/stone|gravel/.test(material)) return '#858d83'
  return cell.habitat === 'forest' ? '#4e7742' : '#849d55'
}
export function assertPlanningIdentity(manifest: PlanningManifest, value: PlanningIdentity) {
  if (manifest.schemaVersion !== 2 || value.schemaVersion !== 2 || !manifest.exportRevision ||
      manifest.exportRevision !== value.exportRevision || manifest.worldId !== value.worldId ||
      manifest.terrainFingerprint !== value.terrainFingerprint) {
    throw new Error('解析結果の版が一致しません。「解析結果を再読込」で一式を読み直してください。')
  }
}
export function validatePlanningPlan(snapshot: PlanningSnapshot, plan: PlanningPlan) {
  assertPlanningIdentity(snapshot.manifest, plan)
  if (plan.previewOnly !== true || !snapshot.manifest.radiusOptions.includes(plan.radiusMeters) ||
      plan.reasonCodes.length !== snapshot.cells.length ||
      Object.values(plan.counts).reduce((sum, count) => sum + count, 0) !== snapshot.cells.length) {
    throw new Error('配置案の区画数・半径・プレビュー指定が不正です。解析結果を作り直してください。')
  }
  for (const proposal of plan.proposals) {
    if (proposal.radiusMeters !== plan.radiusMeters || proposal.spawnMobs.length === 0 ||
        proposal.coveredCellIndices.some(index => !Number.isInteger(index) || index < 0 || index >= snapshot.cells.length)) {
      throw new Error('配置案に不正な出現範囲または区画参照があります。')
    }
  }
}
export function cellAtWorld(bounds: PlanningBounds, grid: Map<string, number>, x: number, z: number) {
  if (x < bounds.minX || z < bounds.minZ || x >= bounds.minX + bounds.width || z >= bounds.minZ + bounds.height) return undefined
  const cx = Math.floor((x - bounds.minX) / bounds.cellSize) * bounds.cellSize + bounds.minX
  const cz = Math.floor((z - bounds.minZ) / bounds.cellSize) * bounds.cellSize + bounds.minZ
  return grid.get(`${cx},${cz}`)
}
