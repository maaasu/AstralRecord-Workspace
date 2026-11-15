import { useState, type ReactNode } from 'react'
import { setAccountNode, setAccountSigil, setAccountSkill, type AccountSkillList } from '../data/progressionAccount'
import type { JsonValue } from '../types/editor'
import type { DesignCheckpoint, DesignField, DesignItem, DesignPreset, ProgressionDesignCatalog } from '../types/progressionDesign'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { MasterEntityPicker, type MasterEntityPickerChoice } from './MasterEntityPicker'

type PickTarget = { kind: 'node' } | { kind: 'skill'; list: AccountSkillList; slot: number } | { kind: 'sigil'; list: AccountSkillList; slot: number; sigilSlot: number }

interface Props {
  catalog: ProgressionDesignCatalog
  preset: DesignPreset
  checkpoint: DesignCheckpoint
  disabled: boolean
  renderNumber: (entry: DesignField, title: string, max?: number) => ReactNode
  edit: (path: string, transform: (content: JsonValue) => JsonValue) => Promise<boolean>
  onExploreItem: (id: string) => void
}

const skillLabel = (list: AccountSkillList) => list === 'skills' ? 'アクションリング' : 'パッシブ'

export function AccountProgressionPanel({ catalog, preset, checkpoint, disabled, renderNumber, edit, onExploreItem }: Props) {
  const [picker, setPicker] = useState<PickTarget | null>(null)
  const [expanded, setExpanded] = useState(true)
  const skillsById = new Map((catalog.skills ?? []).map(skill => [skill.id, skill]))
  const nodesById = new Map((catalog.nodes ?? []).map(node => [node.id, node]))
  const itemsById = new Map(catalog.items.map(item => [item.id, item]))
  const nodes = checkpoint.nodes ?? []
  const classMax = catalog.classes.find(entry => entry.id === preset.classId)?.maxLevel

  const choices: MasterEntityPickerChoice[] = picker?.kind === 'node' ? (catalog.nodes ?? []).map(node => ({
    ...node, id: String(node.id), category: 'スキルツリー', disabledReason: nodes.includes(node.id) ? 'この段階で選択済みです' : undefined,
  })) : picker?.kind === 'skill' ? (catalog.skills ?? []).filter(skill => skill.passive === (picker.list === 'passives')).map(skill => ({
    ...skill, category: skill.passive ? 'パッシブ' : 'アクティブ',
    description: `必要プレイヤーLv.${skill.requiredLevel} · 最大スキルLv.${skill.maxLevel}`,
    disabledReason: skill.requiredLevel > checkpoint.level ? `この段階のLv.${checkpoint.level}では習得できません`
      : (checkpoint[picker.list] ?? []).some(entry => entry.slot !== picker.slot && entry.skillId === skill.id) ? '別の枠に設定済みです' : undefined,
  })) : picker?.kind === 'sigil' ? (() => {
    const assignment = (checkpoint[picker.list] ?? []).find(entry => entry.slot === picker.slot)
    const skill = assignment && skillsById.get(assignment.skillId)
    return catalog.items.filter(item => item.category === 'sigil' && skill?.allowedSigilIds.includes(item.id)).map(item => ({
      ...item, category: 'シジル', disabledReason: assignment?.sigils.some(entry => {
        if (entry.slot === picker.sigilSlot) return false
        const other = itemsById.get(entry.itemId)
        return entry.itemId === item.id || !!(item.sigilEquipGroupId && item.sigilEquipGroupId === other?.sigilEquipGroupId)
      }) ? '別の枠に同じシジルまたは装着グループが設定済みです' : undefined,
    }))
  })() : []
  const pickerValue = picker?.kind === 'skill' ? (checkpoint[picker.list] ?? []).find(entry => entry.slot === picker.slot)?.skillId
    : picker?.kind === 'sigil' ? (checkpoint[picker.list] ?? []).find(entry => entry.slot === picker.slot)?.sigils.find(entry => entry.slot === picker.sigilSlot)?.itemId : undefined
  const pickerTitle = picker?.kind === 'node' ? '習得ノードを追加' : picker?.kind === 'skill' ? `${skillLabel(picker.list)} ${picker.slot}番枠のスキル` : `シジル ${picker?.sigilSlot}番枠を選択`
  const select = async (id: string) => {
    if (!picker || disabled) return
    const target = picker
    const success = await edit(preset.path, content => target.kind === 'node' ? setAccountNode(content, checkpoint, Number(id), true)
      : target.kind === 'skill' ? setAccountSkill(content, checkpoint, target.list, target.slot, id)
        : setAccountSigil(content, checkpoint, target.list, target.slot, target.sigilSlot, id))
    if (success) setPicker(null)
  }
  const remove = (transform: (content: JsonValue) => JsonValue) => { void edit(preset.path, transform) }

  const skillGroup = (list: AccountSkillList) => {
    const assignments = [...(checkpoint[list] ?? [])].sort((a, b) => a.slot - b.slot)
    const max = list === 'skills' ? 8 : 12
    const vacantSlots = Array.from({ length: max }, (_, index) => index + 1).filter(slot => !assignments.some(entry => entry.slot === slot))
    return <section className="design-account-group" aria-label={skillLabel(list)}>
      <div className="design-account-heading"><h3>{skillLabel(list)}</h3><span>{assignments.length} / {max}枠</span></div>
      <div className="design-account-skills">{assignments.map(entry => {
        const skill = skillsById.get(entry.skillId)
        const unlocked = Math.max(0, ...(skill?.sigilSlotsByLevel ?? []).filter(row => row.level <= entry.skillLevel).map(row => row.slots))
        const visibleSigils = Math.max(unlocked, ...entry.sigils.map(sigil => sigil.slot), 0)
        return <article className="design-account-skill" key={entry.slot} aria-label={`${skillLabel(list)} ${entry.slot}番枠`}>
          <div className="design-account-skill-main"><span className="design-account-slot">{entry.slot}</span><MinecraftIcon icon={skill?.icon ?? null} customModelData={skill?.customModelData} iconTexture={skill?.iconTexture} /><div><strong>{skill ? <MinecraftText value={skill.name} /> : entry.skillId}</strong><small>スキルLv.{entry.skillLevel}{skill && ` / ${skill.maxLevel}`}</small></div></div>
          <div className="design-account-actions"><button className="button compact" disabled={disabled} onClick={() => setPicker({ kind: 'skill', list, slot: entry.slot })}>変更</button>{!(list === 'skills' && entry.slot === 1) && <button className="text-button" disabled={disabled} onClick={() => remove(content => setAccountSkill(content, checkpoint, list, entry.slot, null))}>外す</button>}</div>
          {renderNumber({ path: preset.path, pointer: `${entry.pointer}/skillLevel`, value: entry.skillLevel }, `スキルLv.（${skillLabel(list)}${entry.slot}番枠）`, skill?.passive && !skill.bindRequired ? 1 : skill?.maxLevel)}
          {visibleSigils > 0 && <div className="design-account-sigils" role="group" aria-label={`${skillLabel(list)} ${entry.slot}番枠のシジル`}>{Array.from({ length: visibleSigils }, (_, index) => index + 1).map(sigilSlot => {
            const assigned = entry.sigils.find(sigil => sigil.slot === sigilSlot)
            const item: DesignItem | undefined = assigned && itemsById.get(assigned.itemId)
            return <div className="design-account-sigil" key={sigilSlot}><span>シジル {sigilSlot}</span><span className="design-account-identity">{item && <MinecraftIcon icon={item.icon} customModelData={item.customModelData} iconTexture={item.iconTexture} />}{item ? <MinecraftText value={item.name} /> : assigned?.itemId ?? '未設定'}</span><button className="text-button" disabled={disabled || sigilSlot > unlocked} onClick={() => setPicker({ kind: 'sigil', list, slot: entry.slot, sigilSlot })}>{assigned ? '変更' : '設定'}</button>{assigned && <button className="text-button" disabled={disabled} onClick={() => onExploreItem(assigned.itemId)}>入手経路</button>}{assigned && <button className="text-button" disabled={disabled} onClick={() => remove(content => setAccountSigil(content, checkpoint, list, entry.slot, sigilSlot, null))}>外す</button>}</div>
          })}</div>}
        </article>
      })}</div>
      {vacantSlots.length > 0 && <div className="design-account-add-slots">{vacantSlots.map(slot => <button className="button compact" key={slot} disabled={disabled} onClick={() => setPicker({ kind: 'skill', list, slot })}>＋ {skillLabel(list)} {slot}番枠</button>)}</div>}
    </section>
  }

  return <details className="design-account" open={expanded} onToggle={event => setExpanded(event.currentTarget.open)}>
    <summary>アカウント進行設定 <span>Lv.{checkpoint.level}の構成 · 職業Lv.{checkpoint.classLevel} · ノード{nodes.length}件</span></summary>
    <div className="design-account-content">
      <p className="design-caption">このレベル段階で適用するアカウントの構成です。装備と同じ下書き・保存履歴で編集します。</p>
      <div className="design-account-level">{renderNumber({ path: preset.path, pointer: `${checkpoint.pointer}/classLevel`, value: checkpoint.classLevel }, '職業レベル', classMax)}</div>
      <section className="design-account-group" aria-label="習得ノード"><div className="design-account-heading"><h3>習得ノード</h3><span>{nodes.length}件</span></div>
        <div className="design-account-nodes">{nodes.map(id => { const node = nodesById.get(id); return <div className="design-account-node" key={id}><MinecraftIcon icon={node?.icon ?? null} customModelData={node?.customModelData} iconTexture={node?.iconTexture} /><span>{node ? <MinecraftText value={node.name} /> : `ノード ${id}`}</span><button className="text-button" aria-label={`${node?.name ?? `ノード ${id}`}を外す`} disabled={disabled} onClick={() => remove(content => setAccountNode(content, checkpoint, id, false))}>外す</button></div> })}</div>
        <button className="button compact" disabled={disabled} onClick={() => setPicker({ kind: 'node' })}>＋ ノードを追加</button>
        <p className="design-caption">ノードの接続・ポイント予算はゲーム適用時にも確認してください。</p>
      </section>
      <div className="design-account-skill-groups">{skillGroup('skills')}{skillGroup('passives')}</div>
    </div>
    {picker && <MasterEntityPicker title={pickerTitle} choices={choices} value={pickerValue} loading={disabled} onSelect={id => { void select(id) }} onClose={() => { if (!disabled) setPicker(null) }} />}
  </details>
}
