import type { JsonValue } from '../types/editor'
import type { MasterDocument } from '../types/masterData'

export interface MasterDraftState { raw: string; content: JsonValue; source: 'raw' | 'form' }
export interface RecoverableMasterDraft {
  version: 1
  document: MasterDocument
  draft: MasterDraftState
  formPath: string
  invalidValues: Record<string, string>
  requiresDiffReview?: boolean
  updatedAt: string
}

const prefix = (root: string) => `astralrecord.master-draft.v1:${encodeURIComponent(root)}:`
const key = (root: string, path: string) => prefix(root) + encodeURIComponent(path)

function validDraft(value: unknown, path: string): value is RecoverableMasterDraft {
  if (!value || typeof value !== 'object') return false
  const entry = value as Partial<RecoverableMasterDraft>
  return entry.version === 1 && entry.document?.path === path
    && typeof entry.document.raw === 'string' && typeof entry.document.revision === 'string'
    && entry.document.content !== undefined && typeof entry.draft?.raw === 'string'
    && entry.draft.content !== undefined && ['raw', 'form'].includes(entry.draft.source)
    && typeof entry.formPath === 'string' && typeof entry.updatedAt === 'string'
    && (entry.requiresDiffReview === undefined || typeof entry.requiresDiffReview === 'boolean')
    && Boolean(entry.invalidValues && typeof entry.invalidValues === 'object' && !Array.isArray(entry.invalidValues)
      && Object.values(entry.invalidValues).every((text) => typeof text === 'string'))
}

/** Per-browser-tab drafts, isolated by workspace. These never write to Filebase. */
export function readMasterDraft(root: string, path: string): RecoverableMasterDraft | null {
  try {
    const raw = window.sessionStorage.getItem(key(root, path))
    if (!raw) return null
    const value: unknown = JSON.parse(raw)
    return validDraft(value, path) ? value : null
  } catch { return null }
}

export function listMasterDrafts(root: string): RecoverableMasterDraft[] {
  try {
    const entries: RecoverableMasterDraft[] = []
    const start = prefix(root)
    for (let index = 0; index < window.sessionStorage.length; index++) {
      const name = window.sessionStorage.key(index)
      if (!name?.startsWith(start)) continue
      const entry = readMasterDraft(root, decodeURIComponent(name.slice(start.length)))
      if (entry) entries.push(entry)
    }
    return entries.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
  } catch { return [] }
}

export function writeMasterDraft(root: string, entry: RecoverableMasterDraft): boolean {
  try { window.sessionStorage.setItem(key(root, entry.document.path), JSON.stringify(entry)); return true }
  catch { return false }
}

export function removeMasterDraft(root: string, path: string): boolean {
  try { window.sessionStorage.removeItem(key(root, path)); return true }
  catch { return false }
}
