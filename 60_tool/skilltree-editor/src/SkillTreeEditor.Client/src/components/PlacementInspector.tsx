import { useEffect, useMemo, useState } from 'react'
import type { JsonValue, NodeMaster, SkillMasterSummary, StructureDocument, StructurePlacement } from '../types/editor'
import { MinecraftIcon } from './MinecraftIcon'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { SuggestionInput } from './SuggestionInput'
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
  iconRevision: number
  onChange: (structure: StructureDocument) => void
  onSaveMaster: (node: NodeMaster) => Promise<NodeMaster | null>
  onEditMaster: (node: NodeMaster) => void
  onRetryIcons: () => void
  materialSuggestions?: readonly string[]
  tagSuggestions?: readonly string[]
  skillMasters?: readonly SkillMasterSummary[]
}

export function PlacementInspector({
  nodeId,
  structure,
  master,
  saving,
  iconRevision,
  onChange,
  onSaveMaster,
  onEditMaster,
  onRetryIcons,
  materialSuggestions = [],
  tagSuggestions = [],
  skillMasters = [],
}: PlacementInspectorProps) {
  const [draft, setDraft] = useState<NodeMaster | null>(() => master ? structuredClone(master) : null)

  useEffect(() => setDraft(master ? structuredClone(master) : null), [master])

  const placement = structure.nodes.find((node) => node.nodeId === nodeId)
  const masterDirty = useMemo(
    () => Boolean(master && draft && JSON.stringify(master) !== JSON.stringify(draft)),
    [draft, master],
  )
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
      ? { ...node, [key]: clampCoordinate(value) }
      : node),
  })
  const remove = () => onChange({
    ...structure,
    rootNodeId: structure.rootNodeId === placement.nodeId ? '' : structure.rootNodeId,
    nodes: structure.nodes.filter((node) => node.nodeId !== placement.nodeId),
    edges: structure.edges.filter((edge) => edge.sourceNodeId !== placement.nodeId && edge.targetNodeId !== placement.nodeId),
  })
  const updateMaster = (key: string, value: JsonValue) => setDraft((current) => current ? { ...current, [key]: value } : current)
  const updateLore = (value: string) => setDraft((current) => {
    if (!current) return current
    const next = { ...current }
    if (value.trim().length === 0) delete next.lore
    else next.lore = value.split(/\r?\n/)
    return next
  })
  const saveMaster = async () => {
    if (!draft) return
    const saved = await onSaveMaster(draft)
    if (saved) setDraft(structuredClone(saved))
  }

  return (
    <section className="panel-section inspector node-master-inspector">
      <div className="inspector-heading">
        <MinecraftIcon icon={draft?.icon ?? master?.icon ?? ''} revision={iconRevision} className="inspector-node-icon" />
        <div>
          <span className="eyebrow">SELECTED NODE</span>
          <h2>{stripMinecraftFormatting(String(draft?.name ?? master?.name ?? '不明なノード'))}</h2>
          <p className="muted">nodeId #{placement.nodeId}{masterDirty ? ' · マスター未保存' : ''}</p>
        </div>
      </div>

      <h3 className="inspector-subtitle">配置</h3>
      <div className="coordinate-grid">
        {(['x', 'y', 'z'] as const).map((key) => (
          <label key={key}>{key.toUpperCase()}<input
            aria-label={key.toUpperCase()}
            type="number"
            step={1}
            min={-2147483648}
            max={2147483647}
            value={placement[key]}
            onChange={(event) => updatePlacement(key, Number(event.target.value) || 0)}
          /></label>
        ))}
      </div>
      <div className="button-row compact-actions">
        <button
          className="button compact"
          onClick={() => onChange({ ...structure, rootNodeId: placement.nodeId })}
          disabled={structure.rootNodeId === placement.nodeId}
        >
          {structure.rootNodeId === placement.nodeId ? 'ROOT' : 'ROOTに設定'}
        </button>
        <button className="button danger subtle compact" onClick={remove}>配置から削除</button>
      </div>

      {draft && (
        <>
          <div className="inspector-divider" />
          <div className="section-title">
            <h3 className="inspector-subtitle">マスター定義</h3>
            <span className={masterDirty ? 'badge warning' : 'badge ok'}>{masterDirty ? '未保存' : '保存済み'}</span>
          </div>
          <div className="inline-master-form">
            <label>名前
              <input aria-label="名前" value={draft.name} onChange={(event) => updateMaster('name', event.target.value)} />
              {draft.name !== stripMinecraftFormatting(draft.name) && <small>表示: {stripMinecraftFormatting(draft.name)}</small>}
            </label>
            <label>Minecraft Material
              <div className="input-with-action">
                <SuggestionInput
                  aria-label="Minecraft Material"
                  value={String(draft.icon)}
                  suggestions={materialSuggestions}
                  onChange={(event) => updateMaster('icon', event.target.value.toUpperCase())}
                />
                <button className="button subtle compact" type="button" onClick={onRetryIcons}>再読込</button>
              </div>
              <small>Paper {MINECRAFT_MATERIAL_VERSION}のアイテムMaterial {materialSuggestions.length.toLocaleString()}件から検索できます。</small>
            </label>
            <div className="two-column-fields">
              <label>ポイント種別
                <select aria-label="ポイント種別" value={draft.pointType} onChange={(event) => updateMaster('pointType', event.target.value)}>
                  <option value="CP">CP</option>
                  <option value="PP">PP</option>
                </select>
              </label>
              <label>コスト
                <input aria-label="コスト" type="number" min={0} step={1} value={draft.pointCost} onChange={(event) => updateMaster('pointCost', Math.max(0, Math.round(Number(event.target.value) || 0)))} />
              </label>
            </div>
            <label>タグを追加 <small>表示は日本語、保存値はID</small>
              <select
                aria-label="タグを追加"
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
            <label>Lore <small>任意。1行につき1項目（未入力時は未定義）</small>
              <textarea
                aria-label="Lore"
                rows={3}
                value={(draft.lore ?? []).map(String).join('\n')}
                onChange={(event) => updateLore(event.target.value)}
              />
            </label>
          </div>
          <div className="cost-summary">
            <strong>消費ポイント</strong>
            <span className={`cost-chip ${describeNodeCost(draft).kind}`} title={describeNodeCost(draft).detail}>
              {describeNodeCost(draft).title}
            </span>
          </div>
          <div className="effect-summary">
            <strong>Effects ({draft.effects?.length ?? 0})</strong>
            {(draft.effects ?? []).length === 0
              ? <span className="muted">効果なし</span>
              : describeNodeEffects(draft, skillMasters).map((effect, index) => (
                  <span className={`effect-chip ${effect.kind}`} title={effect.detail} key={`${effect.kind}-${index}`}>
                    {effect.title}
                  </span>
                ))}
          </div>
          <div className="button-stack inspector-actions">
            <button className="button primary" onClick={() => void saveMaster()} disabled={!masterDirty || saving || !draft.name.trim() || !String(draft.icon).trim()}>
              {saving ? '保存中…' : 'マスター定義を保存'}
            </button>
            <button className="button subtle" onClick={() => setDraft(master ? structuredClone(master) : null)} disabled={!masterDirty || saving}>変更を戻す</button>
            <button className="button subtle" onClick={() => onEditMaster(draft)} disabled={saving}>Effects・Schema・Raw JSONを編集</button>
          </div>
        </>
      )}
    </section>
  )
}

const clampCoordinate = (value: number): StructurePlacement['x'] => Math.max(-2147483648, Math.min(2147483647, Math.round(value)))
