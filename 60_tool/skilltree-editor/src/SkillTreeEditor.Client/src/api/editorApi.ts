import type {
  ClassMasterSummary,
  EditorMetadata,
  JsonObject,
  NodeMaster,
  PluginSkillTreeSettings,
  SchemaSummary,
  SkillMasterSummary,
  StoredDocument,
  StructureDocument,
  ValidationReport,
  VersionedDocument,
} from '../types/editor'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly payload?: unknown,
  ) {
    super(message)
  }
}

async function request<T>(url: string, init?: RequestInit, onResponse?: (response: Response) => void): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        ...init?.headers,
      },
    })
  } catch {
    throw new ApiError('サーバーに接続できませんでした。接続を確認して、もう一度実行してください。', 0)
  }
  const contentType = response.headers.get('content-type') ?? ''
  const payload = response.status === 204
    ? undefined
    : contentType.includes('application/json')
      ? await response.json()
      : await response.text()

  if (!response.ok) {
    const message = typeof payload === 'object' && payload && 'message' in payload
      ? String((payload as { message: unknown }).message)
      : typeof payload === 'object' && payload && 'title' in payload
        ? String((payload as { title: unknown }).title)
        : `処理に失敗しました（${response.status}）`
    throw new ApiError(message, response.status, payload)
  }
  onResponse?.(response)
  return payload as T
}

async function versionedRequest<T>(url: string, init?: RequestInit): Promise<VersionedDocument<T>> {
  let revision = ''
  const content = await request<T>(url, init, (response) => {
    revision = response.headers.get('etag')?.replace(/^"|"$/g, '') ?? ''
  })
  if (!revision) throw new ApiError('読込時の版を取得できませんでした。再読み込みしてください。', 409)
  return { content, revision }
}

const matchRevision = (init: RequestInit, revision: string): RequestInit => ({
  ...init,
  headers: { ...init.headers, 'If-Match': `"${revision}"` },
})

const json = (method: string, body: JsonObject | PluginSkillTreeSettings): RequestInit => ({
  method,
  body: JSON.stringify(body),
})

export const editorApi = {
  metadata: () => request<EditorMetadata>('/api/metadata'),
  listClasses: () => request<ClassMasterSummary[]>('/api/classes'),
  listSkills: () => request<SkillMasterSummary[]>('/api/skills'),
  listNodes: () => request<StoredDocument<NodeMaster>[]>('/api/nodes'),
  createNode: (node: JsonObject) => versionedRequest<NodeMaster>('/api/nodes', json('POST', node)),
  saveNode: (node: NodeMaster, revision: string) => versionedRequest<NodeMaster>(
    `/api/nodes/${encodeURIComponent(node.nodeId)}`,
    matchRevision(json('PUT', node), revision),
  ),
  deleteNode: (nodeId: string, revision: string) => request<void>(
    `/api/nodes/${encodeURIComponent(nodeId)}`,
    matchRevision({ method: 'DELETE' }, revision),
  ),
  listStructures: () => request<StoredDocument<StructureDocument>[]>('/api/structures'),
  createStructure: (structure: StructureDocument) => versionedRequest<StructureDocument>(
    '/api/structures',
    json('POST', structure),
  ),
  saveStructure: (structure: StructureDocument, revision: string) => versionedRequest<StructureDocument>(
    `/api/structures/${encodeURIComponent(structure.structureId)}`,
    matchRevision(json('PUT', structure), revision),
  ),
  listSchemas: () => request<SchemaSummary[]>('/api/schemas'),
  getSchema: (fileName: string) => request<JsonObject>(`/api/schemas/${encodeURIComponent(fileName)}`),
  validateAll: () => request<ValidationReport>('/api/validation'),
  validateStructure: (structure: StructureDocument, existingStructureId?: string) => request<ValidationReport>(
    `/api/validation/structure${existingStructureId ? `?existingStructureId=${encodeURIComponent(existingStructureId)}` : ''}`,
    json('POST', structure),
  ),
  getSettings: () => versionedRequest<PluginSkillTreeSettings>('/api/settings'),
  saveSettings: (settings: PluginSkillTreeSettings, revision: string) => versionedRequest<PluginSkillTreeSettings>(
    '/api/settings',
    matchRevision(json('PUT', settings), revision),
  ),
}
