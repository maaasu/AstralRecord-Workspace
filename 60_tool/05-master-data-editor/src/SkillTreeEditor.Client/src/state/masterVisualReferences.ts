import { createContext } from 'react'
import type { JsonValue } from '../types/editor'
import type { MasterFileSummary } from '../types/masterData'

export interface MasterVisualReferences {
  documentPath: string
  rootContent?: JsonValue
  files: ReadonlyMap<string, MasterFileSummary>
  referencePrefixes?: Readonly<Record<string, string>> | null
  openFile: (path: string) => void
  selectCategorizedItem?: (path: string, reference: string, file: MasterFileSummary) => void
  customModelData?: JsonValue
  iconTexture?: JsonValue
  iconModel?: JsonValue
}

export const MasterVisualReferencesContext = createContext<MasterVisualReferences | null>(null)
