import type { JsonObject, JsonValue, ValidationIssue } from './editor'

export interface MasterField {
  path: string
  key: string
  label: string
  type: string
  required: boolean
  description: string
  default?: string | null
  enum?: JsonValue[]
  reference?: string
}

export interface MasterCategory {
  id: string
  label: string
  directory: string
  fileCount: number
  documents: { path: string; title: string }[]
  fields: MasterField[]
  jsonSchemas: { path: string; title: string; schema: JsonObject }[]
  templates: { path: string; raw: string }[]
  itemCategoryCode?: string | null
}

export interface MasterCatalog {
  root: string
  backups: string
  categories: MasterCategory[]
}

export interface MasterFileSummary {
  path: string
  category: string
  format: string
  id?: string | null
  name?: string | null
  size: number
  modifiedUtc: string
  revision: string
  parseError?: string | null
  readOnly?: boolean
  icon?: string | null
}

export interface MasterDocument {
  path: string
  format: string
  raw: string
  revision: string
  content: JsonValue
  issues: ValidationIssue[]
}

export interface MasterReference {
  path: string
  pointer: string
  value: string
  label?: string
  kind?: string
}

export interface MasterDraft {
  path: string
  raw: string
  autoItemId?: boolean
  slug?: string
  group?: string
  autoName?: boolean
}
