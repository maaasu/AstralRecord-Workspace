import { useEffect, useId, useRef, useState } from 'react'
import { worldPlacementApi, WorldPlacementConflictError, type PlacementSnapshot, type WorldPlacement } from '../api/worldPlacementApi'
import { parsePlacementNumber, samePlacement, type PlacementNumericField } from '../state/worldPlacementEditing'
import { MaterialIconInput } from './MaterialIconInput'
import './teleporterEditPanel.css'

export type SavedWaystone = WorldPlacement & { waystone: NonNullable<WorldPlacement['waystone']> }
export type TeleporterEditTarget = { placement: SavedWaystone; revision: string }

const numericLabels: Record<PlacementNumericField, string> = {
  x: 'X', y: 'Y（高さ）', z: 'Z', yaw: 'Yaw（左右の向き）', pitch: 'Pitch（上下の向き）', unlockGold: '解放費用',
}
const numericFields = Object.keys(numericLabels) as PlacementNumericField[]
const coordinateLimits = { x: 30_000_000, y: 100_000, z: 30_000_000, pitch: 90 }
const fieldLabels = { name: '表示名', ...numericLabels, enabled: '配置を有効にする', lockEnabled: '解放条件を使用', icon: 'アイコン' }
const fields = Object.keys(fieldLabels) as (keyof typeof fieldLabels)[]

function readDraft({ waystone, enabled, x, y, z, yaw, pitch }: SavedWaystone) {
  return {
    name: waystone.name, icon: waystone.icon ?? '', enabled, lockEnabled: waystone.lockEnabled,
    x: String(x), y: String(y), z: String(z), yaw: String(yaw), pitch: String(pitch), unlockGold: String(waystone.unlockGold),
  }
}
type Draft = ReturnType<typeof readDraft>

function readNumber(draft: Draft, placement: SavedWaystone, field: PlacementNumericField) {
  const savedValue = field === 'unlockGold' ? placement.waystone.unlockGold : placement[field]
  const value = draft[field] === String(savedValue) ? savedValue : parsePlacementNumber(draft[field], field)
  if (value === null) return null
  if (field === 'unlockGold') return /^\+?\d+$/.test(draft[field].trim()) && Number.isSafeInteger(value) ? value : null
  if (field === 'yaw') return Number.isFinite(Math.fround(value)) ? value : null
  return Math.abs(value) <= coordinateLimits[field] ? value : null
}

function validateDraft(draft: Draft, placement: SavedWaystone): SavedWaystone | string {
  if (!draft.name.trim() || draft.name.length > 128) return '表示名は 1〜128 文字で入力してください。'
  if (draft.icon.length > 256) return 'アイコンは 256 文字以内で入力してください。'
  const next: SavedWaystone = {
    ...placement,
    enabled: draft.enabled,
    waystone: {
      ...placement.waystone,
      name: draft.name,
      lockEnabled: draft.lockEnabled,
      icon: draft.icon === (placement.waystone.icon ?? '') ? placement.waystone.icon : draft.icon.trim() || null,
    },
  }
  for (const field of numericFields) {
    const value = readNumber(draft, placement, field)
    if (value === null) {
      if (field === 'unlockGold') return '解放費用は 0〜9,007,199,254,740,991 の整数で入力してください。'
      if (field === 'yaw') return 'Yaw（左右の向き）を有効な数値で入力してください。'
      return `${numericLabels[field]} は -${coordinateLimits[field]}〜${coordinateLimits[field]} の数値で入力してください。`
    }
    if (field === 'unlockGold') next.waystone.unlockGold = value
    else next[field] = value
  }
  return next
}

function conflictsBetween(original: Draft, draft: Draft, latest: Draft) {
  return fields.filter(field => draft[field] !== original[field] && latest[field] !== original[field] && draft[field] !== latest[field])
}

function displayValue(value: string | boolean) {
  return typeof value === 'boolean' ? (value ? 'はい' : 'いいえ') : value || '（空欄）'
}

export function TeleporterEditPanel({ initialTarget, worldLabel, disabled, onSnapshot, onClose, onSaved, onDirtyChange, onBusyChange }: {
  initialTarget: TeleporterEditTarget
  worldLabel: string
  disabled: boolean
  onSnapshot: (snapshot: PlacementSnapshot) => void
  onClose: () => void
  onSaved: (snapshot: PlacementSnapshot) => void
  onDirtyChange: (dirty: boolean) => void
  onBusyChange: (busy: boolean) => void
}) {
  const heading = useId()
  const iconInputId = useId()
  const nameInput = useRef<HTMLInputElement>(null)
  const [target, setTarget] = useState(initialTarget)
  const [draft, setDraft] = useState(() => readDraft(initialTarget.placement))
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [operation, setOperation] = useState<'save' | 'read' | null>(null)
  const running = useRef(false)
  const [conflict, setConflict] = useState<'stale' | 'missing' | TeleporterEditTarget | null>(null)
  const original = readDraft(target.placement)
  const dirty = fields.some(field => draft[field] !== original[field])
  const latest = typeof conflict === 'object' && conflict ? readDraft(conflict.placement) : null
  const overlapping = latest ? conflictsBetween(original, draft, latest) : []

  useEffect(() => { nameInput.current?.focus() }, [])
  useEffect(() => { onDirtyChange(dirty) }, [dirty, onDirtyChange])

  function update<K extends keyof Draft>(field: K, value: Draft[K]) {
    setDraft(current => ({ ...current, [field]: value }))
    setError('')
    setNotice('')
  }

  function start(next: 'save' | 'read') {
    running.current = true
    setOperation(next)
    onBusyChange(true)
    setError('')
    setNotice('')
  }

  function finish() {
    running.current = false
    setOperation(null)
    onBusyChange(false)
  }

  function useLatest(next: TeleporterEditTarget, keepOverlap: boolean) {
    const latestDraft = readDraft(next.placement)
    const overlap = new Set(conflictsBetween(original, draft, latestDraft))
    let merged = latestDraft
    for (const field of fields) {
      if (draft[field] !== original[field] && (keepOverlap || !overlap.has(field))) {
        merged = { ...merged, [field]: draft[field] }
      }
    }
    setTarget(next)
    setDraft(merged)
    setConflict(null)
    setNotice('最新の設定を取り込みました。入力内容を確認して保存してください。')
  }

  async function readLatest() {
    if (running.current || conflict !== 'stale') return
    start('read')
    try {
      const snapshot = await worldPlacementApi.read()
      onSnapshot(snapshot)
      const saved = target.placement
      const placement = snapshot.placements.find((item): item is SavedWaystone => item.placementId === saved.placementId
        && item.kind === 'waystone' && item.waystone?.id === saved.waystone.id
        && item.worldId === saved.worldId && item.worldName === saved.worldName)
      if (!placement) { setConflict('missing'); return }
      const next = { placement, revision: snapshot.revision }
      if (conflictsBetween(original, draft, readDraft(placement)).length) setConflict(next)
      else useLatest(next, true)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '最新のテレポーター設定を取得できませんでした。')
    } finally { finish() }
  }

  async function save() {
    if (running.current || disabled || conflict || !dirty) return
    const placement = validateDraft(draft, target.placement)
    if (typeof placement === 'string') { setError(placement); return }
    if (samePlacement(placement, target.placement)) {
      setDraft(original)
      setError('')
      setNotice('保存する変更はありません。')
      return
    }
    start('save')
    try {
      const snapshot = await worldPlacementApi.savePlacement(target.revision, placement)
      onSaved(snapshot)
    } catch (failure) {
      if (failure instanceof WorldPlacementConflictError) setConflict('stale')
      else setError(failure instanceof Error ? failure.message : 'テレポーターを保存できませんでした。')
    } finally { finish() }
  }

  function close() {
    if (running.current || (dirty && !window.confirm('未保存のテレポーター編集を破棄しますか？'))) return
    onClose()
  }

  function numberInput(field: PlacementNumericField) {
    return <label key={field}>{numericLabels[field]}<input type="text" inputMode={field === 'unlockGold' ? 'numeric' : 'decimal'}
      value={draft[field]} aria-invalid={readNumber(draft, target.placement, field) === null}
      onChange={event => update(field, event.target.value)} /></label>
  }

  return <section className="sp-teleporter-edit" aria-labelledby={heading}>
    <h3 id={heading}>テレポーターを編集</h3>
    <p className="sp-muted">ワールド {worldLabel} ／ 道標ID <code>{target.placement.waystone.id}</code></p>
    <fieldset disabled={operation !== null || disabled} aria-label="テレポーターの設定">
      <label>表示名<input ref={nameInput} value={draft.name} maxLength={128} onChange={event => update('name', event.target.value)} /></label>
      <div className="sp-teleporter-edit-numbers">{numericFields.filter(field => field !== 'unlockGold').map(numberInput)}</div>
      <div className="sp-teleporter-edit-flags">
        <label><input type="checkbox" checked={draft.enabled} onChange={event => update('enabled', event.target.checked)} />配置を有効にする</label>
        <label><input type="checkbox" checked={draft.lockEnabled} onChange={event => update('lockEnabled', event.target.checked)} />解放条件を使用</label>
      </div>
      {numberInput('unlockGold')}
      <div className="sp-teleporter-edit-icon">
        <label htmlFor={iconInputId}>アイコン</label>
        <MaterialIconInput id={iconInputId} value={draft.icon} disabled={operation !== null || disabled} placeholder="既定の BEACON" onChange={value => update('icon', value)} />
      </div>
    </fieldset>
    {disabled && <p className="sp-muted">配置編集の未保存の変更や進行中の操作を終えると保存できます。</p>}
    {error && <p className="sp-error" role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {conflict === 'stale' && <div className="sp-error">
      <p role="alert">Dev の配置が先に更新されました。入力内容は保持されています。最新の設定を確認してください。</p>
      <button className="button" type="button" disabled={operation !== null} onClick={readLatest}>最新の設定を確認</button>
    </div>}
    {conflict === 'missing' && <p className="sp-error" role="alert">元のテレポーターは削除されたか、別の配置に変わりました。入力内容は確認できます。編集を閉じて一覧から選び直してください。</p>}
    {latest && typeof conflict === 'object' && conflict && <div className="sp-error">
      <p role="alert">同じ項目が Dev でも変更されました。これらの項目に使う値を選んでください。</p>
      <ul>{overlapping.map(field => <li key={field}>{fieldLabels[field]}　入力 <code>{displayValue(draft[field])}</code> ／ Dev <code>{displayValue(latest[field])}</code></li>)}</ul>
      <div className="sp-teleporter-edit-actions">
        <button className="button" type="button" onClick={() => useLatest(conflict, true)}>競合項目に入力値を使う</button>
        <button className="button" type="button" onClick={() => useLatest(conflict, false)}>競合項目に Dev の値を使う</button>
      </div>
    </div>}
    <div className="sp-teleporter-edit-actions">
      <button className="button primary" type="button" disabled={!dirty || operation !== null || disabled || conflict !== null} onClick={save}>
        {operation === 'save' ? '保存中…' : 'Dev に保存'}
      </button>
      <button className="button" type="button" disabled={operation !== null} onClick={close}>編集を閉じる</button>
    </div>
  </section>
}
