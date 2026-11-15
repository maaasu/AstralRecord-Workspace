export type GatheringSpawnerMarker = { id: string; x: number; y: number; z: number }

function isGatheringSpawnerMarker(value: unknown): value is GatheringSpawnerMarker {
  if (!value || typeof value !== 'object') return false
  const marker = value as Partial<GatheringSpawnerMarker>
  return typeof marker.id === 'string' && !!marker.id.trim()
    && [marker.x, marker.y, marker.z].every(Number.isSafeInteger)
}

export const gatheringSpawnerApi = {
  async list(worldId: string, signal: AbortSignal): Promise<GatheringSpawnerMarker[]> {
    const response = await fetch(`/api/world-placements/gathering-spawners?${new URLSearchParams({ worldId })}`, { signal, cache: 'no-store' })
      .catch(() => { throw new Error('採集スポナーの取得先に接続できません。') })
    if (!response.ok) {
      const payload = await response.json().catch(() => null) as { title?: string } | null
      throw new Error(payload?.title || `採集スポナーを取得できませんでした（${response.status}）`)
    }
    const payload: unknown = await response.json()
    if (!Array.isArray(payload) || !payload.every(isGatheringSpawnerMarker)) throw new Error('採集スポナーの応答形式を確認してください。')
    return payload
  },
}
