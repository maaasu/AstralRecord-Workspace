import { useEffect, useMemo, useRef, useState } from 'react'
import type { ClassMasterSummary, JsonValue, NodeMaster, SkillMasterSummary, StructureDocument } from '../types/editor'
import { MinecraftText } from './MinecraftText'
import { MapEffectList, MapNodeIcon, MapNodeName, NODE_KIND_LABEL, connectedNodeIds, mapNodeCost, mapNodeEffects, nodeClassId, nodeClassName, nodeKind, nodeLevel, pointType, type NodeKind } from './skillTreeMapPresentation'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { parsePlacementCoordinate as parseCoordinate } from '../state/placementCoordinates'
import { MaterialIconInput } from './MaterialIconInput'
import { MINECRAFT_MATERIAL_VERSION } from '../data/nodeFieldSuggestions'
import { describeNodeCost, describeNodeEffects } from '../data/nodeEffectPresentation'
import {
  masterTagLabel,
  masterTagTooltip,
  skillTreeNodeTagDefinitions,
} from '../data/masterTagPresentation'

interface PlacementInspectorProps {
  nodeId: string | null
  structure: StructureDocument
  master: NodeMaster | null
  masters?: ReadonlyMap<string, NodeMaster>
  onShowNode?: (nodeId: string) => void
  saving: boolean
  onDirtyChange?: (dirty: boolean) => void
  onInputValidityChange?: (valid: boolean) => void
  iconRevision: number
  onChange: (structure: StructureDocument) => void
  onSaveMaster: (node: NodeMaster) => Promise<NodeMaster | null>
  onEditMaster: (node: NodeMaster) => void
  onRetryIcons: () => void
  materialSuggestions?: readonly string[]
  tagSuggestions?: readonly string[]
  skillMasters?: readonly SkillMasterSummary[]
  classMasters?: readonly ClassMasterSummary[]
}

const EMPTY_MASTERS: ReadonlyMap<string, NodeMaster> = new Map()
const EMPTY_SKILLS: readonly SkillMasterSummary[] = []
const EMPTY_CLASSES: readonly ClassMasterSummary[] = []

export function PlacementInspector({
  nodeId,
  structure,
  master,
  masters = EMPTY_MASTERS,
  onShowNode,
  saving,
  onDirtyChange,
  onInputValidityChange,
  iconRevision,
  onChange,
  onSaveMaster,
  onEditMaster,
  onRetryIcons,
  materialSuggestions = [],
  tagSuggestions = [],
  skillMasters = EMPTY_SKILLS,
  classMasters = EMPTY_CLASSES,
}: PlacementInspectorProps) {
  const placement = structure.nodes.find((node) => node.nodeId === nodeId)
  const [draft, setDraft] = useState<NodeMaster | null>(() => master ? structuredClone(master) : null)
  const [pointCostDraft, setPointCostDraft] = useState(() => String(master?.pointCost ?? ''))
  const [masterSaving, setMasterSaving] = useState(false)
  const busy = saving || masterSaving
  const [coordinateDrafts, setCoordinateDrafts] = useState<Record<'x' | 'y' | 'z', string>>(() => ({ x: String(placement?.x ?? ''), y: String(placement?.y ?? ''), z: String(placement?.z ?? '') }))
  const previousPlacement = useRef(placement)
  const selectedMasterId = useRef(master?.nodeId)
  selectedMasterId.current = master?.nodeId
  useEffect(() => {
    setDraft(master ? structuredClone(master) : null)
    setPointCostDraft(String(master?.pointCost ?? ''))
  }, [master])
  useEffect(() => {
    const previous = previousPlacement.current
    previousPlacement.current = placement
    if (!placement) return
    setCoordinateDrafts((current) => {
      const next = { ...current }
      for (const key of ['x', 'y', 'z'] as const) {
        if (previous?.nodeId !== placement.nodeId || current[key] === String(previous?.[key])) next[key] = String(placement[key])
      }
      return next
    })
  }, [placement?.nodeId, placement?.x, placement?.y, placement?.z])
  const masterDirty = useMemo(
    () => Boolean(master && draft && JSON.stringify(master) !== JSON.stringify(draft)),
    [draft, master],
  )
  const coordinatePending = Boolean(placement && (['x', 'y', 'z'] as const).some((key) => coordinateDrafts[key] !== String(placement[key])))
  const coordinatesValid = !placement || Object.values(coordinateDrafts).every((value) => parseCoordinate(value) !== null)
  const costValid = !draft || parsePointCost(pointCostDraft) !== null
  const inputValid = coordinatesValid && costValid && !coordinatePending
  const masterInputDirty = Boolean(masterDirty || master && pointCostDraft !== String(master.pointCost))
  const inspectorDirty = Boolean(placement && (masterInputDirty || coordinatePending))
  useEffect(() => { onDirtyChange?.(inspectorDirty) }, [inspectorDirty, onDirtyChange])
  useEffect(() => { onInputValidityChange?.(inputValid) }, [inputValid, onInputValidityChange])
  useEffect(() => () => { onDirtyChange?.(false); onInputValidityChange?.(true) }, [onDirtyChange, onInputValidityChange])
  const knownTagIds = useMemo(() => new Set(skillTreeNodeTagDefinitions.map((tag) => tag.id)), [])
  const legacyTags = useMemo(
    () => [...new Set([...tagSuggestions, ...(draft?.tags ?? [])])].filter((tag) => !knownTagIds.has(tag)),
    [draft?.tags, knownTagIds, tagSuggestions],
  )

  const mapMaster = draft?.nodeId === placement?.nodeId ? draft : master
  const effects = useMemo(() => mapMaster ? mapNodeEffects(mapMaster, skillMasters) : [], [mapMaster, skillMasters])
  if (!placement) return <StructureOverview structure={structure} masters={masters} classMasters={classMasters} />

  const kind = nodeKind(mapMaster, placement.nodeId === structure.rootNodeId)
  const classId = nodeClassId(mapMaster)
  const level = nodeLevel(mapMaster)
  const linkedIds = connectedNodeIds(structure.edges, placement.nodeId)
  const root = structure.nodes.find(node => node.nodeId === structure.rootNodeId)
  const rootDistance = root ? Math.hypot(placement.x - root.x, placement.y - root.y, placement.z - root.z) : null
  const placedIds = new Set(structure.nodes.map(node => node.nodeId))

  const updatePlacement = (key: 'x' | 'y' | 'z', value: number) => onChange({
    ...structure,
    nodes: structure.nodes.map((node) => node.nodeId === placement.nodeId
      ? { ...node, [key]: value }
      : node),
  })
  const commitCoordinate = (key: 'x' | 'y' | 'z') => {
    if (busy) return
    const input = coordinateDrafts[key]
    const normalized = parseCoordinate(input)
    if (normalized === null) return
    setCoordinateDrafts((current) => ({ ...current, [key]: String(normalized) }))
    if (normalized !== placement[key]) updatePlacement(key, normalized)
  }
  const remove = () => onChange({
    ...structure,
    rootNodeId: structure.rootNodeId === placement.nodeId ? '' : structure.rootNodeId,
    nodes: structure.nodes.filter((node) => node.nodeId !== placement.nodeId),
    edges: structure.edges.filter((edge) => edge.sourceNodeId !== placement.nodeId && edge.targetNodeId !== placement.nodeId),
  })
  const updateMaster = (key: string, value: JsonValue) => { if (!busy) setDraft((current) => current ? { ...current, [key]: value } : current) }
  const updateUnlockConditionClassId = (classId: string) => setDraft((current) => {
    if (!current || busy) return current
    const unlockCondition = { ...current.unlockCondition }
    if (classId) unlockCondition.classId = classId
    else delete unlockCondition.classId

    const next = { ...current }
    if (Object.keys(unlockCondition).length === 0) delete next.unlockCondition
    else next.unlockCondition = unlockCondition
    return next
  })
  const saveMaster = async () => {
    if (!draft || busy || !inputValid) return
    setMasterSaving(true)
    try {
      const saved = await onSaveMaster(draft)
      if (saved && selectedMasterId.current === saved.nodeId) {
        setDraft((current) => current?.nodeId === saved.nodeId ? structuredClone(saved) : current)
        setPointCostDraft(String(saved.pointCost))
      }
    } finally { setMasterSaving(false) }
  }

  return (
    <section className="panel-section inspector node-master-inspector tree-map-detail" aria-label="ノード詳細">
      <h2>ノード詳細</h2>
      <div className="tree-map-detail-summary">
        <MapNodeIcon node={mapMaster} kind={kind} size={64} revision={iconRevision} />
        <div>
          {mapMaster && <span className="tree-map-chip">{pointType(mapMaster) === 'CP' ? 'CP（クラス）' : 'PP（共通）'}</span>}
          <h3><MapNodeName node={mapMaster} id={placement.nodeId} /></h3>
          <span className="tree-map-chip">{NODE_KIND_LABEL[kind]}</span>
          <p className="tree-map-muted">#{placement.nodeId}{masterInputDirty ? ' · マスター未保存' : ''}</p>
        </div>
      </div>
      <div className="tree-map-detail-section tree-map-detail-cost">
        <span>解放コスト</span><strong>{mapNodeCost(mapMaster)}</strong>
      </div>
      <div className="tree-map-detail-section">
        <h3>得られる効果</h3>
        <MapEffectList effects={effects} />
      </div>
      <div className="tree-map-detail-section">
        <h3>必要条件</h3>
        {classId && <p>{nodeClassName(mapMaster, classMasters)} <small className="tree-map-muted">（祖先職としても成立）</small></p>}
        {level !== undefined && <p>プレイヤーLv. {level} 以上</p>}
        {!classId && level === undefined && <p className="tree-map-muted">なし</p>}
      </div>
      <div className="tree-map-detail-section">
        <h3>つながるノード</h3>
        {linkedIds.length ? <div className="tree-map-linked">{linkedIds.map(id => <button type="button" key={id}
          onClick={() => onShowNode?.(id)} disabled={!onShowNode || busy || !inputValid || !placedIds.has(id)}>
          <MapNodeName node={masters.get(id)} id={id} />{' '}<small>{mapNodeCost(masters.get(id))}</small>
        </button>)}</div> : <p className="tree-map-muted">なし</p>}
      </div>

      <fieldset className="tree-map-editing" disabled={busy} style={{ border: 0, margin: 0, minWidth: 0 }}>
      <h3>配置と編集</h3>
      <div className="coordinate-grid">
        {(['x', 'y', 'z'] as const).map((key) => (
          <label key={key}>{key.toUpperCase()}<input
            aria-label={key.toUpperCase()}
            type="text"
            inputMode="decimal"
            disabled={busy}
            aria-invalid={parseCoordinate(coordinateDrafts[key]) === null}
            value={coordinateDrafts[key]}
            onChange={(event) => { if (!busy) setCoordinateDrafts((current) => ({ ...current, [key]: event.target.value })) }}
            onBlur={() => commitCoordinate(key)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') event.currentTarget.blur()
            }}
          /></label>
        ))}
      </div>
      <p className="muted">起点からの距離 {rootDistance === null ? '—' : `${rootDistance.toFixed(1)} ブロック`}</p>
      {!coordinatesValid && <p className="error-message" role="alert">座標は -2147483648～2147483647 の範囲で0.1単位の数値を入力してください。入力内容は保持しています。</p>}
      {coordinatesValid && coordinatePending && <p className="muted">座標はEnterまたはフォーカス移動で確定してください。</p>}
      <div className="button-row compact-actions">
        <button
          className="button compact"
          onClick={() => onChange({ ...structure, rootNodeId: placement.nodeId })}
          disabled={busy || !inputValid || structure.rootNodeId === placement.nodeId}
        >
          {structure.rootNodeId === placement.nodeId ? '起点' : '起点に設定'}
        </button>
        <button className="button danger subtle compact" onClick={remove} disabled={busy || !inputValid}>配置から削除</button>
      </div>

      <ConnectionControls key={placement.nodeId} nodeId={placement.nodeId} linkedIds={linkedIds}
        masters={masters} structure={structure} onChange={onChange} disabled={!inputValid} />

      {draft && (
        <>
          <div className="inspector-divider" />
          <div className="section-title">
            <h3 className="inspector-subtitle">マスター定義</h3>
            <span className={masterInputDirty ? 'badge warning' : 'badge ok'}>{masterInputDirty ? '未保存' : '保存済み'}</span>
          </div>
          <div className="inline-master-form">
            <label>名前
              <input aria-label="名前" value={draft.name} disabled={busy} onChange={(event) => updateMaster('name', event.target.value)} />
              {draft.name !== stripMinecraftFormatting(draft.name) && <small>表示: {stripMinecraftFormatting(draft.name)}</small>}
            </label>
            <label>Minecraft Material
              <div className="input-with-action">
                <MaterialIconInput
                  ariaLabel="Minecraft Material"
                  value={String(draft.icon)}
                  suggestions={materialSuggestions}
                  revision={iconRevision}
                  disabled={busy}
                  onChange={(value) => updateMaster('icon', value.toUpperCase())}
                />
                <button className="button subtle compact" type="button" onClick={onRetryIcons} disabled={busy}>再読込</button>
              </div>
              <small>Paper {MINECRAFT_MATERIAL_VERSION}のアイテムMaterial {materialSuggestions.length.toLocaleString()}件から検索できます。</small>
            </label>
            <div className="two-column-fields">
              <label>ポイント種別
                <select aria-label="ポイント種別" value={draft.pointType} disabled={busy} onChange={(event) => updateMaster('pointType', event.target.value)}>
                  <option value="CP">CP</option>
                  <option value="PP">PP</option>
                </select>
              </label>
              <label>コスト
                <input aria-label="コスト" inputMode="numeric" value={pointCostDraft} disabled={busy} aria-invalid={!costValid} onBlur={() => { if (!busy && costValid) setPointCostDraft(String(Number(pointCostDraft))) }} onChange={(event) => {
                  if (busy) return
                  const value = event.target.value
                  setPointCostDraft(value)
                  const parsed = parsePointCost(value)
                  if (parsed !== null) updateMaster('pointCost', parsed)
                }} />
              </label>
            </div>
            {!costValid && <p className="error-message" role="alert">コストは0～2147483647の整数を入力してください。入力内容は保持しています。</p>}
            {draft.pointType === 'CP' && (
              <label>対象クラス
                <select
                  aria-label="対象クラス"
                  disabled={busy}
                  value={unlockConditionClassId(draft)}
                  onChange={(event) => updateUnlockConditionClassId(event.target.value)}
                >
                  <option value="">対象クラスを選択してください</option>
                  {classMasters.map((classMaster) => (
                    <option value={classMaster.id} key={classMaster.id}>
                      {stripMinecraftFormatting(classMaster.name)}（{classMaster.id}）
                    </option>
                  ))}
                </select>
              </label>
            )}
            <label>タグを追加 <small>表示は日本語、保存値はID</small>
              <select
                aria-label="タグを追加"
                disabled={busy}
                value=""
                onChange={(event) => {
                  const tag = event.target.value
                  if (tag) updateMaster('tags', [...new Set([...draft.tags, tag])])
                }}
              >
                <option value="">候補を選択してください</option>
                {skillTreeNodeTagDefinitions
                  .filter((tag) => !draft.tags.includes(tag.id))
                  .map((tag) => (
                    <option value={tag.id} title={tag.description} key={tag.id}>
                      {tag.displayName}（{tag.id}）
                    </option>
                  ))}
                {legacyTags.filter((tag) => !draft.tags.includes(tag)).map((tag) => (
                  <option value={tag} key={tag}>未定義: {tag}</option>
                ))}
              </select>
            </label>
            {draft.tags.length > 0 && (
              <div className="tag-suggestions" aria-label="設定済みタグ">
                {draft.tags.map((tag) => (
                  <button
                    className="tag selected-tag"
                    type="button"
                    disabled={busy}
                    key={tag}
                    title={masterTagTooltip(tag)}
                    aria-label={`${masterTagLabel(tag)}を削除`}
                    onClick={() => updateMaster('tags', draft.tags.filter((value) => value !== tag))}
                  >
                    {masterTagLabel(tag)} <small>{tag}</small> ×
                  </button>
                ))}
              </div>
            )}
          </div>
          <div className="cost-summary">
            <strong>消費ポイント</strong>
            <span className={`cost-chip ${describeNodeCost(draft, classMasters).kind}`} title={describeNodeCost(draft, classMasters).detail}>
              {describeNodeCost(draft, classMasters).title}
            </span>
          </div>
          <div className="effect-summary">
            <strong>効果 ({draft.effects?.length ?? 0})</strong>
            {(draft.effects ?? []).length === 0
              ? <span className="muted">効果なし</span>
              : describeNodeEffects(draft, skillMasters).map((effect, index) => (
                  <span className={`effect-chip ${effect.kind}`} title={effect.detail} key={`${effect.kind}-${index}`}>
                    {effect.title}
                  </span>
                ))}
          </div>
          <div className="button-stack inspector-actions">
            <button className="button primary" onClick={() => void saveMaster()} disabled={!masterDirty || busy || !inputValid || !draft.name.trim() || !String(draft.icon).trim()}>
              {busy ? '保存中…' : 'マスター定義を保存'}
            </button>
            <button className="button subtle" onClick={() => {
              if (inspectorDirty && !window.confirm('インスペクターの未保存の入力を破棄しますか？')) return
              setDraft(master ? structuredClone(master) : null)
              setPointCostDraft(String(master?.pointCost ?? ''))
              setCoordinateDrafts({ x: String(placement.x), y: String(placement.y), z: String(placement.z) })
            }} disabled={!inspectorDirty || busy}>変更を戻す</button>
            <button className="button subtle" onClick={() => onEditMaster(draft)} disabled={busy || !inputValid}>効果・スキーマ・原稿 JSONを編集</button>
          </div>
        </>
      )}
      </fieldset>
    </section>
  )
}

function ConnectionControls({ nodeId, linkedIds, masters, structure, onChange, disabled }: {
  nodeId: string
  linkedIds: string[]
  masters: ReadonlyMap<string, NodeMaster>
  structure: StructureDocument
  onChange: (structure: StructureDocument) => void
  disabled: boolean
}) {
  const [open, setOpen] = useState(false)
  const [targetId, setTargetId] = useState('')
  const candidates = open ? structure.nodes.filter(node => node.nodeId !== nodeId && !linkedIds.includes(node.nodeId)) : []
  const add = () => {
    if (!candidates.some(node => node.nodeId === targetId)) return
    const [sourceNodeId, targetNodeId] = [nodeId, targetId].sort()
    onChange({ ...structure, edges: [...structure.edges, { sourceNodeId, targetNodeId }] })
    setTargetId('')
  }
  const remove = (id: string) => onChange({
    ...structure,
    edges: structure.edges.filter(edge => !(edge.sourceNodeId === nodeId && edge.targetNodeId === id
      || edge.sourceNodeId === id && edge.targetNodeId === nodeId)),
  })
  return <details onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>接続を編集</summary>
    {open && <>
      <div className="tree-map-connection-form">
        <label>接続先
          <select value={targetId} onChange={event => setTargetId(event.target.value)} disabled={disabled}>
            <option value="">配置済みのノードを選択</option>
            {candidates.map(({ nodeId: id }) => {
              const name = masters.get(id)?.name
              return <option value={id} key={id}>#{id}{name && ` · ${stripMinecraftFormatting(name)}`}</option>
            })}
          </select>
        </label>
        <button type="button" className="button compact" disabled={disabled || !targetId} onClick={add}>接続を追加</button>
      </div>
      <div className="button-row compact-actions">{linkedIds.map(id => <button type="button" key={id}
        className="button compact subtle" disabled={disabled} onClick={() => remove(id)}>#{id} との接続を削除</button>)}</div>
    </>}
  </details>
}

function StructureOverview({ structure, masters, classMasters }: {
  structure: StructureDocument
  masters: ReadonlyMap<string, NodeMaster>
  classMasters: readonly ClassMasterSummary[]
}) {
  const { kinds, classes, min, max } = useMemo(() => {
    const kinds = new Map<NodeKind, number>()
    const classes = new Map<string, number>()
    const min = { x: Infinity, y: Infinity, z: Infinity }
    const max = { x: -Infinity, y: -Infinity, z: -Infinity }
    for (const placement of structure.nodes) {
      const node = masters.get(placement.nodeId)
      const kind = nodeKind(node, placement.nodeId === structure.rootNodeId)
      const className = node ? nodeClassName(node, classMasters) : '定義不明'
      kinds.set(kind, (kinds.get(kind) ?? 0) + 1)
      classes.set(className, (classes.get(className) ?? 0) + 1)
      for (const axis of ['x', 'y', 'z'] as const) {
        min[axis] = Math.min(min[axis], placement[axis])
        max[axis] = Math.max(max[axis], placement[axis])
      }
    }
    return { kinds, classes, min, max }
  }, [structure.nodes, structure.rootNodeId, masters, classMasters])
  return <section className="panel-section inspector empty-inspector tree-map-detail" aria-label="ノード詳細">
    <h2>ノード詳細</h2>
    <div className="tree-map-detail-section">
      <h3>構造の概要</h3>
      <p><strong><MinecraftText value={structure.name || structure.structureId || '構造未選択'} /></strong></p>
      <p className="tree-map-muted">{structure.nodes.length} ノード · {structure.edges.length} 接続</p>
    </div>
    {structure.nodes.length > 0 && <>
      <div className="tree-map-detail-section">
        <h3>クラス条件ごとのノード数</h3>
        <dl>{[...classes].sort(([a], [b]) => a.localeCompare(b, 'ja')).map(([name, count]) =>
          <div key={name} className="tree-map-count"><dt>{name}</dt><dd>{count}</dd></div>,
        )}</dl>
      </div>
      <div className="tree-map-detail-section">
        <h3>種類ごとのノード数</h3>
        <dl>{(Object.keys(NODE_KIND_LABEL) as NodeKind[]).filter(kind => kinds.has(kind)).map(kind =>
          <div key={kind} className="tree-map-count"><dt>{NODE_KIND_LABEL[kind]}</dt><dd>{kinds.get(kind)}</dd></div>,
        )}</dl>
      </div>
      <div className="tree-map-detail-section">
        <h3>座標の範囲</h3>
        <dl>{(['x', 'y', 'z'] as const).map(axis => <div key={axis} className="tree-map-count">
          <dt>{axis.toUpperCase()}</dt><dd>{min[axis]} ～ {max[axis]}</dd>
        </div>)}</dl>
      </div>
    </>}
    <div className="tree-map-detail-section">
      <h3>操作のヒント</h3>
      <p>ノードを選ぶと効果や条件を確認できます。一覧の「表示」でノードの位置へ移動できます。</p>
      <p>空白をドラッグして地図を移動します。Shift を押すと範囲選択、Ctrl / Cmd を押してクリックすると追加選択できます。</p>
      <p>ノードのハンドルをドラッグして接続します。ノードを右クリックすると編集メニューを開きます。</p>
    </div>
  </section>
}

const parsePointCost = (raw: string): number | null => {
  const value = Number(raw)
  return /^\+?\d+$/.test(raw) && Number.isInteger(value) && value >= 0 && value <= 2147483647 ? value : null
}

const unlockConditionClassId = (node: NodeMaster): string => {
  const classId = node.unlockCondition?.classId
  return typeof classId === 'string' ? classId : ''
}
