import { useEffect, useMemo, useRef, useState } from 'react'
import type { ClassMasterSummary, JsonValue, NodeMaster, SkillMasterSummary, StructureDocument } from '../types/editor'
import { MinecraftIcon } from './MinecraftIcon'
import { stripMinecraftFormatting } from '../utils/minecraft'
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

export function PlacementInspector({
  nodeId,
  structure,
  master,
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
  skillMasters = [],
  classMasters = [],
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

  if (!placement) {
    return (
      <section className="panel-section inspector empty-inspector">
        <h2>ノードインスペクター</h2>
        <p className="muted">キャンバス上のノードを選択すると、配置とマスター定義をここで編集できます。</p>
      </section>
    )
  }

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
    <section className="panel-section inspector node-master-inspector">
      <div className="inspector-heading">
        <MinecraftIcon icon={draft?.icon ?? master?.icon ?? ''} revision={iconRevision} className="inspector-node-icon" />
        <div>
          <span className="eyebrow">選択中のノード</span>
          <h2>{stripMinecraftFormatting(String(draft?.name ?? master?.name ?? '不明なノード'))}</h2>
          <p className="muted">nodeId #{placement.nodeId}{masterInputDirty ? ' · マスター未保存' : ''}</p>
        </div>
      </div>

      <fieldset disabled={busy} style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}>
      <h3 className="inspector-subtitle">配置</h3>
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

const parseCoordinate = (raw: string): number | null => {
  if (!/^[+-]?(?:\d+(?:\.\d+)?|\.\d+)$/.test(raw)) return null
  const value = Number(raw)
  if (!Number.isFinite(value) || value < -2147483648 || value > 2147483647 || Math.abs(value * 10 - Math.round(value * 10)) >= 0.0000001) return null
  return Math.round(value * 10) / 10
}

const parsePointCost = (raw: string): number | null => {
  const value = Number(raw)
  return /^\+?\d+$/.test(raw) && Number.isInteger(value) && value >= 0 && value <= 2147483647 ? value : null
}

const unlockConditionClassId = (node: NodeMaster): string => {
  const classId = node.unlockCondition?.classId
  return typeof classId === 'string' ? classId : ''
}
