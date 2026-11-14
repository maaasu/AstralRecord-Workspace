import { ApiError } from './editorApi'
import type { JsonValue } from '../types/editor'
import type { ProgressionDesignCatalog } from '../types/progressionDesign'

async function request(path: string, documents?: { path: string; content: JsonValue; revision?: string }[], signal?: AbortSignal): Promise<ProgressionDesignCatalog> {
  const response = await fetch(`/api/master-data/progression-design${path}`, {
    method: documents ? 'POST' : 'GET', signal,
    headers: { 'Content-Type': 'application/json' },
    ...(documents ? { body: JSON.stringify({ documents }) } : {}),
  })
  const result = await response.json()
  if (!response.ok) throw new ApiError(result.message ?? result.title ?? '設計データの取得に失敗しました。', response.status, result)
  return result
}
export const progressionDesignApi = {
  catalog: (reload = false, signal?: AbortSignal) => request(reload ? '?reload=true' : '', undefined, signal),
  preview: (documents: { path: string; content: JsonValue; revision?: string }[], signal?: AbortSignal) => request('/preview', documents, signal),
}
