import { ApiError } from './editorApi'
import { flagAndroidSessionExpiry } from '../androidEditor'
import type { MasterPredictionEntry } from '../data/masterPrediction'
import type { JsonValue, ValidationIssue, ValidationReport } from '../types/editor'
import type { MasterCatalog, MasterDocument, MasterDraft, MasterFileSummary, MasterFilterIndex, MasterReference } from '../types/masterData'

const endpoint = '/api/master-data'

async function request<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  const init: RequestInit = {
    method,
    headers: { 'Content-Type': 'application/json' },
  }
  if (body !== undefined) init.body = JSON.stringify(body)
  if (signal) init.signal = signal
  let response: Response
  try { response = await fetch(`${endpoint}${path}`, init) }
  catch (reason) {
    if (signal?.aborted) throw reason
    throw new ApiError('エディタのサーバーに接続できません。サーバーの起動と接続を確認して再試行してください。', 0)
  }
  flagAndroidSessionExpiry(response)
  const text = response.status === 204 ? '' : await response.text().catch((reason) => {
    if (signal?.aborted) throw reason
    throw new ApiError('サーバーの応答を受信できませんでした。接続を確認して再試行してください。', 0)
  })
  let payload: unknown = text
  if (response.headers.get('content-type')?.includes('json') && text) {
    try { payload = JSON.parse(text) } catch {
      if (response.ok) throw new ApiError('サーバーの応答を読み取れませんでした。再試行してください。', response.status)
    }
  }
  if (!response.ok) {
    const details = payload && typeof payload === 'object' ? payload as { message?: string; title?: string } : undefined
    throw new ApiError(details?.message ?? details?.title ?? `操作に失敗しました (${response.status})`, response.status, payload)
  }
  return payload as T
}

export const masterDataApi = {
  catalog: (reload = false) => request<MasterCatalog>(reload ? '/catalog?reload=true' : '/catalog'),
  files: (category = '', query = '', reload = false) =>
    request<MasterFileSummary[]>(`/files?${new URLSearchParams({ category, query, ...(reload ? { reload: 'true' } : {}) })}`),
  file: (path: string, signal?: AbortSignal) => request<MasterDocument>(`/file?${new URLSearchParams({ path })}`, 'GET', undefined, signal),
  save: (path: string, raw: string, revision: string, requireBossEntryWorldMaster?: boolean) =>
    request<MasterDocument>('/file', 'PUT', { path, raw, revision, requireBossEntryWorldMaster }),
  create: (draft: MasterDraft) => request<MasterDocument>('/file', 'POST', draft),
  copy: (sourcePath: string, revision: string, draft: MasterDraft) => request<MasterDocument>('/copy', 'POST', {
    sourcePath, revision, targetPath: draft.path, autoItemId: draft.autoItemId, slug: draft.slug, group: draft.group, autoName: draft.autoName,
  }),
  delete: (path: string, revision: string) => request<void>(`/file?${new URLSearchParams({ path, revision })}`, 'DELETE'),
  parse: (path: string, raw: string) => request<{ content: JsonValue; issues: ValidationIssue[] }>('/parse', 'POST', { path, raw }),
  render: (path: string, content: JsonValue, originalRaw?: string) => request<{ raw: string; commentsPreserved: boolean; warnings: string[] }>('/render', 'POST', { path, content, originalRaw }),
  validate: (path: string, raw: string) => request<ValidationReport>('/validate', 'POST', { path, raw }),
  references: (path: string) => request<MasterReference[]>(`/references?${new URLSearchParams({ path })}`),
  candidates: () => request<MasterReference[]>('/candidates'),
  predictionIndex: (signal?: AbortSignal) => request<MasterPredictionEntry[]>('/prediction-index', 'GET', undefined, signal),
  filterIndex: () => request<MasterFilterIndex>('/filter-index'),
  documentation: (path: string) => request<{ path: string; raw: string }>(`/documentation?${new URLSearchParams({ path })}`),
  exportUrl: (path: string) => `${endpoint}/export?${new URLSearchParams({ path })}`,
}
