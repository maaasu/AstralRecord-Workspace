import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { createMasterCandidateSearch } from '../data/masterCandidateSearch'
import { currentReferenceFile, newCollectionEntry, referenceId, referenceObject, referenceText, setCollectionReference, visualReferenceCollection } from '../data/masterReferenceCollection'
import type { MasterVisualReferences } from '../state/masterVisualReferences'
import type { JsonValue } from '../types/editor'
import type { MasterFileSummary, MasterReference } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
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
  onNavigate: (path: string) => void
  renderNumber: (path: string, value: JsonValue, change: (value: JsonValue) => void) => ReactNode
}

export function MasterReferenceCollection({ path, definition, context, values, references, disabled, onChange, onNavigate, renderNumber }: Props) {
  const [target, setTarget] = useState<PickerTarget | null>(null)
  const opener = useRef<HTMLButtonElement>(null)
  const titleId = useId()
  const { byId, choices } = useMemo(() => {
    const byId = new Map<string, MasterReference | null>()
    for (const entry of references) {
      if (entry.kind !== definition.kind || !currentReferenceFile(entry, context.files, context.referencePrefixes)) continue
      const id = referenceId(entry.value, definition.kind, context.referencePrefixes)
      if (!id) continue
      byId.set(id, byId.has(id) ? null : { ...entry, value: id })
    }
    const choices = [...byId.values()].filter((entry) => entry !== null)
    return { byId, choices }
  }, [references, definition, context.files, context.referencePrefixes])
  const openPicker = (next: number | 'add', button: HTMLButtonElement) => {
    opener.current = button
    setTarget(next === 'add' ? next : { index: next, entry: values[next] })
  }
  const closePicker = () => {
    setTarget(null)
    requestAnimationFrame(() => opener.current?.focus())
  }
  const replace = (index: number, value: JsonValue) => onChange(values.map((entry, i) => i === index ? value : entry))
  const targetChanged = target !== null && target !== 'add' && values[target.index] !== target.entry
  const select = (id: string) => {
    if (disabled || targetChanged || (id && !byId.get(id))) return
    if (target === 'add') onChange([...values, newCollectionEntry(definition, context.documentPath, id)])
    else if (target !== null && id) {
      const entry = referenceObject(target.entry)
      if (entry) replace(target.index, setCollectionReference(entry, definition, id))
    }
    closePicker()
  }
  const move = (index: number, direction: -1 | 1) => {
    const next = [...values]
    ;[next[index], next[index + direction]] = [next[index + direction], next[index]]
    onChange(next)
  }
  const arranging = target !== null || disabled
  return <section className="master-reference-collection" aria-labelledby={titleId}>
    <header className="master-collection-header">
      <div><h4 id={titleId}>{definition.title} <small>{values.length} 件</small></h4>
        <p>名前やアイコンから定義を開けます。「詳細」でその他の設定を編集できます。</p></div>
      <button type="button" className="button primary" disabled={arranging} onClick={(event) => openPicker('add', event.currentTarget)}>＋ {definition.noun}を追加</button>
    </header>
    {target !== null && (targetChanged
      ? <div className="master-collection-picker master-collection-picker-header"><span role="status">行の内容が変わりました。変更する行を選び直してください。</span>
        <button type="button" className="button compact subtle" onClick={closePicker}>閉じる</button></div>
      : <ReferenceCollectionPicker key={target === 'add' ? target : target.index} choices={choices} noun={definition.noun} files={context.files}
        disabled={disabled} adding={target === 'add'} onSelect={select} onClose={closePicker} />)}
    {values.length === 0 && <p className="master-collection-empty">まだ{definition.noun}が設定されていません。</p>}
    <ol className="master-collection-rows">
      {values.map((entry, index) => {
        const data = referenceObject(entry)
        const raw = referenceText(data?.[definition.key])
        const candidate = byId.get(referenceId(raw, definition.kind, context.referencePrefixes))
        const file = candidate && context.files.get(candidate.path)
        const label = candidate?.label || file?.name || raw || '未設定'
        const plain = stripMinecraftFormatting(label) || raw || '未設定'
        const rowPath = `${path}/${index}`
        const update = (key: string, value: JsonValue) => { if (data) replace(index, { ...data, [key]: value }) }
        const identity = <><ReferenceIcon file={file || undefined} /><span><strong><MinecraftText value={label} /></strong>
          <code>{raw || (data ? definition.key : '詳細で形式を確認')}</code></span></>
        return <li key={index} className="master-collection-row">
          {file ? <button type="button" className="master-collection-identity" aria-label={`${plain} の定義を開く`} disabled={arranging} onClick={() => context.openFile(file.path)}>
            {identity}<span className="master-collection-arrow" aria-hidden="true">↗</span>
          </button> : <div className="master-collection-identity unresolved">{identity}{raw && <small>参照先が見つかりません</small>}</div>}
          {data && <div className="master-collection-values">
            {definition.fields.map(({ key, label: fieldLabel, defaultValue }) => {
              const value = data[key] ?? defaultValue
              const fieldPath = `${rowPath}/${key}`
              return <div className="master-collection-value" key={key}><span>{fieldLabel}</span>
                {key === 'amount' && (typeof value === 'string' || typeof value === 'number')
                  ? <input aria-label={fieldPath} value={value} placeholder="1 または 1~3" disabled={target !== null} onChange={(event) => update(key, event.target.value)} />
                  : typeof value === 'number'
                    ? <fieldset disabled={target !== null}>{renderNumber(fieldPath, value, (next) => update(key, next))}</fieldset>
                    : <small>{value === undefined && key === 'level' ? '最小レベル' : '詳細で編集'}</small>}
              </div>
            })}
            {definition.kind === 'item' && (data.hidden === true || data.luckAffected === false) && <div className="master-collection-badges">
              {data.hidden === true && <span>図鑑に非表示</span>}
              {data.luckAffected === false && <span>幸運補正なし</span>}
            </div>}
          </div>}
          <div className="master-collection-actions">
            <button type="button" className="button compact subtle" aria-label={`${rowPath} 上へ`} disabled={index === 0 || arranging} onClick={() => move(index, -1)}>↑</button>
            <button type="button" className="button compact subtle" aria-label={`${rowPath} 下へ`} disabled={index === values.length - 1 || arranging} onClick={() => move(index, 1)}>↓</button>
            <button type="button" className="button compact subtle" disabled={arranging} onClick={() => onChange([...values.slice(0, index + 1), structuredClone(entry), ...values.slice(index + 1)])}>複製</button>
            {data && <button type="button" className="button compact subtle" aria-label={`${rowPath} の${definition.noun}を変更`} disabled={arranging} onClick={(event) => openPicker(index, event.currentTarget)}>変更</button>}
            <button type="button" className="button compact subtle" aria-label={`${rowPath} を開く`} disabled={arranging} onClick={() => onNavigate(rowPath)}>詳細</button>
            <button type="button" className="button compact danger subtle" aria-label={`${rowPath} を削除`} disabled={arranging} onClick={() => onChange(values.filter((_, i) => i !== index))}>削除</button>
          </div>
        </li>
      })}
    </ol>
  </section>
}

export function ReferenceIcon({ file }: { file?: MasterFileSummary }) {
  return <MinecraftIcon icon={file?.icon ?? null} customModelData={file?.customModelData} iconTexture={file?.iconTexture} className="master-reference-icon" />
}

function ReferenceCollectionPicker({ choices, noun, files, disabled, adding, onSelect, onClose }: {
  choices: MasterReference[]; noun: string; files: ReadonlyMap<string, MasterFileSummary>; disabled?: boolean
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
      <button key={entry.value} ref={i === 0 ? firstResult : undefined} type="button" className="master-collection-option" disabled={disabled} onClick={() => onSelect(entry.value)}>
        <ReferenceIcon file={files.get(entry.path)} /><span><strong><MinecraftText value={entry.label ?? entry.value} /></strong><code>{entry.value}</code></span>
      </button>
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
