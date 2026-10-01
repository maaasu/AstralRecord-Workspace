import { ApiError } from './editorApi'
import type { JsonValue, ValidationIssue, ValidationReport } from '../types/editor'
import type { MasterCatalog, MasterDocument, MasterDraft, MasterFileSummary, MasterReference } from '../types/masterData'

const endpoint = '/api/master-data'

async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const init: RequestInit = {
    method,
    headers: { 'Content-Type': 'application/json' },
  }
  if (body !== undefined) init.body = JSON.stringify(body)
  const response = await fetch(`${endpoint}${path}`, init)
  const text = response.status === 204 ? '' : await response.text()
  let payload: unknown = text
  if (response.headers.get('content-type')?.includes('json') && text) {
    try { payload = JSON.parse(text) } catch { /* Preserve the server response in the error. */ }
  }
  if (!response.ok) {
    const details = payload && typeof payload === 'object' ? payload as { message?: string; title?: string } : undefined
    throw new ApiError(details?.message ?? details?.title ?? `操作に失敗しました (${response.status})`, response.status, payload)
  }
  return payload as T
}

export const masterDataApi = {
  catalog: () => request<MasterCatalog>('/catalog'),
  files: (category = '', query = '') => request<MasterFileSummary[]>(`/files?${new URLSearchParams({ category, query })}`),
  file: (path: string) => request<MasterDocument>(`/file?${new URLSearchParams({ path })}`),
  save: (path: string, raw: string, revision: string) => request<MasterDocument>('/file', 'PUT', { path, raw, revision }),
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
  documentation: (path: string) => request<{ path: string; raw: string }>(`/documentation?${new URLSearchParams({ path })}`),
  exportUrl: (path: string) => `${endpoint}/export?${new URLSearchParams({ path })}`,
}
