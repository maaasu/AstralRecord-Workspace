import type { ValidationIssue } from './editor'

export type AiMode = 'create' | 'complete' | 'batch'
export interface AiSettings { configured: boolean; model: string; keySource: string; reason: string | null }
export interface AiProposal {
  path: string
  sourcePath: string | null
  raw: string
  beforeRaw: string | null
  revision: string | null
  summary: string
  issues: ValidationIssue[]
  isNew: boolean
}
export interface AiProposalRequest {
  mode: AiMode
  instruction: string
  categoryId?: string
  sourcePath?: string
  raw?: string
  fieldPointer?: string
  completionStyle?: 'continue'
  paths?: string[]
}
export interface AiProposalResponse { mode: string; model: string; suggestions: string[]; proposals: AiProposal[]; warnings: string[] }
export interface AiEditorSnapshot { path: string; revision: string; token: string; dirty: boolean; blocked: boolean }
export interface AiPaneActions {
  snapshot: () => AiEditorSnapshot | null
  raw: () => Promise<string>
  apply: (raw: string, expected: AiEditorSnapshot) => Promise<void>
  create: (raw: string, categoryId: string) => void
}
export type AiBatchGuard = Record<string, AiEditorSnapshot | null>
