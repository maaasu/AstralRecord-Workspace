import { useEffect, useMemo, useState } from 'react'
import { defaultFromSchema, SchemaForm } from './SchemaForm'
import { schemaForDocument } from '../state/schemaSelection'
import type { FieldSuggestionValue, JsonObject, JsonValue, LoadedSchema, NodeMaster } from '../types/editor'
import { MINECRAFT_MATERIAL_VERSION } from '../data/nodeFieldSuggestions'

interface NodeEditorProps {
  node: JsonObject | NodeMaster
  schemas: LoadedSchema[]
  isNew: boolean
  saving: boolean
  onSave: (node: JsonObject) => Promise<void>
  onDelete?: () => Promise<void>
  onCancel: () => void
  suggestionsByPath?: Readonly<Record<string, readonly FieldSuggestionValue[]>>
}

export function NodeEditor({ node, schemas, isNew, saving, onSave, onDelete, onCancel, suggestionsByPath = {} }: NodeEditorProps) {
  const [draft, setDraft] = useState<JsonObject>(() => structuredClone(node))
  const [raw, setRaw] = useState(() => JSON.stringify(node, null, 2))
  const [mode, setMode] = useState<'form' | 'raw'>('form')
  const [parseError, setParseError] = useState('')

  useEffect(() => {
    setDraft(structuredClone(node))
    setRaw(JSON.stringify(node, null, 2))
    setParseError('')
  }, [node])

  const title = isNew ? 'ノードを新規作成' : `${String(draft.name || '名称未設定')} #${String(draft.nodeId)}`
  const disabledPaths = useMemo(() => new Set(['/nodeId']), [])
  const selectedSchema = schemaForDocument(draft, schemas)

  const updateDraft = (value: JsonValue) => {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return
    const object = value as JsonObject
    setDraft(object)
    setRaw(JSON.stringify(object, null, 2))
    setParseError('')
  }

  const updateRaw = (value: string) => {
    setRaw(value)
    try {
      const parsed = JSON.parse(value) as unknown
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('JSONルートはオブジェクトである必要があります。')
      }
      setDraft(parsed as JsonObject)
      setParseError('')
    } catch (error) {
      setParseError(error instanceof Error ? error.message : String(error))
    }
  }

  const selectSchema = (fileName: string) => {
    const selected = schemas.find((schema) => schema.summary.fileName === fileName)
    if (!selected) return
    const generated = defaultFromSchema(selected.content)
    if (generated === null || typeof generated !== 'object' || Array.isArray(generated)) return
    updateDraft({
      ...generated,
      $schema: `../schemas/${selected.summary.fileName}`,
      nodeId: '',
    })
  }

  return (
    <div className="modal-backdrop" role="presentation">
      <section className="modal node-editor" role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-header">
          <div>
            <span className="eyebrow">NODE MASTER</span>
            <h2>{title}</h2>
            <p className="muted">nodeId: {isNew ? '保存時に自動採番（1000以上）' : String(draft.nodeId)}</p>
            <p className="muted">iconは入力中にPaper {MINECRAFT_MATERIAL_VERSION}のMaterial候補を表示します。</p>
          </div>
          <button className="icon-button" onClick={onCancel} aria-label="閉じる">×</button>
        </header>
        <div className="tab-row">
          <button className={mode === 'form' ? 'tab active' : 'tab'} onClick={() => setMode('form')}>スキーマフォーム</button>
          <button className={mode === 'raw' ? 'tab active' : 'tab'} onClick={() => setMode('raw')}>Raw JSON</button>
        </div>
        <div className="modal-body">
          <label className="schema-field schema-selector">
            <span>JSON Schema<small>既存ノードは文書内の $schema から自動選択します。</small></span>
            <select
              value={selectedSchema?.summary.fileName ?? ''}
              disabled={!isNew || schemas.length === 0}
              onChange={(event) => selectSchema(event.target.value)}
            >
              {!selectedSchema && <option value="">参照先Schemaが見つかりません</option>}
              {schemas.map((schema) => (
                <option key={schema.summary.fileName} value={schema.summary.fileName}>
                  {schema.summary.title ?? schema.summary.fileName}{schema.summary.isDefault ? '（既定）' : ''}
                </option>
              ))}
            </select>
          </label>
          {mode === 'form' && selectedSchema && (
            <SchemaForm
              schema={selectedSchema.content}
              value={draft}
              onChange={updateDraft}
              disabledPaths={disabledPaths}
              suggestionsByPath={suggestionsByPath}
            />
          )}
          {mode === 'form' && !selectedSchema && (
            <p className="error-message">文書の $schema に対応するnode用JSON Schemaがありません。Raw JSONで参照値を修正してください。</p>
          )}
          {mode === 'raw' && (
            <>
              <textarea
                className="raw-editor"
                value={raw}
                onChange={(event) => updateRaw(event.target.value)}
                spellCheck={false}
                aria-label="ノードJSON"
              />
              {parseError && <p className="error-message">{parseError}</p>}
            </>
          )}
        </div>
        <footer className="modal-footer">
          {!isNew && onDelete && (
            <button className="button danger" onClick={() => void onDelete()} disabled={saving}>削除</button>
          )}
          <span className="spacer" />
          <button className="button subtle" onClick={onCancel} disabled={saving}>キャンセル</button>
          <button className="button primary" onClick={() => void onSave(draft)} disabled={saving || Boolean(parseError)}>
            {saving ? '保存中…' : 'ノードを保存'}
          </button>
        </footer>
      </section>
    </div>
  )
}
