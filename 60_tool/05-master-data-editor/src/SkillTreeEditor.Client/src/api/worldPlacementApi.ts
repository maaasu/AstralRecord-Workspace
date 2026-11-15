export type PlacementKind = 'spawner' | 'npc' | 'waystone'
export interface WorldPlacement {
  placementId: string
  kind: PlacementKind
  worldId: string
  worldName: string
  templateId: string | null
  x: number
  y: number
  z: number
  yaw: number
  pitch: number
  enabled: boolean
  waystone: null | { id: string; name: string; lockEnabled: boolean; unlockGold: number; icon: string | null; createdAt: string | null; createdBy: string }
}
export interface PlacementSnapshot { schemaVersion: 1; revision: string; placements: WorldPlacement[] }

export class WorldPlacementConflictError extends Error {
  constructor() {
    super('他の編集者が先に保存しました。編集内容は残っています。変更を控えてから再読込し、差分を確認してください。')
  }
}

async function request(method: 'GET' | 'PUT', body?: unknown, placementId?: string): Promise<PlacementSnapshot> {
  let response: Response
  try {
    const path = '/api/world-placements/dev' + (placementId === undefined ? '' : `/${encodeURIComponent(placementId)}`)
    response = await fetch(path, method === 'GET'
      ? { method, cache: 'no-store' }
      : { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  } catch { throw new Error('Dev 配置 API に接続できません。編集内容は画面に残っています。') }
  const payload = await response.json().catch(() => null) as PlacementSnapshot & { title?: string; message?: string } | null
  if (response.status === 409) throw new WorldPlacementConflictError()
  if (!response.ok) throw new Error(payload?.title ?? payload?.message ?? `Dev 配置の操作に失敗しました (${response.status})`)
  if (!payload || payload.schemaVersion !== 1 || !Array.isArray(payload.placements) || !payload.revision)
    throw new Error('Dev 配置 API の応答形式を確認してください。')
  return payload
}

export const worldPlacementApi = {
  read: () => request('GET'),
  save: (expectedRevision: string, placements: WorldPlacement[]) => request('PUT', { expectedRevision, placements }),
  savePlacement: (expectedRevision: string, placement: WorldPlacement) =>
    request('PUT', { expectedRevision, placement }, placement.placementId),
}
