import { useEffect, useMemo, useState } from 'react'
import { compatibleEnchantChoices, enchantAmount, enchantChoiceKey, enchantEffectKey } from '../data/progressionDesign'
import type { DesignEquipment, DesignEquipmentEnchant, DesignItem, ProgressionDesignCatalog } from '../types/progressionDesign'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MasterEntityPicker, type MasterEntityPickerChoice } from './MasterEntityPicker'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { Modal } from './Modal'

interface Props {
  item: DesignItem
  equipment: DesignEquipment
  catalog: ProgressionDesignCatalog
  onClose: () => void
  onDirtyChange: (dirty: boolean) => void
  onApply: (enhanceLevel: number, enchants: DesignEquipmentEnchant[]) => Promise<boolean>
}

type Draft = Omit<DesignEquipmentEnchant, 'value'> & { amount: string }

export function BuildEquipmentOptionsDialog({ item, equipment, catalog, onClose, onDirtyChange, onApply }: Props) {
  const maxSlots = item.maxEnchantSlots ?? 0
  const [enhanceLevel, setEnhanceLevel] = useState(equipment.enhanceLevel)
  const [enchants, setEnchants] = useState<Draft[]>(() => (equipment.enchants ?? []).map(({ value, ...entry }) => ({ ...entry, amount: String(value) })))
  const [picking, setPicking] = useState<number | null>(null)
  const [applying, setApplying] = useState(false)
  const [failed, setFailed] = useState(false)
  const items = useMemo(() => new Map(catalog.items.map(entry => [entry.id, entry])), [catalog.items])
  const choices = useMemo(() => compatibleEnchantChoices(item, catalog), [item, catalog])
  const byKey = useMemo(() => new Map(choices.map(choice => [enchantChoiceKey(choice), choice])), [choices])
  const usedEffects = new Set(enchants.map(entry => enchantEffectKey(entry.effectId)))
  const amounts = enchants.map(entry => {
    const choice = byKey.get(enchantChoiceKey(entry))
    return choice ? enchantAmount(entry.amount, choice) : undefined
  })
  const valid = enhanceLevel >= 0 && enhanceLevel <= item.maxEnhanceLevel
    && enchants.length <= maxSlots && usedEffects.size === enchants.length && amounts.every(value => value !== undefined)
  const initialEnchants = equipment.enchants ?? []
  const dirty = enhanceLevel !== equipment.enhanceLevel || initialEnchants.length !== enchants.length
    || enchants.some((entry, index) => enchantChoiceKey(entry) !== enchantChoiceKey(initialEnchants[index]) || amounts[index] !== initialEnchants[index].value)
  useEffect(() => { onDirtyChange(dirty) }, [dirty, onDirtyChange])
  useEffect(() => () => onDirtyChange(false), [onDirtyChange])
  const canAdd = enchants.length < maxSlots && choices.some(choice => !usedEffects.has(enchantEffectKey(choice.effectId)))
  const pickerChoices: MasterEntityPickerChoice[] = picking === null ? [] : choices
    .filter(choice => !enchants.some((entry, index) => index !== picking && enchantEffectKey(entry.effectId) === enchantEffectKey(choice.effectId)))
    .map(choice => {
      const source = items.get(choice.sourceItemId)
      return { ...source, id: enchantChoiceKey(choice), name: effectName(choice),
        category: source?.name, description: rangeLabel(choice.min, choice.max) }
    })
  const apply = async () => {
    if (!valid || !dirty || applying) return
    setApplying(true)
    setFailed(false)
    const next = enchants.map(({ enchantMasterId, effectId }, index) => ({ enchantMasterId, effectId, value: amounts[index]! }))
    if (await onApply(enhanceLevel, next)) onClose()
    else { setFailed(true); setApplying(false) }
  }
  const close = () => { if (!applying) onClose() }

  return <>
    <Modal ariaLabel="装備の強化・エンチャント" className="design-build-dialog design-gear-dialog" onClose={close} closeDisabled={applying || picking !== null}>
      <header className="modal-header">
        <div><h2>装備の強化・エンチャント</h2><p><MinecraftText value={item.name} /></p></div>
      </header>
      <div className="design-build-body design-gear-options">
        <label className="design-dialog-label"><span>強化値</span>
          <select aria-label="強化値" value={enhanceLevel} disabled={applying || item.maxEnhanceLevel === 0 && enhanceLevel === 0} onChange={event => setEnhanceLevel(Number(event.target.value))}>
            {enhanceLevel > item.maxEnhanceLevel && <option value={enhanceLevel}>+{enhanceLevel}（範囲外）</option>}
            {Array.from({ length: item.maxEnhanceLevel + 1 }, (_, plus) => <option key={plus} value={plus}>+{plus}</option>)}
          </select>
          {item.maxEnhanceLevel === 0 && <small>この装備には強化段階がありません。</small>}
        </label>
        <div className="design-section-heading">
          <h3>付与エンチャント</h3><span>{enchants.length} / {maxSlots}枠</span>
        </div>
        {maxSlots === 0 && <p className="design-caption">この装備には付与枠がありません。</p>}
        {maxSlots > 0 && !choices.length && <p className="design-caption">この装備に設定できる付与効果がありません。</p>}
        {enchants.map((entry, index) => {
          const choice = byKey.get(enchantChoiceKey(entry))
          const source = choice && items.get(choice.sourceItemId)
          const duplicate = enchants.some((other, i) => i !== index && enchantEffectKey(other.effectId) === enchantEffectKey(entry.effectId))
          return <div className="design-gear-enchant" key={index}>
            <div className="design-gear-effect">
              {source && <MinecraftIcon icon={source.icon} customModelData={source.customModelData} iconTexture={source.iconTexture} />}
              <div><strong>{choice ? effectName(choice) : entry.effectId}</strong>
                {source && <small>{stripMinecraftFormatting(source.name)}</small>}
              </div>
              <button className="text-button" disabled={applying || !choices.some(candidate => !enchants.some((other, i) => i !== index && enchantEffectKey(other.effectId) === enchantEffectKey(candidate.effectId)))} onClick={() => setPicking(index)}>変更</button>
              <button className="text-button" aria-label={'付与効果 ' + (index + 1) + ' を外す'} disabled={applying} onClick={() => setEnchants(enchants.filter((_, i) => i !== index))}>外す</button>
            </div>
            <label className="design-gear-amount"><span>付与値</span><input inputMode="decimal" aria-label={'付与値 ' + (index + 1)} disabled={applying || !choice || choice.min === choice.max && amounts[index] !== undefined} aria-invalid={amounts[index] === undefined} value={entry.amount}
              onChange={event => setEnchants(enchants.map((current, i) => i === index ? { ...current, amount: event.target.value } : current))} />
              <small>{choice ? rangeLabel(choice.min, choice.max) : 'この装備に対応する効果を選び直してください。'}</small>
            </label>
            {duplicate && <small role="alert">同じ効果は重複して設定できません。</small>}
          </div>
        })}
        {maxSlots > 0 && <button className="button compact" disabled={applying || !canAdd} onClick={() => setPicking(enchants.length)}>＋ エンチャントを選択</button>}
        {failed && <p className="design-warning" role="alert">下書きに反映できませんでした。ビルドの詳細で原稿を確認してください。</p>}
      </div>
      <footer className="modal-footer">
        <button className="button" disabled={applying} onClick={close}>キャンセル</button>
        <button className="button primary" disabled={!valid || !dirty || applying} onClick={() => void apply()}>下書きに反映</button>
      </footer>
    </Modal>
    {picking !== null && <MasterEntityPicker title="付与エンチャントを選択" choices={pickerChoices}
      value={enchants[picking] && enchantChoiceKey(enchants[picking])} onClose={() => setPicking(null)} onSelect={id => {
        const choice = byKey.get(id)
        if (!choice) return
        const { enchantMasterId, effectId, min, max } = choice
        if (!enchants[picking] || enchantChoiceKey(enchants[picking]) !== id) {
          const first = enchantAmount(String(min), choice) ?? Math.ceil(min * 10000) / 10000
          const amount = first <= max ? String(first) : ''
          const entry = { enchantMasterId, effectId, amount }
          setEnchants(picking === enchants.length ? [...enchants, entry] : enchants.map((other, index) => index === picking ? entry : other))
        }
        setPicking(null)
      }} />}
  </>
}

function effectName(choice: { name: string; type: string }) {
  return choice.name + (choice.type === 'SCALAR' ? '（倍率）' : '（加算）')
}

function rangeLabel(min: number, max: number) {
  return min === max ? '固定値 ' + min : min + '〜' + max + '（小数4桁まで）'
}
