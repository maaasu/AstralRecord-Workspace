export type ChallengeEntry = {
  path: string
  kind: 'boss' | 'dungeon'
  name: string
  worldReference: string
  x: number
  y: number
  z: number
}

function isChallengeEntry(value: unknown): value is ChallengeEntry {
  if (!value || typeof value !== 'object') return false
  const entry = value as Partial<ChallengeEntry>
  return typeof entry.path === 'string' && !!entry.path
    && (entry.kind === 'boss' || entry.kind === 'dungeon')
    && typeof entry.name === 'string'
    && typeof entry.worldReference === 'string' && !!entry.worldReference
    && [entry.x, entry.y, entry.z].every(Number.isFinite)
}

export const challengeEntryApi = {
  async list(worldId: string, signal: AbortSignal): Promise<ChallengeEntry[]> {
    const response = await fetch(`/api/world-placements/challenge-entries?${new URLSearchParams({ worldId })}`, { signal, cache: 'no-store' })
    if (!response.ok) throw new Error(`受付地点を取得できませんでした（${response.status}）`)
    const payload: unknown = await response.json()
    if (!Array.isArray(payload) || !payload.every(isChallengeEntry)) throw new Error('受付地点の応答形式を確認してください。')
    return payload
  },
}
