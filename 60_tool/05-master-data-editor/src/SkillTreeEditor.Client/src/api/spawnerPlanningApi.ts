import { assertPlanningIdentity, validatePlanningPlan } from '../data/spawnerPlanning'
import type { PlanningCells, PlanningManifest, PlanningPlan, PlanningRegions, PlanningSnapshot } from '../data/spawnerPlanning'

export interface PlanningWorld { id: string; name: string }

export function spawnerPlanningUrl(route: string, revision?: string, worldId?: string): string {
  const query = new URLSearchParams()
  if (revision) query.set('revision', revision)
  if (worldId) query.set('worldId', worldId)
  return `/api/spawner-planning/${route}${query.size ? `?${query}` : ''}`
}

async function read<T>(route: string, signal: AbortSignal, revision?: string, worldId?: string): Promise<T> {
  const response = await fetch(spawnerPlanningUrl(route, revision, worldId), { signal })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.title ?? payload.message ?? `解析結果を取得できませんでした（${response.status}）`)
  return payload as T
}
export const spawnerPlanningApi = {
  worlds(signal: AbortSignal): Promise<PlanningWorld[]> { return read('worlds', signal) },
  async snapshot(signal: AbortSignal, worldId?: string): Promise<PlanningSnapshot> {
    const manifest = await read<PlanningManifest>('manifest', signal, undefined, worldId)
    if (manifest.schemaVersion !== 2 || manifest.previewOnly !== true || !manifest.exportRevision) throw new Error('対応していない解析結果です。全大陸の解析を実行してください。')
    if (worldId && manifest.worldId !== worldId) throw new Error('選択したワールドと解析結果が一致しません。')
    const [cells, regions] = await Promise.all([
      read<PlanningCells>('cells', signal, manifest.exportRevision, worldId),
      read<PlanningRegions>('regions', signal, manifest.exportRevision, worldId),
    ])
    assertPlanningIdentity(manifest, cells)
    assertPlanningIdentity(manifest, regions)
    const cellSize = manifest.bounds.cellSize ?? manifest.cellSize
    if (!cellSize || cellSize <= 0 || manifest.bounds.width / cellSize * (manifest.bounds.height / cellSize) !== cells.cells.length) {
      throw new Error('解析範囲と区画数が一致しません。未生成区画を含む解析一式を確認してください。')
    }
    manifest.bounds.cellSize = cellSize
    return { manifest, cells: cells.cells, regions: regions.regions }
  },
  async plan(snapshot: PlanningSnapshot, radius: number, signal: AbortSignal, worldId?: string): Promise<PlanningPlan> {
    const plan = await read<PlanningPlan>(`proposals/${radius}`, signal, snapshot.manifest.exportRevision, worldId)
    validatePlanningPlan(snapshot, plan)
    return plan
  },
}
