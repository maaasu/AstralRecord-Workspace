export type JsonPrimitive = string | number | boolean | null
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue }
export type JsonObject = { [key: string]: JsonValue }

export interface NodeMaster extends JsonObject {
  $schema: string
  schemaVersion: number
  nodeId: string
  name: string
  icon: JsonValue
  lore: JsonValue[]
  tags: string[]
  pointType: string
  pointCost: number
  effects: JsonValue[]
}

export interface StructurePlacement extends JsonObject {
  nodeId: string
  x: number
  y: number
  z: number
}

export interface StructureEdge extends JsonObject {
  sourceNodeId: string
  targetNodeId: string
}

export interface StructureDocument extends JsonObject {
  $schema: string
  schemaVersion: number
  structureId: string
  name: string
  rootNodeId: string
  nodes: StructurePlacement[]
  edges: StructureEdge[]
}

export interface StoredDocument<T extends JsonObject> {
  fileName: string
  content: T
}

export interface SchemaSummary {
  fileName: string
  id?: string
  title?: string
  entityKind: 'node' | 'structure' | 'generic'
  version?: number | null
  isDefault: boolean
}

export interface LoadedSchema {
  summary: SchemaSummary
  content: JsonObject
}

export interface ValidationIssue {
  code: string
  message: string
  severity: 'error' | 'warning'
  file?: string
  path?: string
}

export interface ValidationReport {
  isValid: boolean
  issues: ValidationIssue[]
}

export interface PluginSkillTreeSettings {
  worldName: string
  structureId: string
  centerX: number
  centerY: number
  centerZ: number
}

export interface ClassMasterSummary {
  id: string
  name: string
  parentClassIds: string[]
}

export interface EditorMetadata {
  workspaceRoot: string
  nodesPath: string
  structuresPath: string
  schemasPath: string
  nodeIdSequencePath: string
  pluginConfigPath: string
  backupPath: string
  minecraftIconCachePath: string
}
