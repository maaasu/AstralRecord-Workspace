import type {
  ClassMasterSummary,
  EditorMetadata,
  JsonObject,
  NodeMaster,
  PluginSkillTreeSettings,
  SchemaSummary,
  StoredDocument,
  StructureDocument,
  ValidationReport,
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

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...init?.headers,
    },
  })
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
        : `Request failed (${response.status})`
    throw new ApiError(message, response.status, payload)
  }
  return payload as T
}

const json = (method: string, body: JsonObject | PluginSkillTreeSettings): RequestInit => ({
  method,
  body: JSON.stringify(body),
})

export const editorApi = {
  metadata: () => request<EditorMetadata>('/api/metadata'),
  listClasses: () => request<ClassMasterSummary[]>('/api/classes'),
  listNodes: () => request<StoredDocument<NodeMaster>[]>('/api/nodes'),
  createNode: (node: JsonObject) => request<NodeMaster>('/api/nodes', json('POST', node)),
  saveNode: (node: NodeMaster) => request<NodeMaster>(
    `/api/nodes/${encodeURIComponent(node.nodeId)}`,
    json('PUT', node),
  ),
  deleteNode: (nodeId: string) => request<void>(
    `/api/nodes/${encodeURIComponent(nodeId)}`,
    { method: 'DELETE' },
  ),
  listStructures: () => request<StoredDocument<StructureDocument>[]>('/api/structures'),
  createStructure: (structure: StructureDocument) => request<StructureDocument>(
    '/api/structures',
    json('POST', structure),
  ),
  saveStructure: (structure: StructureDocument) => request<StructureDocument>(
    `/api/structures/${encodeURIComponent(structure.structureId)}`,
    json('PUT', structure),
  ),
  listSchemas: () => request<SchemaSummary[]>('/api/schemas'),
  getSchema: (fileName: string) => request<JsonObject>(`/api/schemas/${encodeURIComponent(fileName)}`),
  validateAll: () => request<ValidationReport>('/api/validation'),
  validateStructure: (structure: StructureDocument, existingStructureId?: string) => request<ValidationReport>(
    `/api/validation/structure${existingStructureId ? `?existingStructureId=${encodeURIComponent(existingStructureId)}` : ''}`,
    json('POST', structure),
  ),
  getSettings: () => request<PluginSkillTreeSettings>('/api/settings'),
  saveSettings: (settings: PluginSkillTreeSettings) => request<PluginSkillTreeSettings>(
    '/api/settings',
    json('PUT', settings),
  ),
}
