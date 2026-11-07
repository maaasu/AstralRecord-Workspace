import { masterFieldLabel, masterFieldTitle } from '../data/masterDataPresentation'
import { masterStatusSuggestions, masterTagSuggestions, masterValueFieldKey, masterValueLabel, masterValueReferenceKind } from '../data/masterValuePresentation'
import { masterUnionMatches, mergeMasterUnion, resolveMasterSchema as resolveSchema } from '../data/masterFormSchema'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import type { JsonObject, JsonValue } from '../types/editor'
import type { MasterField, MasterReference } from '../types/masterData'
import { MaterialIconInput } from './MaterialIconInput'
import { summarizeMasterValue } from '../data/masterDataNavigation'
import { MinecraftText } from './MinecraftText'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { createMasterCandidateSearch } from '../data/masterCandidateSearch'
import './masterDataForm.css'

type ValueType = 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array' | 'null'
const typeLabels: Record<ValueType, string> = {
  string: '文字列', number: '数値', integer: '整数', boolean: '真偽値', object: 'オブジェクト / Map', array: '配列 / List', null: 'null',
}
const types = Object.keys(typeLabels) as ValueType[]
const emptyReferences: MasterReference[] = []
const isSigilReference = (entry: MasterReference) => entry.path.startsWith('10.features.item/70.sigil/')
const isCaughtItemReference = (entry: MasterReference) => !['20.equipment', '80.pet', '81.pet_egg'].some((category) => entry.path.startsWith(`10.features.item/${category}/`))
const textFieldName = (path: string) => path.split('/').at(-1)?.replaceAll('~1', '/').replaceAll('~0', '~')
const isDescriptionField = (path: string) => ['description', 'lore'].includes(textFieldName(path) ?? '')
const isTextLineArray = (path: string, value: JsonValue): value is string[] =>
  Array.isArray(value) && isDescriptionField(path) && value.every((entry) => typeof entry === 'string')
export function normalizeMasterType(type: unknown): ValueType | undefined {
  if (typeof type !== 'string') return undefined
  const value = type.trim().toLowerCase()
  if (/^(?:list|array|set)(?:\b|<|\[)/.test(value) || value.endsWith('[]')) return 'array'
  if (/^(?:map|object|dictionary)(?:\b|<)/.test(value)) return 'object'
  if (/^(?:integer|int|int32|int64|long|short)$/.test(value)) return 'integer'
  if (/^(?:number|double|float|decimal)$/.test(value)) return 'number'
  if (/^(?:boolean|bool)$/.test(value)) return 'boolean'
  if (/^(?:string|text|uuid|enum)$/.test(value)) return 'string'
  return types.includes(value as ValueType) ? value as ValueType : undefined
}
const objectOf = (value: JsonValue | undefined): JsonObject | null => value && typeof value === 'object' && !Array.isArray(value) ? value : null
export const pointerKey = (key: string) => key.replaceAll('~', '~0').replaceAll('/', '~1')

export function fieldForPath(fields: MasterField[], path: string): MasterField | undefined {
  return fields.find((field) => {
    const expected = field.path.startsWith('/') ? field.path : `/${field.path.replaceAll('.', '/').replaceAll('[]', '/*')}`
    const actual = path.split('/')
    const pattern = expected.split('/')
    return pattern.length === actual.length && pattern.every((part, index) => part === '*' || part === actual[index])
  })
}

function valueType(value: JsonValue, schema: JsonObject = {}, field?: MasterField): ValueType {
  const declared = schema.type ?? field?.type
  const normalized = normalizeMasterType(declared)
  if (Array.isArray(value)) return 'array'
  if (value === null) return 'null'
  if (typeof value === 'object') return 'object'
  if (typeof value === 'number' && normalized === 'integer') return 'integer'
  return typeof value as ValueType
}

export function masterDefault(schema: JsonObject = {}, type: string = 'string', root = schema): JsonValue {
  const resolved = resolveSchema(schema, root)
  if (resolved.default !== undefined) return structuredClone(resolved.default)
  if (resolved.const !== undefined) return structuredClone(resolved.const)
  if (Array.isArray(resolved.enum) && resolved.enum.length) return structuredClone(resolved.enum[0])
  const union = resolved.oneOf ?? resolved.anyOf
  if (Array.isArray(union) && union.length) return masterDefault(objectOf(union[0]) ?? {}, type, root)
  const selected = normalizeMasterType(resolved.type) ?? normalizeMasterType(type) ?? type
  if (selected === 'object' || resolved.properties) {
    const properties = objectOf(resolved.properties) ?? {}
    const required = Array.isArray(resolved.required) ? resolved.required.map(String) : []
    return Object.fromEntries(required.map((key) => [key, masterDefault(objectOf(properties[key]) ?? {}, 'string', root)]))
  }
  if (selected === 'array') return []
  if (selected === 'number' || selected === 'integer') return 0
  if (selected === 'boolean') return false
  if (selected === 'null') return null
  return ''
}

function documentedFieldDefault(field: MasterField | undefined, schema: JsonObject, fallbackType: string, root: JsonObject): JsonValue {
  if (schema.default !== undefined || schema.const !== undefined || schema.enum !== undefined) return masterDefault(schema, field?.type ?? fallbackType, root)
  const documented = field?.default?.trim()
  const type = normalizeMasterType(field?.type ?? fallbackType)
  if (documented === 'true' && type === 'boolean') return true
  if (documented === 'false' && type === 'boolean') return false
  if (documented && type === 'string' && /^[A-Za-z][A-Za-z0-9_]*$/.test(documented)
      && documented !== 'Null') return documented
  return masterDefault(schema, field?.type ?? fallbackType, root)
}

interface MasterDataFormProps {
  value: JsonValue
  onChange: (value: JsonValue) => void
  fields: MasterField[]
  references?: MasterReference[]
  schema?: JsonObject
  rootSchema?: JsonObject
  path?: string
  onInvalid?: (path: string, message: string | null, text?: string) => void
  invalidValues?: Record<string, string>
  onNavigate?: (path: string) => void
  navigationDisabled?: boolean
}

export function MasterDataForm({ value, onChange, fields, references = emptyReferences, schema = {}, rootSchema = schema, path = '', onInvalid, invalidValues, onNavigate, navigationDisabled }: MasterDataFormProps) {
  const resolved = resolveSchema(schema, rootSchema)
  const metadata = fieldForPath(fields, path)
  const kind = valueType(value, resolved, metadata)
  const inherited = { fields, references, rootSchema, onInvalid, invalidValues, onNavigate, navigationDisabled }
  const unions = Array.isArray(resolved.oneOf ?? resolved.anyOf) ? (resolved.oneOf ?? resolved.anyOf) as JsonValue[] : []
  const choices = unions.map((choice) => resolveSchema(objectOf(choice) ?? {}, rootSchema))
  const selectedUnion = Math.max(0, choices.findIndex((choice) => masterUnionMatches(choice, value, rootSchema)))
  if (unions.length) {
    return <div className="master-union"><label>定義の種類<select aria-label={`${path || '/'} 定義の種類`} value={selectedUnion} onChange={(event) => {
      const next = choices[Number(event.target.value)] ?? {}
      const defaults = masterDefault(next, 'object', rootSchema)
      onChange(mergeMasterUnion(value, defaults, choices[selectedUnion], next, rootSchema))
    }} disabled={navigationDisabled}>{choices.map((choice, index) => <option key={index} value={index}>{String(choice.title ?? (objectOf(objectOf(choice.properties)?.type)?.const === 'skill' ? 'スキル効果' : objectOf(objectOf(choice.properties)?.type)?.const === 'status' ? 'ステータス効果' : `種類 ${index + 1}`))}</option>)}</select></label>
      <MasterDataForm {...inherited} path={path} schema={choices[selectedUnion] ?? {}} value={value} onChange={onChange} />
    </div>
  }
  if (kind === 'object') {
    return <ObjectEditor {...inherited} path={path} schema={resolved} value={objectOf(value) ?? {}} onChange={onChange} />
  }
  if (kind === 'array') {
    const values = Array.isArray(value) ? value : []
    if (isTextLineArray(path, values)) return <TextLineArrayEditor path={path} values={values} onChange={onChange} />
    const declaredItemType = metadata?.type.match(/^(?:List|Array|Set)\s*<\s*([^>]+)\s*>$/i)?.[1]
    const itemSchema = objectOf(resolved.items) ?? (normalizeMasterType(declaredItemType) ? { type: normalizeMasterType(declaredItemType) } : {})
    return <ArrayEditor {...inherited} path={path} values={values} itemSchema={itemSchema} onChange={onChange} />
  }
  const enumValues = Array.isArray(resolved.enum) ? resolved.enum : metadata?.enum
  if (enumValues?.length) return <select aria-label={path || '/'} value={JSON.stringify(value)} onChange={(event) => onChange(JSON.parse(event.target.value))}>
    {!enumValues.some((entry) => JSON.stringify(entry) === JSON.stringify(value)) && <option value={JSON.stringify(value)}>未定義: {String(value)}</option>}
    {enumValues.map((entry, index) => <option key={index} value={JSON.stringify(entry)}>{masterValueLabel(path, String(entry))}</option>)}
  </select>
  if (kind === 'boolean') return <label className="master-checkbox"><input aria-label={path || '/'} type="checkbox" checked={value === true} onChange={(event) => onChange(event.target.checked)} />{value === true ? 'true（有効）' : 'false（無効）'}</label>
  if (kind === 'number' || kind === 'integer') return <NumberEditor value={typeof value === 'number' ? value : 0} kind={kind} path={path} onChange={onChange} onInvalid={onInvalid} restoredText={invalidValues?.[path]}
    candidates={/^\/levels\/\d+\/nodes\/\d+$/.test(path) ? references.filter((entry) => entry.kind === 'node') : undefined} />
  if (kind === 'null') return <span className="master-muted">null（値なし）</span>
  const parentReference = (/\/\d+$/.test(path) || path.endsWith('/ref')) ? fieldForPath(fields, path.slice(0, path.lastIndexOf('/')))?.reference : undefined
  const progressionSigilReference = /^\/levels\/\d+\/(skills|passives)\/\d+\/sigils\/\d+\/item$/.test(path)
  const spawnerMobReference = /^\/spawnMobs\/\d+\/mobId$/.test(path)
  const referenceKind = metadata?.reference ?? parentReference ?? masterValueReferenceKind(path) ?? (spawnerMobReference ? 'mob' : path.endsWith('/ref') ? ({ caughtItemId: 'item', lootTableId: 'loot_table', worldId: 'world', rulesId: 'fishing_rules', fishId: 'fishing_fish' } as Record<string, string>)[path.split('/').at(-2) ?? ''] : undefined)
  const fishingReferencePrefix = path.endsWith('/ref') && referenceKind && ['item', 'loot_table', 'world', 'fishing_rules', 'fishing_fish'].includes(referenceKind)
    ? referenceKind + ':' : undefined
  const enchantReferencePrefix = path === '/orb/effect/enchantMasterId' && referenceKind === 'enchant' ? 'enchant:' : undefined
  const spawnerMobReferencePrefix = spawnerMobReference && referenceKind === 'mob' ? 'mob:' : undefined
  if (path === '/icon' && typeof value === 'string') return <MaterialIconInput value={value} onChange={onChange} ariaLabel={path} />
  return <StringEditor path={path} label={masterFieldTitle(masterValueFieldKey(path), metadata?.label)} value={typeof value === 'string' ? value : String(value ?? '')} onChange={onChange} references={references} referenceKind={referenceKind}
    referencePrefix={enchantReferencePrefix ?? fishingReferencePrefix ?? spawnerMobReferencePrefix ?? (typeof resolved.pattern === 'string' ? resolved.pattern.match(/^\^(item|skill):/)?.[0].slice(1) : undefined)}
    referenceFilter={progressionSigilReference ? isSigilReference : path.endsWith('/caughtItemId/ref') ? isCaughtItemReference : undefined} />
}

function ObjectEditor({ value, onChange, fields, references, schema, rootSchema, path, onInvalid, invalidValues, onNavigate, navigationDisabled }: Omit<MasterDataFormProps, 'value'> & { value: JsonObject; schema: JsonObject; rootSchema: JsonObject; path: string }) {
  const [newKey, setNewKey] = useState('')
  const [newType, setNewType] = useState<ValueType>('string')
  const [addError, setAddError] = useState('')
  const properties = objectOf(schema.properties) ?? {}
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : [])
  const childSchema = (key: string) => objectOf(properties[key]) ?? objectOf(schema.additionalProperties) ?? {}
  const metadataChildren = fields.filter((field) => {
    const fieldPath = field.path.startsWith('/') ? field.path : `/${field.path.replaceAll('.', '/').replaceAll('[]', '/*')}`
    const pattern = fieldPath.split('/').slice(0, -1).join('/')
    return fieldPath.split('/').at(-1) !== '*' && (pattern === path || pattern.split('/').every((part, index) => part === '*' || part === path.split('/')[index]) && pattern.split('/').length === path.split('/').length)
  })
  const optionalKeys = [...new Set([...Object.keys(properties), ...metadataChildren.map((field) => field.path.split('/').at(-1)?.replaceAll('~1', '/').replaceAll('~0', '~') ?? field.key)])].filter((key) => !Object.hasOwn(value, key))
  const add = (key: string, type = newType) => {
    if (!key.trim() || Object.hasOwn(value, key)) { setAddError('キーが空、または同名キーが存在します。'); return }
    const field = fieldForPath(fields, `${path}/${pointerKey(key)}`)
    onChange({ ...value, [key]: documentedFieldDefault(field, childSchema(key), type, rootSchema) })
    setNewKey(''); setAddError('')
  }
  return <div className="master-object">
    {Object.entries(value).map(([key, child]) => {
      if (child === undefined) return null
      const childPath = `${path}/${pointerKey(key)}`
      const field = fieldForPath(fields, childPath)
      const definition = childSchema(key)
      const isRequired = required.has(key) || field?.required === true
      const structured = Array.isArray(child) || objectOf(child)
      const textLines = isTextLineArray(childPath, child)
      const label = masterFieldTitle(key, definition.title ?? field?.label)
      const description = String(definition.description ?? field?.description ?? '')
      const declaredType = normalizeMasterType(definition.type ?? field?.type)
      const currentType = valueType(child, definition, field)
      const canChangeType = !declaredType || declaredType !== currentType
      const content = <>
        <div className="master-field-heading"><strong>{label}</strong><code>{key}</code><span className="master-type">{typeLabels[valueType(child, definition, field)]}</span>{isRequired && <b className="master-required">必須</b>}
          <span className="master-spacer" />
          <div className="master-field-inline-actions" role="group" aria-label={`${label}（${key}）の操作`}>
          {canChangeType && <select disabled={navigationDisabled} className="master-type-select" aria-label={`${childPath} の型`} value={valueType(child)} onChange={(event) => {
            const nextType = event.target.value as ValueType
            if ((structured || typeof child === 'string' && child) && !window.confirm(`「${key}」の型を変更して現在の値を置き換えますか？`)) return
            onChange({ ...value, [key]: masterDefault({}, nextType) })
          }}>{types.map((type) => <option value={type} key={type}>{typeLabels[type]}</option>)}</select>}
          <button disabled={navigationDisabled} type="button" className="button subtle danger compact" aria-label={`${childPath} を削除`} onClick={() => {
            if (isRequired && !window.confirm(`「${key}」は必須項目です。削除しますか？（保存前の検証で確認されます）`)) return
            onChange(Object.fromEntries(Object.entries(value).filter(([entry]) => entry !== key)))
          }}>削除</button></div></div>
        {description && (description.length > 120 || description.includes('\n')
          ? <details className="master-field-help"><summary>{label}の詳しい説明</summary><p className="master-field-description">{description}</p></details>
          : <p className="master-field-description">{description}</p>)}
        {declaredType && declaredType !== currentType && <p className="master-field-description">定義上の型: {typeLabels[declaredType]}。現在の値の型で表示しています。型を変更するか原稿で修正できます。</p>}
        {structured && onNavigate && !textLines ? <button type="button" className="master-node-link" aria-label={childPath + ' を開く'} disabled={navigationDisabled} onClick={() => onNavigate(childPath)}><span><MinecraftText value={summarizeMasterValue(child)} /></span><b>{label}を編集 →</b></button>
          : <MasterDataForm fields={fields} references={references} schema={definition} rootSchema={rootSchema} value={child} path={childPath} onInvalid={onInvalid} invalidValues={invalidValues} onNavigate={onNavigate} navigationDisabled={navigationDisabled} onChange={(next) => onChange({ ...value, [key]: next })} />}
      </>
      return structured && !onNavigate ? <details open key={key} className="master-field master-field-wide"><summary>{label} <code>{key}</code> <small>{Array.isArray(child) ? child.length + ' 件' : Object.keys(objectOf(child) ?? {}).length + ' 項目'}</small></summary>{content}</details>
        : <div key={key} className={'master-field ' + (structured || isDescriptionField(childPath) || typeof child === 'string' && child.includes('\n') || childPath === '/icon' ? 'master-field-wide' : 'master-field-scalar')}>{content}</div>
    })}
    <details className="master-add-field"><summary>{masterFieldLabel(textFieldName(path) ?? '') || '基本情報'}に項目を追加</summary><div className="master-add-field-body">
      {optionalKeys.length > 0 && <label>定義済みの項目を追加<select aria-label={`${path || '/'} 定義済みの項目を追加`} value="" onChange={(event) => { if (event.target.value) add(event.target.value) }}><option value="">項目を選択…</option>{optionalKeys.map((key) => {
        const field = fieldForPath(fields, `${path}/${pointerKey(key)}`)
        return <option key={key} value={key}>{masterFieldTitle(key, childSchema(key).title ?? field?.label)} · {key}{required.has(key) || field?.required ? '（必須）' : ''}</option>
      })}</select></label>}
      <div className="master-add-row"><input aria-label={`${path || '/'} 新しいキー`} value={newKey} placeholder="任意のキーを追加" onChange={(event) => setNewKey(event.target.value)} /><select aria-label={`${path || '/'} 追加する型`} value={newType} onChange={(event) => setNewType(event.target.value as ValueType)}>{types.map((type) => <option value={type} key={type}>{typeLabels[type]}</option>)}</select><button type="button" className="button" onClick={() => add(newKey)}>＋ キー</button></div>
      {addError && <p role="alert" className="error-message">{addError}</p>}
    </div></details>
  </div>
}

function TextLineArrayEditor({ values, path, onChange }: { values: string[]; path: string; onChange: (value: JsonValue) => void }) {
  return <div className="master-line-editor">
    <textarea className="master-multiline-input" aria-label={path || '/'} rows={Math.min(12, Math.max(5, values.length + 2))}
      value={values.join('\n')} onChange={(event) => {
        const text = event.target.value.replace(/\r\n?/g, '\n')
        onChange(text === '' ? [] : text.split('\n'))
      }} />
    <small className="master-line-guide">1 行が YAML の配列 1 要素になります。空行は空文字の要素、すべて消すと空配列になります。</small>
    {values.some((line) => line.includes('\n')) && <small className="master-line-guide">既存の要素内に改行があります。編集するとその改行も別の要素に分かれます。</small>}
    <div className="master-text-preview master-line-preview" aria-label={`${path || '/'} 表示プレビュー`}>
      <strong>表示プレビュー</strong>
      {values.length ? values.map((line, index) => <div className="master-line-preview-row" key={index}><code>{index + 1}</code><span><MinecraftText value={line || ' '} /></span></div>)
        : <span className="master-muted">表示する行がありません。</span>}
    </div>
  </div>
}

function ArrayEditor({ values, itemSchema, path, onChange, ...inherited }: Omit<MasterDataFormProps, 'value' | 'schema'> & { values: JsonValue[]; itemSchema: JsonObject; path: string }) {
  const [newType, setNewType] = useState<ValueType>(typeof itemSchema.type === 'string' && types.includes(itemSchema.type as ValueType) ? itemSchema.type as ValueType : 'string')
  const [page, setPage] = useState(0)
  const pageSize = 25
  const paged = Boolean(inherited.onNavigate) && values.every((entry) => entry !== null && typeof entry === 'object')
  const pageCount = Math.max(1, Math.ceil(values.length / pageSize))
  const currentPage = Math.min(page, pageCount - 1)
  const offset = paged ? currentPage * pageSize : 0
  const visible = paged ? values.slice(offset, offset + pageSize) : values
  const isFishDistribution = /\/levelRanges\/\d+\/fish$/.test(path)
  const chanceTotal = isFishDistribution ? values.reduce<number>((total, entry) => {
    const chance = objectOf(entry)?.chancePercent
    return total + (typeof chance === 'number' && Number.isFinite(chance) ? chance : 0)
  }, 0) : 0
  const move = (index: number, offset: number) => {
    const next = [...values]
    ;[next[index], next[index + offset]] = [next[index + offset], next[index]]
    onChange(next)
  }
  return <div className="master-array">{isFishDistribution && <p className={Math.abs(chanceTotal - 100) <= 0.000001 ? 'master-muted' : 'error-message'} role="status">出現確率の合計: {chanceTotal.toLocaleString('ja-JP', { maximumFractionDigits: 6 })}% / 100%</p>}{visible.map((entry, visibleIndex) => {
    const index = offset + visibleIndex
    const structured = entry !== null && typeof entry === 'object'
    return <div className={'master-array-entry ' + (paged && structured ? 'master-array-summary-row' : '')} key={index}>
    <div className="master-array-actions"><code>[{index}]</code><button type="button" className="button compact" aria-label={`${path}/${index} 上へ`} disabled={index === 0 || inherited.navigationDisabled} onClick={() => move(index, -1)}>↑</button><button type="button" className="button compact" aria-label={`${path}/${index} 下へ`} disabled={index === values.length - 1 || inherited.navigationDisabled} onClick={() => move(index, 1)}>↓</button><button type="button" className="button compact" disabled={inherited.navigationDisabled} onClick={() => onChange([...values.slice(0, index + 1), structuredClone(entry), ...values.slice(index + 1)])}>複製</button><button type="button" disabled={inherited.navigationDisabled} className="button compact danger subtle" aria-label={`${path}/${index} を削除`} onClick={() => onChange(values.filter((_, item) => item !== index))}>削除</button></div>
    {structured && inherited.onNavigate ? <div className="master-node-link"><span><MinecraftText value={summarizeMasterValue(entry)} /></span><button type="button" className="button compact" aria-label={path + '/' + index + ' を開く'} disabled={inherited.navigationDisabled} onClick={() => inherited.onNavigate?.(path + '/' + index)}>編集 →</button></div>
      : <MasterDataForm {...inherited} path={path + '/' + index} value={entry} schema={itemSchema} onChange={(next) => onChange(values.map((current, item) => item === index ? next : current))} />}
  </div>})}
    {paged && pageCount > 1 && <div className="master-pagination"><button type="button" className="button compact" disabled={currentPage === 0 || inherited.navigationDisabled} onClick={() => setPage(currentPage - 1)}>前の要素</button><span>{offset + 1}–{Math.min(offset + pageSize, values.length)} / {values.length}件</span><button type="button" className="button compact" disabled={currentPage + 1 >= pageCount || inherited.navigationDisabled} onClick={() => setPage(currentPage + 1)}>次の要素</button></div>}
    <div className="master-add-row">{!itemSchema.type && <select aria-label={path + ' 配列に追加する型'} value={newType} onChange={(event) => setNewType(event.target.value as ValueType)}>{types.map((type) => <option key={type} value={type}>{typeLabels[type]}</option>)}</select>}<button type="button" className="button" onClick={() => onChange([...values, masterDefault(itemSchema, newType, inherited.rootSchema)])}>＋ 要素を追加</button><span className="master-muted">{values.length} 件</span></div></div>
}

function numberInputError(text: string, kind: 'number' | 'integer'): string {
  const number = Number(text)
  const invalid = !text.trim() || !Number.isFinite(number) || kind === 'integer' && !Number.isInteger(number)
  return invalid ? kind === 'integer' ? '有限の整数を入力してください。' : '有限の数値を入力してください。' : ''
}

function NumberEditor({ value, kind, path, onChange, onInvalid, restoredText, candidates }: { value: number; kind: 'number' | 'integer'; path: string; onChange: (value: JsonValue) => void; onInvalid?: MasterDataFormProps['onInvalid']; restoredText?: string; candidates?: MasterReference[] }) {
  const listId = useId()
  const [text, setText] = useState(restoredText ?? String(value))
  const [error, setError] = useState(() => numberInputError(restoredText ?? String(value), kind))
  useEffect(() => {
    const nextText = restoredText ?? String(value)
    const message = numberInputError(nextText, kind)
    setText(nextText); setError(message)
    if (message) onInvalid?.(path, message, nextText)
    else onInvalid?.(path, null)
  }, [value, path, kind, onInvalid, restoredText])
  return <div><input type="text" inputMode="decimal" list={candidates?.length ? listId : undefined} aria-label={path || '/'} aria-invalid={Boolean(error)} value={text} onChange={(event) => {
    setText(event.target.value)
    const message = numberInputError(event.target.value, kind)
    setError(message)
    if (message) onInvalid?.(path, message, event.target.value)
    else { onInvalid?.(path, null); onChange(Number(event.target.value)) }
  }} />{Boolean(candidates?.length) && <datalist id={listId}>{candidates?.map((entry) =>
    <option key={entry.path} value={entry.value} label={stripMinecraftFormatting(entry.label ?? entry.value)} />
  )}</datalist>}{error && <small className="error-message">{error}</small>}</div>
}

function StringEditor({ value, path, label, onChange, references, referenceKind, referencePrefix, referenceFilter }: { value: string; path: string; label: string; onChange: (value: JsonValue) => void; references: MasterReference[]; referenceKind?: string; referencePrefix?: string; referenceFilter?: (entry: MasterReference) => boolean }) {
  const listId = useId()
  const [candidatesOpen, setCandidatesOpen] = useState(false)
  const [candidateSearch, setCandidateSearch] = useState('')
  const [candidatePage, setCandidatePage] = useState(0)
  const candidateSummary = useRef<HTMLElement>(null)
  const candidateInput = useRef<HTMLInputElement>(null)
  const candidateList = useRef<HTMLDivElement>(null)
  const focusAfterCandidatePage = useRef(false)
  const candidates = useMemo(() => {
    if (!referenceKind) return []
    const catalog = referenceKind === 'status' ? masterStatusSuggestions : referenceKind === 'tag' ? masterTagSuggestions : []
    const catalogReferences: MasterReference[] = catalog.map((entry) => ({ ...entry, kind: referenceKind, path: '', pointer: '' }))
    return [...new Map([...references, ...catalogReferences].filter((entry) => entry.kind === referenceKind && (!referenceFilter || referenceFilter(entry))).map((entry) => {
      const candidate = referencePrefix && !entry.value.startsWith(referencePrefix) ? { ...entry, value: referencePrefix + entry.value } : entry
      const knownLabel = masterValueLabel(referenceKind, candidate.value)
      return [candidate.value, knownLabel !== candidate.value ? { ...candidate, label: knownLabel } : candidate] as const
    })).values()]
  }, [referenceFilter, referenceKind, referencePrefix, references])
  const candidateIndex = useMemo(() => createMasterCandidateSearch(candidates), [candidates])
  const search = stripMinecraftFormatting(value).toLocaleLowerCase()
  const visibleCandidates = useMemo(() => candidateIndex.find(search, 100), [candidateIndex, search])
  const candidateQuery = candidateSearch.trim().toLocaleLowerCase()
  const matchingCandidates = useMemo(() => candidatesOpen ? candidateIndex.find(candidateQuery) : [], [candidateIndex, candidateQuery, candidatesOpen])
  const lastCandidatePage = Math.max(0, Math.ceil(matchingCandidates.length / 50) - 1)
  const currentCandidatePage = Math.min(candidatePage, lastCandidatePage)
  useEffect(() => {
    if (candidatesOpen) setCandidatePage((page) => Math.min(page, lastCandidatePage))
  }, [lastCandidatePage, candidatesOpen])
  useEffect(() => {
    if (candidatesOpen && !candidateInput.current?.matches(':disabled')) candidateInput.current?.focus()
  }, [candidatesOpen])
  useEffect(() => {
    if (!focusAfterCandidatePage.current) return
    focusAfterCandidatePage.current = false
    if (!candidatesOpen || candidateInput.current?.matches(':disabled')) return
    if (candidateList.current) candidateList.current.scrollTop = 0
    candidateList.current?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
  }, [currentCandidatePage, matchingCandidates, candidatesOpen])
  const skipCandidateKey = (event: KeyboardEvent<HTMLElement>) => event.nativeEvent.isComposing || event.keyCode === 229
    || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.currentTarget.matches(':disabled')
  const focusCandidate = (index: number) => candidateList.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')[index]?.focus()
  const onCandidateSearchKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (skipCandidateKey(event)) return
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopPropagation()
      focusAfterCandidatePage.current = false
      setCandidatesOpen(false)
      candidateSummary.current?.focus()
    } else if ((event.key === 'ArrowDown' || event.key === 'Enter') && matchingCandidates.length > 0) {
      event.preventDefault()
      focusCandidate(0)
    }
  }
  const onCandidateKey = (event: KeyboardEvent<HTMLButtonElement>, index: number, count: number) => {
    if (skipCandidateKey(event)) return
    if (event.key === 'Escape') {
      event.preventDefault(); event.stopPropagation()
      candidateInput.current?.focus()
      return
    }
    const next = event.key === 'ArrowDown' ? (index + 1) % count
      : event.key === 'ArrowUp' ? (index - 1 + count) % count
        : event.key === 'Home' ? 0 : event.key === 'End' ? count - 1 : -1
    if (next >= 0) { event.preventDefault(); focusCandidate(next) }
  }
  const changeCandidatePage = (page: number) => {
    focusAfterCandidatePage.current = true
    setCandidatePage(page)
  }
  const candidatePageEntries = candidatesOpen ? matchingCandidates.slice(currentCandidatePage * 50, (currentCandidatePage + 1) * 50) : []
  const selected = candidateIndex.get(value)
  const preview = selected?.label ?? masterValueLabel(path, value)
  const formatted = preview !== stripMinecraftFormatting(preview)
  const description = isDescriptionField(path)
  const hexColor = textFieldName(path)?.toLowerCase() === 'color' && /^#[0-9a-f]{6}$/i.test(value)
  return <div>{description || value.includes('\n') ? <textarea className="master-multiline-input" aria-label={path || '/'} rows={Math.min(12, Math.max(4, value.split('\n').length + 1))} value={value} onChange={(event) => onChange(event.target.value)} /> : <input aria-label={path || '/'} list={candidates.length ? listId : undefined} value={value} onChange={(event) => onChange(event.target.value)} />}
    {description && <small className="master-line-guide">この欄は 1 つの文字列として保存します。詳細を行ごとに分ける場合は lore を使います。</small>}
    {(formatted || description) && <div className="master-text-preview master-scalar-preview" aria-label={`${path || '/'} 表示プレビュー`}><strong>表示プレビュー</strong><span><MinecraftText value={preview || ' '} /></span></div>}
    {!formatted && !description && preview !== value && <div className="master-value-label" aria-label={`${path || '/'} 選択中の名前`}>{preview}</div>}
    {hexColor && <output className="master-color-preview" aria-label={`${path || '/'} 色プレビュー`}><span className="master-color-swatch" style={{ backgroundColor: value }} /><code>{value.toUpperCase()}</code></output>}
    {candidates.length > 0 && <><datalist id={listId}>{visibleCandidates.map((entry, index) => <option key={`${entry.value}-${index}`} value={entry.value}>{candidateIndex.label(entry)}</option>)}</datalist><small className="master-muted">参照候補 {candidates.length} 件。入力欄の補完は最大100件です。自由入力も可能です。</small>
      <details className="master-colored-candidates" open={candidatesOpen}><summary ref={candidateSummary} aria-label={`${label}（${path}）の候補を選択`} onClick={(event) => { event.preventDefault(); if (event.currentTarget.closest('fieldset:disabled')) return; focusAfterCandidatePage.current = false; setCandidatesOpen((open) => !open) }}>{label}の候補を選択</summary>{candidatesOpen && <div className="master-candidate-browser">
        <div className="master-candidate-current">選択中: {value ? <><MinecraftText value={selected?.label ?? value} /> <code>{value}</code></> : 'なし'}</div>
        <label className="master-candidate-search">{label}の候補を検索 <small>{path || '/'}</small><input ref={candidateInput} type="search" aria-label={`${path || '/'} 候補を検索`} value={candidateSearch} placeholder="ID または日本語名" onChange={(event) => { setCandidateSearch(event.target.value); setCandidatePage(0) }} onKeyDown={onCandidateSearchKey} /></label>
        <small className="master-muted">↓ / Enterで候補へ、↑↓で移動、Enterで選択。Escapeで検索欄へ戻り、もう一度押すと閉じます。</small>
        <p className="master-candidate-count" role="status">{matchingCandidates.length} 件 / 全 {candidates.length} 件{matchingCandidates.length > 0 && ` · ${currentCandidatePage + 1} / ${lastCandidatePage + 1} ページ`}</p>
        <div ref={candidateList} className="master-candidate-list">{candidatePageEntries.map((entry, index) => <button type="button" className="master-candidate" key={entry.value} aria-current={entry.value === value ? 'true' : undefined} onKeyDown={(event) => onCandidateKey(event, index, candidatePageEntries.length)} onClick={(event) => { if (!event.currentTarget.matches(':disabled')) onChange(entry.value) }}><MinecraftText value={entry.label ?? entry.value} /><code>{entry.value}</code></button>)}{!matchingCandidates.length && <p className="master-muted">一致する候補がありません。</p>}</div>
        {lastCandidatePage > 0 && <div className="master-candidate-pages"><button type="button" className="button" disabled={currentCandidatePage === 0} onClick={(event) => { if (!event.currentTarget.matches(':disabled')) changeCandidatePage(currentCandidatePage - 1) }}>前へ</button><span>{currentCandidatePage + 1} / {lastCandidatePage + 1} ページ</span><button type="button" className="button" disabled={currentCandidatePage === lastCandidatePage} onClick={(event) => { if (!event.currentTarget.matches(':disabled')) changeCandidatePage(currentCandidatePage + 1) }}>次へ</button></div>}
      </div>}</details>
    </>}
  </div>
}
