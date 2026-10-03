import { useEffect, useId, useState } from 'react'
import type { JsonObject, JsonValue } from '../types/editor'
import type { MasterField, MasterReference } from '../types/masterData'
import { MaterialIconInput } from './MaterialIconInput'
import { summarizeMasterValue } from '../data/masterDataNavigation'
import { MinecraftText } from './MinecraftText'
import { stripMinecraftFormatting } from '../utils/minecraft'

type ValueType = 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array' | 'null'
const typeLabels: Record<ValueType, string> = {
  string: '文字列', number: '数値', integer: '整数', boolean: '真偽値', object: 'オブジェクト / Map', array: '配列 / List', null: 'null',
}
const types = Object.keys(typeLabels) as ValueType[]
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

function resolveSchema(schema: JsonObject, root: JsonObject): JsonObject {
  if (typeof schema.$ref !== 'string' || !schema.$ref.startsWith('#/')) return schema
  let target: JsonValue = root
  for (const part of schema.$ref.slice(2).split('/')) {
    target = objectOf(target)?.[part.replaceAll('~1', '/').replaceAll('~0', '~')] ?? null
  }
  const result: JsonObject = { ...objectOf(target), ...schema }
  delete result.$ref
  return result
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
  onInvalid?: (path: string, message: string | null) => void
  onNavigate?: (path: string) => void
  navigationDisabled?: boolean
}

export function MasterDataForm({ value, onChange, fields, references = [], schema = {}, rootSchema = schema, path = '', onInvalid, onNavigate, navigationDisabled }: MasterDataFormProps) {
  const resolved = resolveSchema(schema, rootSchema)
  const metadata = fieldForPath(fields, path)
  const kind = valueType(value, resolved, metadata)
  const inherited = { fields, references, rootSchema, onInvalid, onNavigate, navigationDisabled }
  const unions = Array.isArray(resolved.oneOf ?? resolved.anyOf) ? (resolved.oneOf ?? resolved.anyOf) as JsonValue[] : []
  const selectedUnion = Math.max(0, unions.findIndex((choice) => {
    const properties = objectOf(objectOf(choice)?.properties)
    return properties && Object.entries(properties).some(([key, definition]) => objectOf(definition)?.const !== undefined && objectOf(definition)?.const === objectOf(value)?.[key])
  }))
  if (unions.length) {
    return <div className="master-union"><label>定義の種類<select aria-label={`${path || '/'} 定義の種類`} value={selectedUnion} onChange={(event) => {
      const next = objectOf(unions[Number(event.target.value)]) ?? {}
      const defaults = masterDefault(next, 'object', rootSchema)
      const merged = objectOf(value) && objectOf(defaults) ? { ...objectOf(value), ...objectOf(defaults) } : defaults
      const properties = objectOf(next.properties) ?? {}
      if (objectOf(merged)) for (const [key, definition] of Object.entries(properties)) {
        if (objectOf(definition)?.const !== undefined) (merged as JsonObject)[key] = objectOf(definition)!.const
      }
      onChange(merged)
    }}>{unions.map((choice, index) => <option key={index} value={index}>{String(objectOf(choice)?.title ?? `種類 ${index + 1}`)}</option>)}</select></label>
      <MasterDataForm {...inherited} path={path} schema={objectOf(unions[selectedUnion]) ?? {}} value={value} onChange={onChange} />
    </div>
  }
  if (kind === 'object') {
    return <ObjectEditor {...inherited} path={path} schema={resolved} value={objectOf(value) ?? {}} onChange={onChange} />
  }
  if (kind === 'array') {
    const values = Array.isArray(value) ? value : []
    const declaredItemType = metadata?.type.match(/^(?:List|Array|Set)\s*<\s*([^>]+)\s*>$/i)?.[1]
    const itemSchema = objectOf(resolved.items) ?? (normalizeMasterType(declaredItemType) ? { type: normalizeMasterType(declaredItemType) } : {})
    return <ArrayEditor {...inherited} path={path} values={values} itemSchema={itemSchema} onChange={onChange} />
  }
  const enumValues = Array.isArray(resolved.enum) ? resolved.enum : metadata?.enum
  if (enumValues?.length) return <select aria-label={path || '/'} value={JSON.stringify(value)} onChange={(event) => onChange(JSON.parse(event.target.value))}>
    {!enumValues.some((entry) => JSON.stringify(entry) === JSON.stringify(value)) && <option value={JSON.stringify(value)}>未定義: {String(value)}</option>}
    {enumValues.map((entry, index) => <option key={index} value={JSON.stringify(entry)}>{String(entry)}</option>)}
  </select>
  if (kind === 'boolean') return <label className="master-checkbox"><input aria-label={path || '/'} type="checkbox" checked={value === true} onChange={(event) => onChange(event.target.checked)} />{value === true ? 'true（有効）' : 'false（無効）'}</label>
  if (kind === 'number' || kind === 'integer') return <NumberEditor value={typeof value === 'number' ? value : 0} kind={kind} path={path} onChange={onChange} onInvalid={onInvalid} />
  if (kind === 'null') return <span className="master-muted">null（値なし）</span>
  const parentReference = /\/\d+$/.test(path) ? fieldForPath(fields, path.slice(0, path.lastIndexOf('/')))?.reference : undefined
  if (path === '/icon' && typeof value === 'string') return <MaterialIconInput value={value} onChange={onChange} ariaLabel={path} />
  return <StringEditor path={path} value={typeof value === 'string' ? value : String(value ?? '')} onChange={onChange} references={references} referenceKind={metadata?.reference ?? parentReference}
    referencePrefix={typeof resolved.pattern === 'string' && resolved.pattern.startsWith('^item:') ? 'item:' : undefined} />
}

function ObjectEditor({ value, onChange, fields, references, schema, rootSchema, path, onInvalid, onNavigate, navigationDisabled }: Omit<MasterDataFormProps, 'value'> & { value: JsonObject; schema: JsonObject; rootSchema: JsonObject; path: string }) {
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
      const label = String(definition.title ?? field?.label ?? key)
      const description = String(definition.description ?? field?.description ?? '')
      const declaredType = normalizeMasterType(definition.type ?? field?.type)
      const currentType = valueType(child, definition, field)
      const canChangeType = !declaredType || declaredType !== currentType
      const content = <>
        <div className="master-field-heading"><strong>{label}</strong><code>{key}</code><span className="master-type">{typeLabels[valueType(child, definition, field)]}</span>{isRequired && <b className="master-required">必須</b>}
          <span className="master-spacer" />
          <details className="master-field-actions"><summary aria-label={childPath + ' の操作'}>操作</summary><div className="master-field-actions-body">
          {canChangeType && <select className="master-type-select" aria-label={`${childPath} の型`} value={valueType(child)} onChange={(event) => {
            const nextType = event.target.value as ValueType
            if ((structured || typeof child === 'string' && child) && !window.confirm(`「${key}」の型を変更して現在の値を置き換えますか？`)) return
            onChange({ ...value, [key]: masterDefault({}, nextType) })
          }}>{types.map((type) => <option value={type} key={type}>{typeLabels[type]}</option>)}</select>}
          <button type="button" className="button subtle danger compact" aria-label={`${childPath} を削除`} onClick={() => {
            if (isRequired && !window.confirm(`「${key}」は必須項目です。削除しますか？（保存前の検証で確認されます）`)) return
            onChange(Object.fromEntries(Object.entries(value).filter(([entry]) => entry !== key)))
          }}>削除</button></div></details></div>
        {description && <details className="master-field-help"><summary>項目の説明</summary><p className="master-field-description">{description}</p></details>}
        {declaredType && declaredType !== currentType && <p className="master-field-description">定義上の型: {typeLabels[declaredType]}。現在の値の型で表示しています。型を変更するか原稿で修正できます。</p>}
        {structured && onNavigate ? <div className="master-node-link"><span><MinecraftText value={summarizeMasterValue(child)} /></span><button type="button" className="button compact" aria-label={childPath + ' を開く'} disabled={navigationDisabled} onClick={() => onNavigate(childPath)}>開く →</button></div>
          : <MasterDataForm fields={fields} references={references} schema={definition} rootSchema={rootSchema} value={child} path={childPath} onInvalid={onInvalid} onNavigate={onNavigate} navigationDisabled={navigationDisabled} onChange={(next) => onChange({ ...value, [key]: next })} />}
      </>
      return structured && !onNavigate ? <details open key={key} className="master-field master-field-wide"><summary>{label} <code>{key}</code> <small>{Array.isArray(child) ? child.length + ' 件' : Object.keys(objectOf(child) ?? {}).length + ' 項目'}</small></summary>{content}</details>
        : <div key={key} className={'master-field ' + (structured || typeof child === 'string' && child.includes('\n') || childPath === '/icon' ? 'master-field-wide' : 'master-field-scalar')}>{content}</div>
    })}
    <details className="master-add-field"><summary>項目を追加</summary><div className="master-add-field-body">
      {optionalKeys.length > 0 && <label>定義済みの項目を追加<select aria-label={`${path || '/'} 定義済みの項目を追加`} value="" onChange={(event) => { if (event.target.value) add(event.target.value) }}><option value="">項目を選択…</option>{optionalKeys.map((key) => {
        const field = fieldForPath(fields, `${path}/${pointerKey(key)}`)
        return <option key={key} value={key}>{String(childSchema(key).title ?? field?.label ?? key)} · {key}{required.has(key) || field?.required ? '（必須）' : ''}</option>
      })}</select></label>}
      <div className="master-add-row"><input aria-label={`${path || '/'} 新しいキー`} value={newKey} placeholder="任意のキーを追加" onChange={(event) => setNewKey(event.target.value)} /><select aria-label={`${path || '/'} 追加する型`} value={newType} onChange={(event) => setNewType(event.target.value as ValueType)}>{types.map((type) => <option value={type} key={type}>{typeLabels[type]}</option>)}</select><button type="button" className="button" onClick={() => add(newKey)}>＋ キー</button></div>
      {addError && <p role="alert" className="error-message">{addError}</p>}
    </div></details>
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
  const move = (index: number, offset: number) => {
    const next = [...values]
    ;[next[index], next[index + offset]] = [next[index + offset], next[index]]
    onChange(next)
  }
  return <div className="master-array">{visible.map((entry, visibleIndex) => {
    const index = offset + visibleIndex
    const structured = entry !== null && typeof entry === 'object'
    return <div className={'master-array-entry ' + (paged && structured ? 'master-array-summary-row' : '')} key={index}>
    <div className="master-array-actions"><code>[{index}]</code><button type="button" className="button compact" aria-label={`${path}/${index} 上へ`} disabled={index === 0} onClick={() => move(index, -1)}>↑</button><button type="button" className="button compact" aria-label={`${path}/${index} 下へ`} disabled={index === values.length - 1} onClick={() => move(index, 1)}>↓</button><button type="button" className="button compact" onClick={() => onChange([...values.slice(0, index + 1), structuredClone(entry), ...values.slice(index + 1)])}>複製</button><button type="button" className="button compact danger subtle" aria-label={`${path}/${index} を削除`} onClick={() => onChange(values.filter((_, item) => item !== index))}>削除</button></div>
    {structured && inherited.onNavigate ? <div className="master-node-link"><span><MinecraftText value={summarizeMasterValue(entry)} /></span><button type="button" className="button compact" aria-label={path + '/' + index + ' を開く'} disabled={inherited.navigationDisabled} onClick={() => inherited.onNavigate?.(path + '/' + index)}>編集 →</button></div>
      : <MasterDataForm {...inherited} path={path + '/' + index} value={entry} schema={itemSchema} onChange={(next) => onChange(values.map((current, item) => item === index ? next : current))} />}
  </div>})}
    {paged && pageCount > 1 && <div className="master-pagination"><button type="button" className="button compact" disabled={currentPage === 0 || inherited.navigationDisabled} onClick={() => setPage(currentPage - 1)}>前の要素</button><span>{offset + 1}–{Math.min(offset + pageSize, values.length)} / {values.length}件</span><button type="button" className="button compact" disabled={currentPage + 1 >= pageCount || inherited.navigationDisabled} onClick={() => setPage(currentPage + 1)}>次の要素</button></div>}
    <div className="master-add-row">{!itemSchema.type && <select aria-label={path + ' 配列に追加する型'} value={newType} onChange={(event) => setNewType(event.target.value as ValueType)}>{types.map((type) => <option key={type} value={type}>{typeLabels[type]}</option>)}</select>}<button type="button" className="button" onClick={() => onChange([...values, masterDefault(itemSchema, newType, inherited.rootSchema)])}>＋ 要素を追加</button><span className="master-muted">{values.length} 件</span></div></div>
}

function NumberEditor({ value, kind, path, onChange, onInvalid }: { value: number; kind: 'number' | 'integer'; path: string; onChange: (value: JsonValue) => void; onInvalid?: MasterDataFormProps['onInvalid'] }) {
  const [text, setText] = useState(String(value))
  const [error, setError] = useState('')
  useEffect(() => { setText(String(value)); setError(''); onInvalid?.(path, null) }, [value, path, onInvalid])
  useEffect(() => () => onInvalid?.(path, null), [path, onInvalid])
  return <div><input type="text" inputMode="decimal" aria-label={path || '/'} aria-invalid={Boolean(error)} value={text} onChange={(event) => {
    setText(event.target.value)
    const number = Number(event.target.value)
    const invalid = !event.target.value.trim() || !Number.isFinite(number) || kind === 'integer' && !Number.isInteger(number)
    const message = invalid ? kind === 'integer' ? '有限の整数を入力してください。' : '有限の数値を入力してください。' : ''
    setError(message); onInvalid?.(path, message || null)
    if (!invalid) onChange(number)
  }} />{error && <small className="error-message">{error}</small>}</div>
}

function StringEditor({ value, path, onChange, references, referenceKind, referencePrefix }: { value: string; path: string; onChange: (value: JsonValue) => void; references: MasterReference[]; referenceKind?: string; referencePrefix?: string }) {
  const listId = useId()
  const candidates = referenceKind ? [...new Map(references.filter((entry) => entry.kind === referenceKind).map((entry) => {
    const candidate = referencePrefix && !entry.value.startsWith(referencePrefix) ? { ...entry, value: referencePrefix + entry.value } : entry
    return [candidate.value, candidate] as const
  })).values()] : []
  const search = stripMinecraftFormatting(value).toLocaleLowerCase()
  const visibleCandidates = candidates.filter((entry) => !search || entry.value.toLocaleLowerCase().includes(search) || stripMinecraftFormatting(entry.label ?? '').toLocaleLowerCase().includes(search)).slice(0, 100)
  const selected = candidates.find((entry) => entry.value === value)
  const preview = selected?.label ?? value
  const formatted = preview !== stripMinecraftFormatting(preview)
  return <div>{value.includes('\n') ? <textarea aria-label={path || '/'} rows={Math.min(12, value.split('\n').length + 1)} value={value} onChange={(event) => onChange(event.target.value)} /> : <input aria-label={path || '/'} list={candidates.length ? listId : undefined} value={value} onChange={(event) => onChange(event.target.value)} />}
    {formatted && <div className="master-text-preview" aria-label={`${path || '/'} 表示プレビュー`}><MinecraftText value={preview} /></div>}
    {candidates.length > 0 && <><datalist id={listId}>{visibleCandidates.map((entry, index) => <option key={`${entry.value}-${index}`} value={entry.value}>{stripMinecraftFormatting(entry.label ?? entry.value)}</option>)}</datalist><small className="master-muted">参照候補 {candidates.length} 件。ID / 名前で絞込（最大100件）。自由入力も可能です。</small>
      <details className="master-colored-candidates"><summary>名前と色を確認して選択</summary><div>{visibleCandidates.map((entry) => <button type="button" className="master-candidate" key={entry.value} onClick={() => onChange(entry.value)}><MinecraftText value={entry.label ?? entry.value} /><code>{entry.value}</code></button>)}{!visibleCandidates.length && <p className="master-muted">一致する候補がありません。</p>}</div></details>
    </>}
  </div>
}
