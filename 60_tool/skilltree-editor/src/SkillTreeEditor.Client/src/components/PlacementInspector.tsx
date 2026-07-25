import { useEffect, useMemo, useState } from 'react'
import type { JsonObject, JsonValue, NodeMaster, StructureDocument, StructurePlacement } from '../types/editor'
import { MinecraftIcon } from './MinecraftIcon'
import { stripMinecraftFormatting } from '../utils/minecraft'

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
}: PlacementInspectorProps) {
  const [draft, setDraft] = useState<NodeMaster | null>(() => master ? structuredClone(master) : null)

  useEffect(() => setDraft(master ? structuredClone(master) : null), [master])

  const placement = structure.nodes.find((node) => node.nodeId === nodeId)
  const masterDirty = useMemo(
    () => Boolean(master && draft && JSON.stringify(master) !== JSON.stringify(draft)),
    [draft, master],
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
                <input aria-label="Minecraft Material" value={String(draft.icon)} onChange={(event) => updateMaster('icon', event.target.value.toUpperCase())} />
                <button className="button subtle compact" type="button" onClick={onRetryIcons}>再読込</button>
              </div>
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
            <label>タグ <small>カンマ区切り</small>
              <input
                aria-label="タグ"
                value={(draft.tags ?? []).join(', ')}
                onChange={(event) => updateMaster('tags', splitList(event.target.value))}
              />
            </label>
            <label>Lore <small>1行につき1項目</small>
              <textarea
                aria-label="Lore"
                rows={3}
                value={(draft.lore ?? []).map(String).join('\n')}
                onChange={(event) => updateMaster('lore', event.target.value.split(/\r?\n/))}
              />
            </label>
          </div>
          <div className="effect-summary">
            <strong>Effects ({draft.effects?.length ?? 0})</strong>
            {(draft.effects ?? []).length === 0
              ? <span className="muted">効果なし</span>
              : (draft.effects ?? []).map((effect, index) => <EffectSummary key={index} effect={effect} />)}
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

function EffectSummary({ effect }: { effect: JsonValue }) {
  const value = effect !== null && typeof effect === 'object' && !Array.isArray(effect) ? effect as JsonObject : null
  if (!value) return <span className="effect-chip">不明な効果</span>
  if (value.type === 'skill') return <span className="effect-chip">skill: {String(value.skillId ?? '')}</span>
  if (value.type === 'status') return <span className="effect-chip">{String(value.status ?? '')} {String(value.modifierType ?? '')} {String(value.value ?? '')}</span>
  return <span className="effect-chip">{String(value.type ?? '不明')}</span>
}

const splitList = (value: string) => [...new Set(value.split(',').map((item) => item.trim()).filter(Boolean))]
const clampCoordinate = (value: number): StructurePlacement['x'] => Math.max(-2147483648, Math.min(2147483647, Math.round(value)))
