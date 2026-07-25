import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError, editorApi } from './api/editorApi'
import { NodeEditor } from './components/NodeEditor'
import { NodeSidebar } from './components/NodeSidebar'
import { PlacementInspector } from './components/PlacementInspector'
import { defaultFromSchema } from './components/SchemaForm'
import { SettingsDialog } from './components/SettingsDialog'
import { SkillTreeCanvas } from './components/SkillTreeCanvas'
import { ValidationPanel } from './components/ValidationPanel'
import { WorkspaceLayout } from './components/WorkspaceLayout'
import { buildNodeFieldSuggestions, minecraftMaterialSuggestions } from './data/nodeFieldSuggestions'
import { useHistory } from './state/history'
import { applyAuxiliaryLayout } from './state/autoLayout'
import { defaultSchema } from './state/schemaSelection'
import type {
  EditorMetadata,
  JsonObject,
  LoadedSchema,
  NodeMaster,
  PluginSkillTreeSettings,
  SchemaSummary,
  StoredDocument,
  StructureDocument,
  ValidationReport,
} from './types/editor'

const emptyStructure = (): StructureDocument => ({
  $schema: '../schemas/structure.v1.schema.json',
  schemaVersion: 1,
  structureId: '',
  name: '',
  rootNodeId: '',
  nodes: [],
  edges: [],
})

interface EditorTarget {
  node: JsonObject | NodeMaster
  isNew: boolean
}

type WorkspacePane = 'nodes' | 'canvas' | 'details'

export default function App() {
  const [nodeDocuments, setNodeDocuments] = useState<StoredDocument<NodeMaster>[]>([])
  const [structureDocuments, setStructureDocuments] = useState<StoredDocument<StructureDocument>[]>([])
  const [schemas, setSchemas] = useState<SchemaSummary[]>([])
  const [nodeSchemas, setNodeSchemas] = useState<LoadedSchema[]>([])
  const [settings, setSettings] = useState<PluginSkillTreeSettings>({
    worldName: 'world', structureId: '', centerX: 0, centerY: 0, centerZ: 0,
  })
  const [metadata, setMetadata] = useState<EditorMetadata | null>(null)
  const [selectedStructureId, setSelectedStructureId] = useState('')
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [selectedTag, setSelectedTag] = useState('')
  const [nodeEditor, setNodeEditor] = useState<EditorTarget | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [report, setReport] = useState<ValidationReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [validating, setValidating] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [iconRevision, setIconRevision] = useState(0)
  const [visiblePanes, setVisiblePanes] = useState<Record<WorkspacePane, boolean>>({
    nodes: true,
    canvas: true,
    details: true,
  })
  const [savedSnapshot, setSavedSnapshot] = useState(JSON.stringify(emptyStructure()))
  const history = useHistory(emptyStructure())

  const nodes = useMemo(
    () => nodeDocuments.map((document) => document.content).sort((a, b) => a.nodeId.localeCompare(b.nodeId, undefined, { numeric: true })),
    [nodeDocuments],
  )
  const structures = useMemo(() => structureDocuments.map((document) => document.content), [structureDocuments])
  const currentStructure = history.present
  const dirty = Boolean(selectedStructureId) && JSON.stringify(currentStructure) !== savedSnapshot
  const placedIds = useMemo(() => new Set(currentStructure.nodes.map((node) => node.nodeId)), [currentStructure.nodes])
  const selectedMaster = nodes.find((node) => node.nodeId === selectedNodeId) ?? null
  const tagSuggestions = useMemo(
    () => [...new Set(nodes.flatMap((node) => node.tags))].sort((a, b) => a.localeCompare(b, 'ja')),
    [nodes],
  )
  const nodeFieldSuggestions = useMemo(() => buildNodeFieldSuggestions(tagSuggestions), [tagSuggestions])

  const showError = useCallback((reason: unknown) => {
    if (reason instanceof ApiError) {
      const payload = reason.payload as Partial<ValidationReport> | undefined
      if (payload?.issues) setReport(payload as ValidationReport)
      setError(reason.message)
    } else {
      setError(reason instanceof Error ? reason.message : String(reason))
    }
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [loadedNodes, loadedStructures, loadedSchemas, loadedSettings, loadedMetadata] = await Promise.all([
        editorApi.listNodes(),
        editorApi.listStructures(),
        editorApi.listSchemas(),
        editorApi.getSettings(),
        editorApi.metadata(),
      ])
      setNodeDocuments(loadedNodes)
      setStructureDocuments(loadedStructures)
      setSchemas(loadedSchemas)
      setSettings(loadedSettings)
      setMetadata(loadedMetadata)
      const loadedNodeSchemas = await Promise.all(loadedSchemas
        .filter((schema) => schema.entityKind === 'node')
        .map(async (summary) => ({ summary, content: await editorApi.getSchema(summary.fileName) })))
      setNodeSchemas(loadedNodeSchemas)

      const preferredId = loadedStructures.some((document) => document.content.structureId === loadedSettings.structureId)
        ? loadedSettings.structureId
        : loadedStructures[0]?.content.structureId ?? ''
      const preferred = loadedStructures.find((document) => document.content.structureId === preferredId)?.content
      setSelectedStructureId(preferredId)
      if (preferred) {
        history.reset(preferred)
        setSavedSnapshot(JSON.stringify(preferred))
      }
    } catch (reason) {
      showError(reason)
    } finally {
      setLoading(false)
    }
  }, [history.reset, showError])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirty) return
      event.preventDefault()
    }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [dirty])
  useEffect(() => {
    const keyboard = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target?.matches('input, textarea, select') || nodeEditor || settingsOpen) return
      if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'z') {
        event.preventDefault()
        if (event.shiftKey) history.redo()
        else history.undo()
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'y') {
        event.preventDefault()
        history.redo()
      }
    }
    window.addEventListener('keydown', keyboard)
    return () => window.removeEventListener('keydown', keyboard)
  }, [history, nodeEditor, settingsOpen])

  const selectStructure = (structureId: string) => {
    if (dirty && !window.confirm('未保存の構造変更を破棄しますか？')) return
    const selected = structures.find((structure) => structure.structureId === structureId)
    if (!selected) return
    setSelectedStructureId(structureId)
    history.reset(selected)
    setSavedSnapshot(JSON.stringify(selected))
    setSelectedNodeId(null)
    setReport(null)
  }

  const createStructure = async () => {
    if (!nodes.length) {
      setError('構造を作る前にノードマスターを1件以上作成してください。')
      return
    }
    const structureId = window.prompt('構造ID（小文字英数字・_-）を入力してください。')?.trim()
    if (!structureId) return
    if (!/^[a-z0-9][a-z0-9_-]*$/.test(structureId)) {
      setError('構造IDは小文字英数字で始め、小文字英数字・_・-だけを使用してください。')
      return
    }
    const name = window.prompt('構造名を入力してください。', structureId)?.trim()
    if (!name) return
    const rootNodeId = window.prompt('ROOTにするnodeIdを入力してください。', nodes[0].nodeId)?.trim()
    if (!rootNodeId || !nodes.some((node) => node.nodeId === rootNodeId)) {
      setError('存在するnodeIdをROOTに指定してください。')
      return
    }
    const structureSchema = schemas.find((schema) => schema.entityKind === 'structure' && schema.isDefault)
      ?? schemas.find((schema) => schema.entityKind === 'structure')
    const draft: StructureDocument = {
      $schema: `../schemas/${structureSchema?.fileName ?? 'structure.v1.schema.json'}`,
      schemaVersion: structureSchema?.version ?? 1,
      structureId,
      name,
      rootNodeId,
      nodes: [{ nodeId: rootNodeId, x: 0, y: 0, z: 0 }],
      edges: [],
    }
    setSaving(true)
    try {
      const created = await editorApi.createStructure(draft)
      setStructureDocuments((current) => [...current, { fileName: `${structureId}.json`, content: created }])
      setSelectedStructureId(structureId)
      history.reset(created)
      setSavedSnapshot(JSON.stringify(created))
      setNotice(`構造 '${structureId}' を作成しました。`)
    } catch (reason) {
      showError(reason)
    } finally {
      setSaving(false)
    }
  }

  const saveStructure = async () => {
    if (!selectedStructureId) return
    setSaving(true)
    setError('')
    try {
      const validation = await editorApi.validateStructure(currentStructure, selectedStructureId)
      setReport(validation)
      if (!validation.isValid) {
        setError('検証エラーがあるため保存しませんでした。')
        return
      }
      const saved = await editorApi.saveStructure(currentStructure)
      setStructureDocuments((current) => current.map((document) => document.content.structureId === saved.structureId
        ? { ...document, content: saved }
        : document))
      history.reset(saved)
      setSavedSnapshot(JSON.stringify(saved))
      setNotice(`構造 '${saved.structureId}' を保存しました。`)
    } catch (reason) {
      showError(reason)
    } finally {
      setSaving(false)
    }
  }

  const autoLayout = () => {
    if (!selectedStructureId || currentStructure.nodes.length === 0) return
    if (!window.confirm('現在のX/Z座標を補助自動配置で置き換えますか？（Undoで戻せます）')) return
    history.record(applyAuxiliaryLayout(currentStructure))
    setNotice('補助自動配置を適用しました。最終座標は構造JSONへ明示保存されます。')
  }

  const validateCurrent = async () => {
    setValidating(true)
    setError('')
    try {
      setReport(await editorApi.validateStructure(currentStructure, selectedStructureId ?? undefined))
    } catch (reason) {
      showError(reason)
    } finally {
      setValidating(false)
    }
  }

  const validateAll = async () => {
    setValidating(true)
    setError('')
    try {
      setReport(await editorApi.validateAll())
    } catch (reason) {
      showError(reason)
    } finally {
      setValidating(false)
    }
  }

  const openNewNode = () => {
    const selectedSchema = defaultSchema(nodeSchemas)
    const schemaDefault = selectedSchema ? defaultFromSchema(selectedSchema.content) : null
    const template: JsonObject = schemaDefault && typeof schemaDefault === 'object' && !Array.isArray(schemaDefault)
      ? schemaDefault
      : {
          $schema: '../schemas/node.v1.schema.json', schemaVersion: 1, nodeId: '', name: '', icon: '', lore: [], tags: [],
          pointType: 'CP', pointCost: 1, effects: [],
        }
    if (selectedSchema) template.$schema = `../schemas/${selectedSchema.summary.fileName}`
    template.nodeId = ''
    setNodeEditor({ node: template, isNew: true })
  }

  const saveNode = async (draft: JsonObject) => {
    if (!nodeEditor) return
    setSaving(true)
    setError('')
    try {
      const saved = nodeEditor.isNew
        ? await editorApi.createNode(draft)
        : await editorApi.saveNode(draft as NodeMaster)
      setNodeDocuments((current) => nodeEditor.isNew
        ? [...current, { fileName: `${saved.nodeId}.json`, content: saved }]
        : current.map((document) => document.content.nodeId === saved.nodeId ? { ...document, content: saved } : document))
      setNodeEditor(null)
      setNotice(`ノード #${saved.nodeId} を保存しました。`)
    } catch (reason) {
      showError(reason)
    } finally {
      setSaving(false)
    }
  }

  const deleteNode = async () => {
    if (!nodeEditor || nodeEditor.isNew) return
    const node = nodeEditor.node as NodeMaster
    if (!window.confirm(`ノード #${node.nodeId} '${node.name}' を削除しますか？`)) return
    setSaving(true)
    try {
      await editorApi.deleteNode(node.nodeId)
      setNodeDocuments((current) => current.filter((document) => document.content.nodeId !== node.nodeId))
      setNodeEditor(null)
      setNotice(`ノード #${node.nodeId} を削除しました。`)
    } catch (reason) {
      showError(reason)
    } finally {
      setSaving(false)
    }
  }

  const saveSelectedMaster = async (draft: NodeMaster): Promise<NodeMaster | null> => {
    setSaving(true)
    setError('')
    try {
      const saved = await editorApi.saveNode(draft)
      setNodeDocuments((current) => current.map((document) => document.content.nodeId === saved.nodeId
        ? { ...document, content: saved }
        : document))
      setNotice(`ノード #${saved.nodeId} のマスター定義を保存しました。`)
      return saved
    } catch (reason) {
      showError(reason)
      return null
    } finally {
      setSaving(false)
    }
  }

  const retryIcons = () => {
    setIconRevision((current) => current + 1)
    setNotice('Minecraftアイコンを再読み込みします。取得できない場合は外部サービスへの接続を確認してください。')
  }

  const savePluginSettings = async (draft: PluginSkillTreeSettings) => {
    setSaving(true)
    try {
      const saved = await editorApi.saveSettings(draft)
      setSettings(saved)
      setSettingsOpen(false)
      setNotice('Pluginのskilltree設定を保存しました。')
    } catch (reason) {
      showError(reason)
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <main className="loading-screen"><div className="loader" /><h1>Skill Tree Editor</h1><p>ワークスペースを読み込んでいます…</p></main>
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">AR</span>
          <div><span className="eyebrow">ASTRALRECORD DEV TOOL</span><h1>Skill Tree Editor</h1></div>
        </div>
        <div className="structure-control">
          <label>構造
            <select value={selectedStructureId} onChange={(event) => selectStructure(event.target.value)}>
              {!selectedStructureId && <option value="">構造がありません</option>}
              {structures.map((structure) => <option key={structure.structureId} value={structure.structureId}>{structure.name} · {structure.structureId}</option>)}
            </select>
          </label>
          <button className="button subtle" onClick={() => void createStructure()} disabled={saving}>＋ 構造</button>
        </div>
        <div className="toolbar">
          <button className="icon-button" onClick={history.undo} disabled={!history.canUndo} title="Undo (Ctrl+Z)">↶</button>
          <button className="icon-button" onClick={history.redo} disabled={!history.canRedo} title="Redo (Ctrl+Y)">↷</button>
          <button className="button subtle" onClick={autoLayout} disabled={!selectedStructureId || currentStructure.nodes.length === 0}>補助自動配置</button>
          <span className={dirty ? 'save-state dirty' : 'save-state'}>{dirty ? '未保存' : '保存済み'}</span>
          <button className="button subtle" onClick={retryIcons} title="取得に失敗したMinecraftアイコンを再読み込み">アイコン再読込</button>
          <div className="pane-toggles" role="group" aria-label="表示パネル">
            {([
              ['nodes', '一覧'],
              ['canvas', 'キャンバス'],
              ['details', '詳細'],
            ] as const).map(([pane, label]) => (
              <button
                className={`button compact pane-toggle ${visiblePanes[pane] ? 'active' : ''}`}
                key={pane}
                type="button"
                aria-pressed={visiblePanes[pane]}
                onClick={() => setVisiblePanes((current) => ({ ...current, [pane]: !current[pane] }))}
              >
                {label}
              </button>
            ))}
          </div>
          <button className="button" onClick={() => setSettingsOpen(true)}>表示設定</button>
          <button className="button primary" onClick={() => void saveStructure()} disabled={!dirty || saving || !selectedStructureId}>
            {saving ? '保存中…' : '構造を保存'}
          </button>
        </div>
      </header>

      {(error || notice) && (
        <div className={`toast ${error ? 'error' : 'success'}`}>
          <span>{error || notice}</span>
          <button onClick={() => { setError(''); setNotice('') }}>×</button>
        </div>
      )}

      <WorkspaceLayout
        leftVisible={visiblePanes.nodes}
        centerVisible={visiblePanes.canvas}
        rightVisible={visiblePanes.details}
        left={(
          <NodeSidebar
            nodes={nodes}
            placedIds={placedIds}
            query={query}
            selectedTag={selectedTag}
            onQueryChange={setQuery}
            onTagChange={setSelectedTag}
            onEdit={(node) => setNodeEditor({ node, isNew: false })}
            onCreate={openNewNode}
            iconRevision={iconRevision}
          />
        )}
        center={(
          <section className="canvas-column">
            {selectedStructureId ? (
              <SkillTreeCanvas
                structure={currentStructure}
                masters={nodes}
                onRecord={history.record}
                onReplace={history.replace}
                onBeginTransaction={history.beginTransaction}
                onCommitTransaction={history.commitTransaction}
                onSelectedNode={setSelectedNodeId}
                onEditMaster={(node) => setNodeEditor({ node, isNew: false })}
                onNotify={setNotice}
                iconRevision={iconRevision}
              />
            ) : (
              <div className="canvas-placeholder"><h2>構造を作成してください</h2><p>ノードマスターを用意し、「＋ 構造」から始めます。</p></div>
            )}
          </section>
        )}
        right={(
          <aside className="right-panel">
            <PlacementInspector
              nodeId={selectedNodeId}
              structure={currentStructure}
              master={selectedMaster}
              saving={saving}
              iconRevision={iconRevision}
              materialSuggestions={minecraftMaterialSuggestions}
              tagSuggestions={tagSuggestions}
              onChange={history.record}
              onSaveMaster={saveSelectedMaster}
              onEditMaster={(node) => setNodeEditor({ node, isNew: false })}
              onRetryIcons={retryIcons}
            />
            <ValidationPanel
              report={report}
              validating={validating}
              onValidate={() => void validateCurrent()}
              onValidateAll={() => void validateAll()}
            />
            {metadata && (
              <details className="paths panel-section">
                <summary>読込先</summary>
                <dl>
                  <dt>Workspace</dt><dd>{metadata.workspaceRoot}</dd>
                  <dt>Nodes</dt><dd>{metadata.nodesPath}</dd>
                  <dt>Structures</dt><dd>{metadata.structuresPath}</dd>
                  <dt>ID Sequence</dt><dd>{metadata.nodeIdSequencePath}</dd>
                  <dt>Backups</dt><dd>{metadata.backupPath}</dd>
                  <dt>Icon Cache</dt><dd>{metadata.minecraftIconCachePath}</dd>
                </dl>
              </details>
            )}
          </aside>
        )}
      />

      {nodeEditor && (
        <NodeEditor
          node={nodeEditor.node}
          schemas={nodeSchemas}
          isNew={nodeEditor.isNew}
          saving={saving}
          onSave={saveNode}
          onDelete={nodeEditor.isNew ? undefined : deleteNode}
          onCancel={() => setNodeEditor(null)}
          suggestionsByPath={nodeFieldSuggestions}
        />
      )}
      {settingsOpen && (
        <SettingsDialog
          settings={settings}
          structures={structures}
          saving={saving}
          onSave={savePluginSettings}
          onCancel={() => setSettingsOpen(false)}
        />
      )}
    </main>
  )
}
