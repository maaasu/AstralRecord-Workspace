import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { createMasterCandidateSearch } from '../data/masterCandidateSearch'
import { currentReferenceFile, newCollectionEntry, referenceId, referencedItemCategory, referenceObject, referenceText, setCollectionReference, visualReferenceCollection } from '../data/masterReferenceCollection'
import { useMasterArrayOrder } from '../state/useMasterArrayOrder'
import type { MasterVisualReferences } from '../state/masterVisualReferences'
import type { JsonObject, JsonValue } from '../types/editor'
import type { MasterFileSummary, MasterReference } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { MasterRowActions } from './MasterRowActions'
import { isPlacementEntryUnplaced, MasterGridPlacement } from './MasterGridPlacement'
import { MasterEntityPicker } from './MasterEntityPicker'
import { MasterReferenceButton } from './MasterReferenceButton'
import './masterReferenceCollection.css'

type Definition = NonNullable<ReturnType<typeof visualReferenceCollection>>
type PickerTarget = 'add' | { index: number; entry: JsonValue }

interface Props {
  path: string
  definition: Definition
  context: MasterVisualReferences
  values: JsonValue[]
  references: MasterReference[]
  disabled?: boolean
  onChange: (values: JsonValue[]) => void
  onNavigate?: (path: string) => void
  renderNumber: (path: string, value: number | null, kind: 'number' | 'integer', change: (value: JsonValue) => void, optional?: boolean) => ReactNode
}

export function MasterReferenceCollection({ path, definition, context, values, references, disabled, onChange, onNavigate, renderNumber }: Props) {
  const [target, setTarget] = useState<PickerTarget | null>(null)
  const [page, setPage] = useState(0)
  const [placing, setPlacing] = useState(false)
  const opener = useRef<HTMLElement>(null)
  const addButton = useRef<HTMLButtonElement>(null)
  const titleId = useId()
  const { byId, choices } = useMemo(() => {
    const byId = new Map<string, MasterReference | null>()
    for (const entry of references) {
      if (entry.kind !== definition.kind) continue
      const file = currentReferenceFile(entry, context.files, context.referencePrefixes)
      if (!file || definition.itemCategory && !referencedItemCategory(file)) continue
      const id = referenceId(entry.value, definition.kind, context.referencePrefixes)
      if (!id) continue
      byId.set(id, byId.has(id) ? null : { ...entry, value: id })
    }
    const choices = [...byId.values()].filter((entry): entry is MasterReference =>
      entry !== null && (!definition.candidatePath || definition.candidatePath.test(entry.path)))
    return { byId, choices }
  }, [references, definition, context.files, context.referencePrefixes])
  const dialogChoices = useMemo(() => definition.dialog ? choices.map(entry => {
    const file = context.files.get(entry.path)
    const name = entry.label || file?.name || entry.value
    return {
      id: entry.value, name, icon: file?.icon,
      customModelData: file?.customModelData, iconTexture: file?.iconTexture, iconModel: file?.iconModel,
      preview: { file, kind: definition.kind, label: name, reference: entry.value },
    }
  }) : [], [choices, context.files, definition.dialog, definition.kind])
  const openPicker = (next: number | 'add', element: HTMLElement) => {
    opener.current = element
    setTarget(next === 'add' ? next : { index: next, entry: values[next] })
  }
  const closePicker = () => {
    setTarget(null)
    requestAnimationFrame(() => opener.current?.focus())
  }
  const replace = (index: number, value: JsonValue) => onChange(values.map((entry, i) => i === index ? value : entry))
  const targetChanged = target !== null && target !== 'add' && values[target.index] !== target.entry
  const pageSize = 20
  const pageCount = Math.max(1, Math.ceil(values.length / pageSize))
  const currentPage = Math.min(page, pageCount - 1)
  const offset = currentPage * pageSize
  const select = (id: string) => {
    const candidate = byId.get(id)
    const file = candidate ? context.files.get(candidate.path) : undefined
    if (disabled || target === null || addButton.current?.closest('fieldset:disabled') ||
      targetChanged || (id && (!file || definition.candidatePath && !definition.candidatePath.test(file.path)))) return
    if (target === 'add') {
      onChange([...values, newCollectionEntry(definition, values, id, file)])
      setPage(Math.floor(values.length / pageSize))
    }
    else if (target !== null && id) {
      const entry = referenceObject(target.entry)
      if (entry) {
        const next = setCollectionReference(entry, definition, id, file, context.referencePrefixes)
        if (next !== entry) replace(target.index, next)
      }
    }
    closePicker()
  }
  const arranging = target !== null || disabled
  const placement = definition.placement
  const order = useMasterArrayOrder(values, Boolean(arranging || placement), onChange, index => setPage(Math.floor(index / pageSize)))
  const candidateFor = (entry: JsonObject) => byId.get(referenceId(referenceText(entry[definition.key]), definition.kind, context.referencePrefixes))
  const fileForEntry = (entry: JsonObject) => {
    const candidate = candidateFor(entry)
    return candidate ? context.files.get(candidate.path) : undefined
  }
  const chanceTotal = definition.kind === 'fishing_fish' ? fishingChanceTotal(values) : null
  return <section className="master-reference-collection" aria-labelledby={titleId}>
    <header className="master-collection-header">
      <h4 id={titleId} tabIndex={definition.dialog ? -1 : undefined} data-master-field-path={definition.dialog ? path : undefined}>{definition.title} <small>{values.length} 件</small></h4>
      <div className="master-collection-actions">
        {placement && <button type="button" className="button compact" disabled={arranging} onClick={() => setPlacing(!placing)}>
          {placing ? definition.noun + '一覧へ' : placement === 'shop' ? '売り場の配置' : '掲示板の配置'}
        </button>}
        {!placing && <button ref={addButton} type="button" className="button primary" aria-haspopup={definition.dialog ? 'dialog' : undefined}
          disabled={arranging} onClick={(event) => openPicker('add', event.currentTarget)}>＋ {definition.noun}を追加</button>}
      </div>
    </header>
    {placing && placement ? <MasterGridPlacement kind={placement} values={values} fileForEntry={fileForEntry} onChange={onChange} disabled={disabled} /> : <>
    {chanceTotal !== null && <p className={Math.abs(chanceTotal - 100) <= 0.000001 ? 'master-muted' : 'error-message'} role="status">
      出現確率の合計: {chanceTotal.toLocaleString('ja-JP', { maximumFractionDigits: 6 })}% / 100%
    </p>}
    {target !== null && (targetChanged
      ? <div className="master-collection-picker master-collection-picker-header"><span role="status">行の内容が変わりました。変更する行を選び直してください。</span>
        <button type="button" className="button compact subtle" onClick={closePicker}>閉じる</button></div>
      : definition.dialog ? <MasterEntityPicker title={definition.noun + (target === 'add' ? 'を追加' : 'を変更')} choices={dialogChoices}
        value={target === 'add' ? null : referenceId(referenceText(referenceObject(target.entry)?.[definition.key]), definition.kind, context.referencePrefixes)}
        onSelect={select} onClose={closePicker} />
      : <ReferenceCollectionPicker key={target === 'add' ? target : target.index} choices={choices} noun={definition.noun} kind={definition.kind} files={context.files}
        disabled={disabled} adding={target === 'add'} onSelect={select} onClose={closePicker} />)}
    {values.length === 0 && <p className="master-collection-empty">まだ{definition.noun}が設定されていません。</p>}
    <ol className="master-collection-rows">
      {values.slice(offset, offset + pageSize).map((entry, rowIndex) => {
        const index = offset + rowIndex
        const data = referenceObject(entry)
        const raw = referenceText(data?.[definition.key])
        const candidate = data && candidateFor(data)
        const file = data ? fileForEntry(data) : undefined
        const label = candidate?.label || file?.name || raw || '未設定'
        const plain = stripMinecraftFormatting(label) || raw || '未設定'
        const rowPath = `${path}/${index}`
        const update = (key: string, value: JsonValue) => {
          if (!data) return
          const next = { ...data, [key]: value }
          if (value === null && definition.fields.some(field => field.key === key && field.optional)) delete next[key]
          replace(index, next)
        }
        const identity = <><ReferenceIcon file={file} kind={definition.kind} /><span><strong><MinecraftText value={label} /></strong>
          {definition.dropFlags && data?.hidden === true && <small className="master-collection-status">非表示</small>}
          {definition.dropFlags && data?.luckAffected === false && <small className="master-collection-status">幸運補正なし</small>}
          {placement && isPlacementEntryUnplaced(entry, placement) && <small className="master-collection-status">未配置</small>}</span></>
        return <li key={index} className="master-collection-row" {...order.destination(index)}>
          {!placement && values.length > 1 && <button type="button" className="master-order-handle" aria-label={rowPath + ' を並べ替え'}
            title="ドラッグまたは上下キーで移動" {...order.handle(index)}>⠿</button>}
          {file ? <MasterReferenceButton type="button" className="master-collection-identity"
            preview={{ file, kind: definition.kind, label, reference: raw }} aria-label={`${plain} の定義を開く`} disabled={arranging} onClick={() => context.openFile(file.path)}>
            {identity}<span className="master-collection-arrow" aria-hidden="true">↗</span>
          </MasterReferenceButton> : <div className="master-collection-identity unresolved">{identity}{raw && <small>参照先が見つかりません</small>}</div>}
          {data && definition.fields.length > 0 && <div className="master-collection-values">
            {definition.fields.map(({ key, label: fieldLabel, defaultValue, input, optional }) => {
              const value = data[key] ?? defaultValue
              const fieldPath = `${rowPath}/${key}`
              return <div className="master-collection-value" key={key}><span>{fieldLabel}</span>
                {input === 'range' && (typeof value === 'string' || typeof value === 'number')
                  ? <input aria-label={fieldPath} value={value} placeholder="1 または 1~3" disabled={target !== null} onChange={(event) => update(key, event.target.value)} />
                  : typeof value === 'number' || (value === undefined && optional)
                    ? <fieldset disabled={target !== null}>{renderNumber(fieldPath, value ?? null, input === 'integer' ? 'integer' : 'number', (next) => update(key, next), optional)}</fieldset>
                    : <small>{value === undefined && key === 'level' ? '最小レベル' : '詳細で編集'}</small>}
              </div>
            })}
          </div>}
          <div className="master-collection-actions">
            {onNavigate && <button type="button" className="button compact subtle" aria-label={`${rowPath} を開く`} disabled={arranging} onClick={() => onNavigate(rowPath)}>編集</button>}
            <MasterRowActions path={rowPath} row={entry} disabled={arranging}
              onUp={!placement && index > 0 ? () => order.move(index, index - 1) : undefined}
              onDown={!placement && index < values.length - 1 ? () => order.move(index, index + 1) : undefined}
              onDuplicate={!placement ? () => onChange([...values.slice(0, index + 1), structuredClone(entry), ...values.slice(index + 1)]) : undefined}
              onReplace={data ? element => openPicker(index, element) : undefined} replaceLabel={rowPath + ' の' + definition.noun + 'を変更'}
              onDelete={() => onChange(values.filter((_, i) => i !== index))} />
          </div>
        </li>
      })}
    </ol>
    {pageCount > 1 && <div className="master-pagination">
      <button type="button" className="button compact" disabled={arranging || currentPage === 0} onClick={() => setPage(currentPage - 1)}>前の要素</button>
      <span>{offset + 1}–{Math.min(offset + pageSize, values.length)} / {values.length}件</span>
      <button type="button" className="button compact" disabled={arranging || currentPage === pageCount - 1} onClick={() => setPage(currentPage + 1)}>次の要素</button>
    </div>}
    </>}
  </section>
}

export function fishingChanceTotal(values: JsonValue[]) {
  return values.reduce<number>((total, entry) => {
    const chance = referenceObject(entry)?.chancePercent
    return total + (typeof chance === 'number' && Number.isFinite(chance) ? chance : 0)
  }, 0)
}

const referenceSymbols: Record<string, string> = {
  class: '◇', skill: '✦', quest: '▤', loot_pool: '▦', loot_table: '▦', recipe: '⚒', tag: '#', status: '▥', fishing_fish: '🐟', world: '◎',
  game_flow: '↪', progression_preset: '▣',
}

export const referenceSymbol = (kind: string | undefined) => kind && Object.hasOwn(referenceSymbols, kind) ? referenceSymbols[kind] : undefined

export function ReferenceIcon({ file, kind }: { file?: MasterFileSummary; kind?: string }) {
  const symbol = referenceSymbol(kind)
  if (!file?.icon && !file?.iconModel && symbol) return <span className="minecraft-icon master-reference-icon master-reference-symbol" aria-hidden="true">{symbol}</span>
  return <MinecraftIcon icon={file?.icon ?? null} customModelData={file?.customModelData} iconTexture={file?.iconTexture} iconModel={file?.iconModel} className="master-reference-icon" />
}

function ReferenceCollectionPicker({ choices, noun, kind, files, disabled, adding, onSelect, onClose }: {
  choices: MasterReference[]; noun: string; kind: string; files: ReadonlyMap<string, MasterFileSummary>; disabled?: boolean
  adding: boolean; onSelect: (id: string) => void; onClose: () => void
}) {
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const search = useRef<HTMLInputElement>(null)
  const firstResult = useRef<HTMLButtonElement>(null)
  const index = useMemo(() => createMasterCandidateSearch(choices), [choices])
  const matches = useMemo(() => index.find(query.trim().toLocaleLowerCase()), [index, query])
  const pageSize = 24
  const pageCount = Math.max(1, Math.ceil(matches.length / pageSize))
  const current = Math.min(page, pageCount - 1)
  useEffect(() => { search.current?.focus() }, [])
  return <div className="master-collection-picker" onKeyDown={(event) => {
    if (event.key !== 'Escape' || event.nativeEvent.isComposing) return
    event.preventDefault(); event.stopPropagation(); onClose()
  }}>
    <div className="master-collection-picker-header">
      <label>{noun}を検索<input type="search" ref={search} value={query} placeholder="名前またはID" disabled={disabled} onChange={(event) => { setQuery(event.target.value); setPage(0) }} onKeyDown={(event) => {
        if (event.nativeEvent.isComposing || (event.key !== 'ArrowDown' && event.key !== 'Enter') || !firstResult.current) return
        event.preventDefault(); firstResult.current.focus()
      }} /></label>
      <button type="button" className="button subtle compact" onClick={onClose}>閉じる</button>
    </div>
    <p className="master-muted" role="status">{matches.length} 件{matches.length === 0 && ' · 一致する候補がありません'}</p>
    <div className="master-collection-options">{matches.slice(current * pageSize, (current + 1) * pageSize).map((entry, i) => (
      <MasterReferenceButton key={entry.value} ref={i === 0 ? firstResult : undefined} type="button" className="master-collection-option"
        preview={{ file: files.get(entry.path), kind, label: entry.label ?? entry.value, reference: entry.value }} disabled={disabled} onClick={() => onSelect(entry.value)}>
        <ReferenceIcon file={files.get(entry.path)} kind={kind} /><span><strong><MinecraftText value={entry.label ?? entry.value} /></strong><code>{entry.value}</code></span>
      </MasterReferenceButton>
    ))}</div>
    <footer className="master-collection-picker-footer">
      {adding && <button type="button" className="button compact subtle" disabled={disabled} onClick={() => onSelect('')}>手入力用の行を追加</button>}
      {pageCount > 1 && <div className="master-pagination">
        <button type="button" className="button compact" disabled={current === 0 || disabled} onClick={() => setPage(current - 1)}>前へ</button>
        <span>{current + 1} / {pageCount}</span>
        <button type="button" className="button compact" disabled={current + 1 === pageCount || disabled} onClick={() => setPage(current + 1)}>次へ</button>
      </div>}
    </footer>
  </div>
}
