import type { JsonObject, JsonValue } from '../types/editor'
import { SuggestionInput } from './SuggestionInput'

interface SchemaFormProps {
  schema: JsonObject
  value: JsonValue
  onChange: (value: JsonValue) => void
  rootSchema?: JsonObject
  path?: string
  disabledPaths?: Set<string>
  suggestionsByPath?: Readonly<Record<string, readonly string[]>>
}

export function SchemaForm({
  schema,
  value,
  onChange,
  rootSchema = schema,
  path = '',
  disabledPaths = new Set(),
  suggestionsByPath = {},
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
            value={selectedIndex}
            onChange={(event) => {
              const next = asObject(union[Number(event.target.value)]) ?? {}
              onChange(defaultFromSchema(resolveSchema(next, rootSchema), rootSchema))
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
        />
      </div>
    )
  }

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
          const childValue = objectValue[key] ?? defaultFromSchema(resolveSchema(childSchema, rootSchema), rootSchema)
          return (
            <label className="schema-field" key={key}>
              <span>
                {String(childSchema.title ?? key)}{required.has(key) && <b aria-label="必須"> *</b>}
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
            />
            <button
              type="button"
              className="button danger subtle"
              onClick={() => onChange(values.filter((_, itemIndex) => itemIndex !== index))}
            >
              削除
            </button>
          </div>
        ))}
        <button
          type="button"
          className="button subtle"
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
    return (
      <input
        type="number"
        disabled={disabledPaths.has(path)}
        step={type === 'integer' ? 1 : 'any'}
        value={typeof value === 'number' ? value : 0}
        onChange={(event) => {
          const parsed = type === 'integer'
            ? Number.parseInt(event.target.value, 10)
            : Number.parseFloat(event.target.value)
          onChange(Number.isFinite(parsed) ? parsed : 0)
        }}
      />
    )
  }

  return (
    <SuggestionInput
      type="text"
      disabled={disabledPaths.has(path)}
      value={typeof value === 'string' ? value : ''}
      onChange={(event) => onChange(event.target.value)}
      placeholder={resolved.description ? String(resolved.description) : undefined}
      suggestions={suggestionsForPath(suggestionsByPath, path)}
    />
  )
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

function resolveSchema(schema: JsonObject, root: JsonObject): JsonObject {
  const reference = typeof schema.$ref === 'string' ? schema.$ref : null
  if (!reference?.startsWith('#/')) return schema
  let current: JsonValue = root
  for (const segment of reference.slice(2).split('/')) {
    const object = asObject(current)
    if (!object) return schema
    current = object[segment.replaceAll('~1', '/').replaceAll('~0', '~')]
  }
  const resolved: JsonObject = { ...asObject(current), ...schema }
  delete resolved.$ref
  return resolved
}

function optionMatches(option: JsonValue, value: JsonValue, root: JsonObject): boolean {
  const schema = resolveSchema(asObject(option) ?? {}, root)
  const properties = asObject(schema.properties)
  const objectValue = asObject(value)
  if (!properties || !objectValue) return false
  return Object.entries(properties).some(([key, property]) => {
    const constValue = asObject(property)?.const
    return constValue !== undefined && objectValue[key] === constValue
  })
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
  suggestionsByPath: Readonly<Record<string, readonly string[]>>,
  path: string,
): readonly string[] {
  const exact = suggestionsByPath[path]
  if (exact) return exact
  const wildcardPath = path.replace(/\/\d+(?=\/|$)/g, '/*')
  return suggestionsByPath[wildcardPath] ?? []
}
