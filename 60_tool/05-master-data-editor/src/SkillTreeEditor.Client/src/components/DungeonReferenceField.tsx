import { useMemo, useRef, useState } from 'react'
import { currentReferenceFile, referenceId } from '../data/masterReferenceCollection'
import type { MasterVisualReferences } from '../state/masterVisualReferences'
import type { MasterReference } from '../types/masterData'
import { MasterEntityPicker } from './MasterEntityPicker'
import { ReferenceIcon, referenceSymbol } from './MasterReferenceCollection'
import { MasterReferenceButton } from './MasterReferenceButton'
import { MinecraftText } from './MinecraftText'

export function dungeonReferenceKind(path: string) {
  if (path === '/entry/worldRef') return 'world'
  if (path === '/clearRewards/lootTable') return 'loot_table'
  if (/^\/clearRewards\/items\/\d+\/itemId(?:\/ref)?$/.test(path)) return 'item'
  if (path === '/encounter/bossMobId' || /^\/encounter\/normalMobPool\/\d+\/mobId(?:\/ref)?$/.test(path)) return 'mob'
}

function allowedMob(path: string, choicePath: string) {
  return path === '/encounter/bossMobId'
    ? choicePath.startsWith('40.features.mob/boss/')
    : choicePath.startsWith('40.features.mob/enemy/')
}

interface Props {
  path: string
  label: string
  kind: string
  value: string
  references: MasterReference[]
  context: MasterVisualReferences
  disabled?: boolean
  onChange: (value: string) => void
}

export function DungeonReferenceField({ path, label, kind, value, references, context, disabled, onChange }: Props) {
  const [open, setOpen] = useState(false)
  const opener = useRef<HTMLButtonElement>(null)
  const { files, referencePrefixes, openFile } = context
  const catalog = useMemo(() => {
    const byId = new Map<string, MasterReference | null>()
    for (const entry of references) {
      if (entry.kind !== kind || !currentReferenceFile(entry, files, referencePrefixes)) continue
      const id = referenceId(entry.value, kind, referencePrefixes)
      if (id) byId.set(id, byId.has(id) ? null : entry)
    }
    const choices = [...byId.entries()].flatMap(([id, entry]) => {
      if (!entry || kind === 'mob' && !allowedMob(path, entry.path)) return []
      const file = files.get(entry.path)
      return [{
        id, name: entry.label || file?.name || id, icon: file?.icon, customModelData: file?.customModelData,
        iconTexture: file?.iconTexture, iconModel: file?.iconModel, symbol: referenceSymbol(kind),
        preview: { file, kind, label: entry.label || file?.name || id, reference: kind + ':' + id },
      }]
    })
    return { byId, choices }
  }, [files, kind, path, referencePrefixes, references])
  const id = referenceId(value, kind, referencePrefixes)
  const candidate = catalog.byId.get(id)
  const file = candidate ? files.get(candidate.path) : undefined
  const name = candidate?.label || file?.name || value || '未設定'
  const selection = <><ReferenceIcon file={file} kind={kind} /><MinecraftText value={name} /></>
  const select = (nextId: string) => {
    if (!disabled && opener.current && !opener.current.matches(':disabled') && catalog.choices.some(choice => choice.id === nextId) && nextId !== id) {
      const prefix = id === value ? kind + ':' : value.slice(0, value.indexOf(':') + 1)
      onChange(prefix + nextId)
    }
    setOpen(false)
  }
  return <div className="master-reference-field dungeon-reference-field">
    {file ? <MasterReferenceButton type="button" className="master-reference-selection master-reference-link"
      preview={{ file, kind, label: name, reference: value }}
      aria-label={path + ' の定義を開く'} disabled={disabled} onClick={() => openFile(file.path)}>{selection}<span aria-hidden="true">↗</span></MasterReferenceButton>
      : <div className="master-reference-selection" title={value}>{selection}{value && <small>参照先が見つかりません</small>}</div>}
    <button ref={opener} type="button" className="button compact" aria-haspopup="dialog" aria-label={`${label}（${path}）を選択`}
      data-master-field-path={path} disabled={disabled} onClick={() => setOpen(true)}>{value ? '変更' : '候補を選ぶ'}</button>
    <details className="master-reference-id"><summary>IDを入力</summary>
      <input aria-label={path} value={value} onChange={event => onChange(event.target.value)} />
    </details>
    {kind === 'loot_table' && value && <button type="button" className="button compact subtle" disabled={disabled} onClick={() => onChange('')}>参照を解除</button>}
    {open && !disabled && <MasterEntityPicker title={label + 'を選択'} value={id} choices={catalog.choices} onSelect={select} onClose={() => setOpen(false)} />}
  </div>
}
