import type { FieldSuggestion, FieldSuggestionValue, JsonObject, JsonValue } from '../types/editor'
import { useEffect, useId, useRef, useState } from 'react'
import { SuggestionInput } from './SuggestionInput'
import { MaterialIconInput } from './MaterialIconInput'
import { masterFieldLabel } from '../data/masterDataPresentation'
import { masterUnionMatches, mergeMasterUnion, resolveMasterSchema as resolveSchema } from '../data/masterFormSchema'

interface SchemaFormProps {
  schema: JsonObject
  value: JsonValue
  onChange: (value: JsonValue) => void
  rootSchema?: JsonObject
  path?: string
  disabledPaths?: Set<string>
  suggestionsByPath?: Readonly<Record<string, readonly FieldSuggestionValue[]>>
  onInvalid?: (path: string, message: string | null) => void
  navigationDisabled?: boolean
  /** Absent optional fields are displayed as virtual defaults and must not block an untouched document. */
  validateInitial?: boolean
}

export function SchemaForm({
  schema,
  value,
  onChange,
  rootSchema = schema,
  path = '',
  disabledPaths = new Set(),
  suggestionsByPath = {},
  onInvalid,
  navigationDisabled = false,
  validateInitial = true,
}: SchemaFormProps) {
  const resolved = resolveSchema(schema, rootSchema)
  const union = asArray(resolved.oneOf ?? resolved.anyOf)
  if (union) {
    const selectedIndex = Math.max(0, union.findIndex((option) => optionMatches(option, value, rootSchema)))
    const selected = asObject(union[selectedIndex]) ?? {}
    return (
      <div className="schema-union">
        <label>
          種類
          <select
            aria-label={`${path || '/'} 定義の種類`}
            disabled={navigationDisabled || disabledPaths.has(path)}
            value={selectedIndex}
            onChange={(event) => {
              const next = asObject(union[Number(event.target.value)]) ?? {}
              onChange(mergeMasterUnion(value, defaultFromSchema(next, rootSchema), selected, next, rootSchema))
            }}
          >
            {union.map((option, index) => {
              const optionSchema = resolveSchema(asObject(option) ?? {}, rootSchema)
              const typeConst = asObject(asObject(optionSchema.properties)?.type)?.const
              const fallback = typeConst === 'skill'
                ? 'スキル効果'
                : typeConst === 'status'
                  ? 'ステータス効果'
                  : `候補 ${index + 1}`
              return <option key={index} value={index}>{String(optionSchema.title ?? fallback)}</option>
            })}
          </select>
        </label>
        <SchemaForm
          schema={selected}
          value={value}
          onChange={onChange}
          rootSchema={rootSchema}
          path={path}
          disabledPaths={disabledPaths}
          suggestionsByPath={suggestionsByPath}
          onInvalid={onInvalid}
          navigationDisabled={navigationDisabled}
          validateInitial={validateInitial}
        />
      </div>
    )
  }

  if (resolved.const !== undefined) return <input type="text" aria-label={path || '/'} readOnly disabled={disabledPaths.has(path)}
    value={typeof value === 'string' ? value : JSON.stringify(value)} />

  const enumValues = asArray(resolved.enum)
  if (enumValues) {
    return (
      <select
        disabled={disabledPaths.has(path)}
        value={String(value ?? '')}
        onChange={(event) => {
          const selected = enumValues.find((candidate) => String(candidate) === event.target.value)
          onChange(selected ?? event.target.value)
        }}
      >
        {enumValues.map((candidate) => <option key={String(candidate)} value={String(candidate)}>{String(candidate)}</option>)}
      </select>
    )
  }

  const type = schemaType(resolved, value)
  if (type === 'object') {
    const objectValue = asObject(value) ?? {}
    const properties = asObject(resolved.properties) ?? {}
    const required = new Set((asArray(resolved.required) ?? []).map(String))
    return (
      <div className="schema-object">
        {Object.entries(properties).map(([key, propertySchema]) => {
          const childSchema = asObject(propertySchema) ?? {}
          const childPath = `${path}/${key}`
          const childValue = Object.hasOwn(objectValue, key) ? objectValue[key] ?? null : defaultFromSchema(resolveSchema(childSchema, rootSchema), rootSchema)
          return (
            <label className="schema-field" key={key}>
              <span>
                {String(childSchema.title ?? masterFieldLabel(key))}{required.has(key) && <b aria-label="必須"> *</b>}
                {childSchema.description && <small>{String(childSchema.description)}</small>}
              </span>
              <SchemaForm
                schema={childSchema}
                value={childValue}
                onChange={(next) => onChange({ ...objectValue, [key]: next })}
                rootSchema={rootSchema}
                path={childPath}
                disabledPaths={disabledPaths}
                suggestionsByPath={suggestionsByPath}
                onInvalid={onInvalid}
                navigationDisabled={navigationDisabled}
                validateInitial={validateInitial && Object.hasOwn(objectValue, key)}
              />
            </label>
          )
        })}
      </div>
    )
  }

  if (type === 'array') {
    const values = Array.isArray(value) ? value : []
    const itemSchema = asObject(resolved.items) ?? {}
    return (
      <div className="schema-array">
        {values.map((item, index) => (
          <div className="schema-array-item" key={index}>
            <SchemaForm
              schema={itemSchema}
              value={item}
              onChange={(next) => {
                const copy = [...values]
                copy[index] = next
                onChange(copy)
              }}
              rootSchema={rootSchema}
              path={`${path}/${index}`}
              disabledPaths={disabledPaths}
              suggestionsByPath={suggestionsByPath}
              onInvalid={onInvalid}
              navigationDisabled={navigationDisabled}
              validateInitial={validateInitial}
            />
            <button
              type="button"
              className="button danger subtle"
              disabled={navigationDisabled || disabledPaths.has(path)}
              onClick={() => onChange(values.filter((_, itemIndex) => itemIndex !== index))}
            >
              削除
            </button>
          </div>
        ))}
        <button
          type="button"
          className="button subtle"
          disabled={navigationDisabled || disabledPaths.has(path)}
          onClick={() => onChange([...values, defaultFromSchema(resolveSchema(itemSchema, rootSchema), rootSchema)])}
        >
          ＋ 追加
        </button>
      </div>
    )
  }

  if (type === 'boolean') {
    return (
      <input
        type="checkbox"
        disabled={disabledPaths.has(path)}
        checked={Boolean(value)}
        onChange={(event) => onChange(event.target.checked)}
      />
    )
  }

  if (type === 'number' || type === 'integer') {
    return <SchemaNumberEditor value={value} schema={resolved} path={path} integer={type === 'integer'}
      disabled={disabledPaths.has(path)} onChange={onChange} onInvalid={onInvalid} validateInitial={validateInitial} />
  }

  const suggestions = suggestionsForPath(suggestionsByPath, path)
  if (path === '/icon') return <MaterialIconInput value={typeof value === 'string' ? value : ''} onChange={onChange}
    disabled={disabledPaths.has(path)} suggestions={suggestions.length ? suggestions.filter((suggestion): suggestion is string => typeof suggestion === 'string') : undefined}
    placeholder={resolved.description ? String(resolved.description) : undefined} />
  const labeledSuggestions = suggestions.filter(isLabeledSuggestion)
  if (labeledSuggestions.length > 0) {
    const currentValue = typeof value === 'string' ? value : ''
    const knownValue = labeledSuggestions.some((suggestion) => suggestion.value === currentValue)
    return (
      <select
        disabled={disabledPaths.has(path)}
        value={currentValue}
        onChange={(event) => onChange(event.target.value)}
      >
        {!knownValue && currentValue && <option value={currentValue}>未定義: {currentValue}</option>}
        {!currentValue && <option value="">選択してください</option>}
        {labeledSuggestions.map((suggestion) => (
          <option key={suggestion.value} value={suggestion.value} title={suggestion.description}>
            {suggestion.label}
          </option>
        ))}
      </select>
    )
  }

  return (
    <SuggestionInput
      type="text"
      disabled={disabledPaths.has(path)}
      value={typeof value === 'string' ? value : ''}
      onChange={(event) => onChange(event.target.value)}
      placeholder={resolved.description ? String(resolved.description) : undefined}
      suggestions={suggestions.filter((suggestion): suggestion is string => typeof suggestion === 'string')}
    />
  )
}

function schemaNumberError(text: string, integer: boolean, schema: JsonObject): string {
  const number = Number(text)
  if (!/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(text.trim()) || !Number.isFinite(number)) return '有限の数値を入力してください。'
  if (integer && !Number.isInteger(number)) return '整数を入力してください。小数は自動で切り捨てません。'
  if (integer && !Number.isSafeInteger(number)) return '正確に扱える範囲（±9007199254740991）の整数を入力してください。'
  if (typeof schema.minimum === 'number' && number < schema.minimum) return `${schema.minimum} 以上の数値を入力してください。`
  if (typeof schema.maximum === 'number' && number > schema.maximum) return `${schema.maximum} 以下の数値を入力してください。`
  if (typeof schema.exclusiveMinimum === 'number' && number <= schema.exclusiveMinimum) return `${schema.exclusiveMinimum} より大きい数値を入力してください。`
  if (typeof schema.exclusiveMaximum === 'number' && number >= schema.exclusiveMaximum) return `${schema.exclusiveMaximum} より小さい数値を入力してください。`
  if (typeof schema.multipleOf === 'number' && schema.multipleOf > 0) {
    const quotient = number / schema.multipleOf
    if (Math.abs(quotient - Math.round(quotient)) > Number.EPSILON * Math.max(1, Math.abs(quotient)) * 4) return `${schema.multipleOf} の倍数を入力してください。`
  }
  return ''
}

function SchemaNumberEditor({ value, schema, integer, path, disabled, onChange, onInvalid, validateInitial }: {
  value: JsonValue; schema: JsonObject; integer: boolean; path: string; disabled: boolean
  onChange: (value: JsonValue) => void; onInvalid?: SchemaFormProps['onInvalid']; validateInitial: boolean
}) {
  const [text, setText] = useState(String(value ?? ''))
  const [error, setError] = useState(() => validateInitial ? schemaNumberError(String(value ?? ''), integer, schema) : '')
  const errorId = useId()
  const invalidRef = useRef(onInvalid)
  invalidRef.current = onInvalid
  const emitted = useRef<number | undefined>(undefined)
  const previousValue = useRef(value)
  const schemaRef = useRef(schema)
  schemaRef.current = schema
  const constraints = JSON.stringify([schema.minimum, schema.maximum, schema.exclusiveMinimum, schema.exclusiveMaximum, schema.multipleOf])
  useEffect(() => {
    const incomingChanged = value !== previousValue.current
    previousValue.current = value
    if (incomingChanged && emitted.current !== undefined && value === emitted.current) { emitted.current = undefined; return }
    emitted.current = undefined
    const next = String(value ?? '')
    const message = validateInitial ? schemaNumberError(next, integer, schemaRef.current) : ''
    setText(next); setError(message); invalidRef.current?.(path, message || null)
  }, [value, path, integer, validateInitial, constraints])
  useEffect(() => () => invalidRef.current?.(path, null), [path])
  return <div><input type="text" inputMode={integer ? 'numeric' : 'decimal'} aria-label={path || '/'} aria-invalid={Boolean(error)}
    aria-describedby={error ? errorId : undefined} disabled={disabled} value={text} onChange={event => {
      const next = event.target.value
      const message = schemaNumberError(next, integer, schema)
      setText(next); setError(message); invalidRef.current?.(path, message || null)
      if (!message) { emitted.current = Number(next); onChange(Number(next)) }
    }} />{error && <small id={errorId} className="error-message" role="alert">{error}</small>}</div>
}

export function defaultFromSchema(schema: JsonObject, rootSchema: JsonObject = schema): JsonValue {
  const resolved = resolveSchema(schema, rootSchema)
  if ('default' in resolved) return structuredClone(resolved.default as JsonValue)
  if ('const' in resolved) return structuredClone(resolved.const as JsonValue)
  const enumValues = asArray(resolved.enum)
  if (enumValues?.length) return structuredClone(enumValues[0])
  const union = asArray(resolved.oneOf ?? resolved.anyOf)
  if (union?.length) return defaultFromSchema(asObject(union[0]) ?? {}, rootSchema)
  const type = schemaType(resolved, null)
  if (type === 'object') {
    const properties = asObject(resolved.properties) ?? {}
    return Object.fromEntries(Object.entries(properties).map(([key, child]) => [
      key,
      defaultFromSchema(asObject(child) ?? {}, rootSchema),
    ]))
  }
  if (type === 'array') return []
  if (type === 'boolean') return false
  if (type === 'number' || type === 'integer') return 0
  return ''
}

function optionMatches(option: JsonValue, value: JsonValue, root: JsonObject): boolean {
  return masterUnionMatches(asObject(option) ?? {}, value, root)
}

function schemaType(schema: JsonObject, value: JsonValue): string {
  if (typeof schema.type === 'string') return schema.type
  if (asObject(schema.properties)) return 'object'
  if (schema.items) return 'array'
  if (Array.isArray(value)) return 'array'
  if (value !== null && typeof value === 'object') return 'object'
  return typeof value === 'number' ? 'number' : typeof value
}

const asObject = (value: JsonValue | undefined): JsonObject | null => (
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null
)
const asArray = (value: JsonValue | undefined): JsonValue[] | null => Array.isArray(value) ? value : null

function suggestionsForPath(
  suggestionsByPath: Readonly<Record<string, readonly FieldSuggestionValue[]>>,
  path: string,
): readonly FieldSuggestionValue[] {
  const exact = suggestionsByPath[path]
  if (exact) return exact
  const wildcardPath = path.replace(/\/\d+(?=\/|$)/g, '/*')
  return suggestionsByPath[wildcardPath] ?? []
}

const isLabeledSuggestion = (value: FieldSuggestionValue): value is FieldSuggestion => typeof value !== 'string'
