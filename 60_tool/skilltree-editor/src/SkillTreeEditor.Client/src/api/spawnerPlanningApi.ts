import { assertPlanningIdentity, validatePlanningPlan } from '../data/spawnerPlanning'
import type { PlanningCells, PlanningManifest, PlanningPlan, PlanningRegions, PlanningSnapshot } from '../data/spawnerPlanning'

async function read<T>(route: string, signal: AbortSignal, revision?: string): Promise<T> {
  const response = await fetch(`/api/spawner-planning/${route}${revision ? `?revision=${encodeURIComponent(revision)}` : ''}`, { signal })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.title ?? payload.message ?? `解析結果を取得できませんでした（${response.status}）`)
  return payload as T
}
export const spawnerPlanningApi = {
  async snapshot(signal: AbortSignal): Promise<PlanningSnapshot> {
    const manifest = await read<PlanningManifest>('manifest', signal)
    if (manifest.schemaVersion !== 2 || manifest.previewOnly !== true || !manifest.exportRevision) throw new Error('対応していない解析結果です。全大陸の解析を実行してください。')
    const [cells, regions] = await Promise.all([
      read<PlanningCells>('cells', signal, manifest.exportRevision),
      read<PlanningRegions>('regions', signal, manifest.exportRevision),
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
  async plan(snapshot: PlanningSnapshot, radius: number, signal: AbortSignal): Promise<PlanningPlan> {
    const plan = await read<PlanningPlan>(`proposals/${radius}`, signal, snapshot.manifest.exportRevision)
    validatePlanningPlan(snapshot, plan)
    return plan
  },
}
