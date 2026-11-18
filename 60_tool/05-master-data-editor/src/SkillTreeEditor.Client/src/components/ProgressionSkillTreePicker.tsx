import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { editorApi } from '../api/editorApi'
import { isAndroidEditor } from '../androidEditor'
import { usePanelDocument } from '../docking/dockPanel'
import { isNodeVisibleInSimulation } from '../state/nodeVisibility'
import type { ClassMasterSummary, JsonValue, NodeMaster, SkillMasterSummary, StructureDocument } from '../types/editor'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { Modal } from './Modal'
import { stripMinecraftFormatting } from '../utils/minecraft'
import './progressionSkillTreePicker.css'

export interface ProgressionTreeContext {
  classId: string
  playerLevel: number
  classLevel?: number
}

interface Props {
  context: ProgressionTreeContext
  values: JsonValue[]
  disabled?: boolean
  onApply: (values: JsonValue[]) => void
  onClose: () => void
}

interface TreeData {
  nodes: NodeMaster[]
  structure: StructureDocument
  classes: ClassMasterSummary[]
  skills: SkillMasterSummary[]
}

const noChange = () => false
const ignoreChange = () => {}
const positiveNodeId = (value: JsonValue) => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const SkillTreeCanvas = lazy(() => import('./SkillTreeCanvas').then(module => ({ default: module.SkillTreeCanvas })))

export function ProgressionSkillTreePicker({ context, values, disabled = false, onApply, onClose }: Props) {
  const android = isAndroidEditor()
  const document = usePanelDocument()
  const [selection, setSelection] = useState(values)
  const initialNodeIds = useRef(new Set(values.filter(positiveNodeId).map(String)))
  const [data, setData] = useState<TreeData | null>(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [message, setMessage] = useState('')
  const [focusedNodeId, setFocusedNodeId] = useState<string | null>(null)
  const [showSelected, setShowSelected] = useState(false)
  const selectedToggle = useRef<HTMLButtonElement>(null)
  const [focusRequest, setFocusRequest] = useState<{ nodeId: string } | null>(null)
  useEffect(() => {
    let cancelled = false
    setError('')
    void Promise.all([editorApi.listNodes(), editorApi.listStructures(), editorApi.listClasses(), editorApi.listSkills(), editorApi.getSettings()])
      .then(([nodes, structures, classes, skills, settings]) => {
        if (cancelled) return
        const structure = structures.find(entry => entry.content.structureId === settings.content.structureId)?.content
        if (!structure) throw new Error('表示設定に対応するスキルツリーが見つかりません。スキルツリー画面の表示設定を確認してください。')
        setData({ nodes: nodes.map(entry => entry.content), structure, classes, skills })
      }).catch((reason: unknown) => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason))
      })
    return () => { cancelled = true }
  }, [attempt])
  const masters = useMemo(() => new Map(data?.nodes.map(node => [node.nodeId, node]) ?? []), [data])
  const visibleNodeIds = useMemo(() => new Set(data?.nodes.filter(node => isNodeVisibleInSimulation(node, data.classes, {
    currentClassId: context.classId, playerLevel: context.playerLevel,
  })).map(node => node.nodeId) ?? []), [data, context.classId, context.playerLevel])
  const placedIds = useMemo(() => new Set(data?.structure.nodes.map(node => node.nodeId) ?? []), [data])
  const selectedIds = useMemo(() => new Set(selection.filter(positiveNodeId).map(String)), [selection])
  useEffect(() => {
    if (!data) return
    const candidates = data.nodes.filter(node => visibleNodeIds.has(node.nodeId) && placedIds.has(node.nodeId))
    const classes = new Map(data.classes.map(entry => [entry.id.toLowerCase(), entry]))
    const pending = [context.classId.toLowerCase()]
    const seen = new Set<string>()
    let target: NodeMaster | undefined
    while (pending.length && !target) {
      const classId = pending.shift()!
      if (seen.has(classId)) continue
      seen.add(classId)
      const branch = candidates.filter(node => typeof node.unlockCondition?.classId === 'string' && node.unlockCondition.classId.toLowerCase() === classId)
      target = branch.find(node => initialNodeIds.current.has(node.nodeId)) ?? branch[0]
      pending.push(...(classes.get(classId)?.parentClassIds.map(id => id.toLowerCase()) ?? []))
    }
    target ??= candidates.find(node => initialNodeIds.current.has(node.nodeId))
      ?? candidates.find(node => node.nodeId === data.structure.rootNodeId) ?? candidates[0]
    if (target) { setFocusedNodeId(target.nodeId); setFocusRequest({ nodeId: target.nodeId }) }
  }, [data, visibleNodeIds, placedIds, context.classId])
  const eligible = Boolean(data?.classes.some(entry => entry.id.toLowerCase() === context.classId.toLowerCase()))
  const toggle = useCallback((nodeId: string) => {
    if (disabled || !eligible || !visibleNodeIds.has(nodeId)) return
    const id = Number(nodeId)
    if (!Number.isSafeInteger(id) || id < 1 || id > 2147483647) return
    setFocusedNodeId(nodeId)
    setSelection(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id])
  }, [disabled, eligible, visibleNodeIds])
  const learnedSelection = useMemo(() => ({ nodeIds: selectedIds, disabled: disabled || !eligible, onToggle: toggle }),
    [selectedIds, disabled, eligible, toggle])
  const className = data?.classes.find(entry => entry.id.toLowerCase() === context.classId.toLowerCase())?.name ?? context.classId
  const selectedMaster = focusedNodeId ? masters.get(focusedNodeId) : undefined
  const removeSelection = (index: number) => setSelection(current => current.filter((_, entryIndex) => entryIndex !== index))
  const goBack = () => {
    const toggle = selectedToggle.current
    // In the wide layout both panes are visible, even after rotating from the list.
    if (showSelected && toggle && document.defaultView?.getComputedStyle(toggle).display !== 'none') {
      setShowSelected(false)
      toggle.focus()
    } else onClose()
  }
  return createPortal(<Modal className="progression-tree-dialog" ariaLabel="スキルツリーから習得ノードを選択"
    onClose={onClose} onAndroidBack={goBack} closeDisabled={disabled}>
    <header className="modal-header"><div><h2>スキルツリーから習得ノードを選択</h2><p><MinecraftText value={className} /> · プレイヤーLv.{context.playerLevel}{context.classLevel !== undefined && ` · 職業Lv.${context.classLevel}`}</p></div><button type="button" className="button" disabled={disabled} onClick={onClose}>キャンセル</button></header>
    {error ? <div className="progression-tree-loading"><p role="alert">{error}</p><button type="button" className="button" onClick={() => setAttempt(current => current + 1)}>再試行</button></div>
      : !data ? <div className="progression-tree-loading" role="status">スキルツリーを読み込んでいます…</div>
        : <div className="progression-tree-content" data-show-selected={showSelected}>
          <div className="progression-tree-canvas"><Suspense fallback={<div className="progression-tree-loading" role="status">スキルツリーを準備しています…</div>}><SkillTreeCanvas key={data.structure.structureId} structure={data.structure} masters={data.nodes} classMasters={data.classes} skillMasters={data.skills}
            visibleNodeIds={visibleNodeIds} selectedNodeId={focusedNodeId} nodeSize={72} learnedSelection={learnedSelection} androidGestures={android}
            onRecord={noChange} onReplace={ignoreChange} onBeginTransaction={ignoreChange} onCommitTransaction={ignoreChange}
            onEditMaster={ignoreChange} onSelectedNode={setFocusedNodeId} onNotify={setMessage} focusRequest={focusRequest} onFocusHandled={() => setFocusRequest(null)} /></Suspense></div>
          <aside className="progression-tree-selection">
            {!eligible && <p role="alert">職業の定義が見つかりません。プリセットの職業を確認してください。</p>}
            {!android && <p className="master-muted">チェック付きが習得ノードです。選択を確認して「選択を反映」を押してください。</p>}
            {message && <p role="status">{message}</p>}
            {selectedMaster && <div className="progression-tree-current"><MinecraftIcon icon={selectedMaster.icon} /><MinecraftText value={selectedMaster.name} /><small>{selectedIds.has(selectedMaster.nodeId) ? '習得' : '未習得'} · {selectedMaster.pointType} {selectedMaster.pointCost}ポイント</small></div>}
            <h3>習得ノード {selection.length}件</h3>
            <div className="progression-tree-selected-list">{selection.map((value, index) => {
              const id = positiveNodeId(value) ? String(value) : ''
              const node = masters.get(id)
              const hidden = Boolean(node && !visibleNodeIds.has(id))
              const missingPlacement = Boolean(node && !placedIds.has(id))
              const label = node?.name ?? `未登録のノード（${String(value)}）`
              return <div className="progression-tree-selected-node" key={index}>
                {node && <MinecraftIcon icon={node.icon} />}
                <div>{node && !hidden && !missingPlacement ? <button type="button" className="text-button" onClick={() => { setFocusedNodeId(id); setFocusRequest({ nodeId: id }); setShowSelected(false) }}><MinecraftText value={node.name} /></button> : <MinecraftText value={label} />}
                  {hidden && <small>この職業・レベルの表示範囲外</small>}{missingPlacement && <small>ツリーの配置外</small>}</div>
                <button type="button" className="text-button" disabled={disabled} aria-label={`${stripMinecraftFormatting(label)}を習得から外す`} onClick={() => removeSelection(index)}>外す</button>
              </div>
            })}</div>
          </aside>
        </div>}
    <footer className="modal-footer">
      <span className="progression-tree-footer-count">習得 {selection.length}件</span>
      {android && <button ref={selectedToggle} type="button" className="button progression-tree-selected-toggle" aria-pressed={showSelected} disabled={!data}
        onClick={() => setShowSelected(current => !current)}>{showSelected ? 'ツリーに戻る' : `習得 ${selection.length}件を確認`}</button>}
      <button type="button" className="button primary" disabled={disabled || !data || Boolean(error)} onClick={() => onApply(selection)}>選択を反映</button>
    </footer>
  </Modal>, document.body)
}
