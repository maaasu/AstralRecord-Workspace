import { Fragment, useEffect, useMemo, useState } from 'react'
import type { JsonObject, JsonValue } from '../types/editor'
import type { MasterField, MasterReference } from '../types/masterData'
import { closestContainerPointer, parentPointer, replaceAtPointer, schemaAtPointer, summarizeMasterValue, valueAtPointer } from '../data/masterDataNavigation'
import { fieldForPath, MasterDataForm, pointerKey } from './MasterDataForm'

const structured = (value: JsonValue | undefined): value is JsonObject | JsonValue[] => value !== null && typeof value === 'object'
const decode = (key: string) => key.replaceAll('~1', '/').replaceAll('~0', '~')
const children = (value: JsonValue, path: string) => structured(value)
  ? Object.entries(value as JsonObject).flatMap(([key, child]) => child === undefined ? [] : [{ path: path + '/' + pointerKey(key), key, value: child }])
  : []

function nodeLabel(value: JsonValue, schema: JsonObject, fields: MasterField[], path: string) {
  if (!path) return '全体'
  const key = decode(path.split('/').at(-1) ?? '')
  if (Array.isArray(valueAtPointer(value, parentPointer(path)))) return '[' + key + ']'
  return String(schemaAtPointer(schema, path, value).title ?? fieldForPath(fields, path)?.label ?? (key || '空のキー'))
}

interface OutlineProps {
  value: JsonValue
  fields: MasterField[]
  schema: JsonObject
  selectedPath: string
  disabled?: boolean
  onSelect: (path: string) => void
  onFocus: (path: string) => void
}

export function MasterDataOutline({ value, fields, schema, selectedPath, disabled, onSelect, onFocus }: OutlineProps) {
  const [query, setQuery] = useState('')
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set())
  const [limits, setLimits] = useState<Record<string, number>>({})
  const search = query.trim().toLocaleLowerCase()
  useEffect(() => {
    if (!selectedPath) return
    setExpanded((current) => {
      const next = new Set(current)
      let parent = selectedPath
      while (parent) { next.add(parent); parent = parentPointer(parent) }
      return next
    })
  }, [selectedPath])
  const matches = useMemo(() => {
    if (!search) return []
    const found: { path: string; label: string; value: JsonValue }[] = []
    function visit(current: JsonValue, path: string) {
      const label = nodeLabel(value, schema, fields, path)
      const searchableValue = typeof current === 'string' ? current : summarizeMasterValue(current)
      if (path && (path + ' ' + label + ' ' + searchableValue).toLocaleLowerCase().includes(search)) found.push({ path, label, value: current })
      children(current, path).forEach((child) => visit(child.value, child.path))
    }
    visit(value, '')
    return found
  }, [value, fields, schema, search])
  function branch(current: JsonValue, path: string) {
    const nodes = children(current, path).filter((child) => structured(child.value))
    const limit = limits[path] ?? 50
    const shown = nodes.filter((child, index) => index < limit || selectedPath === child.path || selectedPath.startsWith(child.path + '/'))
    return <ul className="master-outline-list">{shown.map((child) => {
      const open = expanded.has(child.path)
      const hasChildren = children(child.value, child.path).some((entry) => structured(entry.value))
      return <li key={child.path}>
        <div className="master-outline-row">
          {hasChildren ? <button type="button" className="master-outline-toggle" aria-label={child.path + ' の子ノードを' + (open ? '折り畳む' : '展開')} aria-expanded={open} onClick={() => setExpanded((currentExpanded) => {
            const next = new Set(currentExpanded)
            if (next.has(child.path)) next.delete(child.path); else next.add(child.path)
            return next
          })}>{open ? '▾' : '▸'}</button> : <span className="master-outline-dot" aria-hidden="true">·</span>}
          <button type="button" className={'master-outline-node ' + (selectedPath === child.path ? 'selected' : '')} disabled={disabled} aria-current={selectedPath === child.path ? 'true' : undefined} aria-label={child.path + ' を編集'} onClick={() => onSelect(child.path)}>
            <span>{nodeLabel(value, schema, fields, child.path)}</span><small>{summarizeMasterValue(child.value)}</small>
          </button>
        </div>
        {hasChildren && open && branch(child.value, child.path)}
      </li>
    })}{nodes.length > limit && <li><button type="button" className="button compact subtle" onClick={() => setLimits((current) => ({ ...current, [path]: limit + 50 }))}>さらに50件を表示</button></li>}</ul>
  }
  return <div className="master-outline">
    <label>フォーム内検索<input aria-label="フォーム内検索" placeholder="項目名 / キー / 値" value={query} onChange={(event) => setQuery(event.target.value)} /></label>
    <nav aria-label="レコード内の構造">
      <button type="button" className={'master-outline-node master-outline-root ' + (!selectedPath ? 'selected' : '')} disabled={disabled} aria-current={!selectedPath ? 'true' : undefined} aria-label="全体を編集" onClick={() => onSelect('')}>全体<small>{summarizeMasterValue(value)}</small></button>
      {search ? <><p className="master-muted" role="status">{matches.length}件{matches.length > 100 ? '（先頭100件）' : ''}</p><ul className="master-outline-list master-search-results">{matches.slice(0, 100).map((match) => <li key={match.path}><button type="button" className="master-outline-node" disabled={disabled} aria-label={match.path + ' に移動'} onClick={() => structured(match.value) ? onSelect(match.path) : onFocus(match.path)}><span>{match.label}</span><code>{match.path}</code><small>{summarizeMasterValue(match.value)}</small></button></li>)}</ul></>
        : branch(value, '')}
    </nav>
  </div>
}

interface DetailsProps {
  value: JsonValue
  path: string
  fields: MasterField[]
  references: MasterReference[]
  schema: JsonObject
  disabled: boolean
  navigationDisabled: boolean
  onSelect: (path: string) => void
  onInvalid: (path: string, message: string | null) => void
  onChange: (value: JsonValue) => void
}

export function MasterDataDetails({ value, path, fields, references, schema, disabled, navigationDisabled, onSelect, onInvalid, onChange }: DetailsProps) {
  const currentPath = closestContainerPointer(value, path)
  const current = valueAtPointer(value, currentPath)
  const crumbs = currentPath ? currentPath.split('/').slice(1).map((_, index, segments) => '/' + segments.slice(0, index + 1).join('/')) : []
  return <section className="master-node-editor" aria-label="選択ノードの編集">
    <nav className="master-breadcrumbs" aria-label="現在の階層">
      <button type="button" className="text-button" disabled={navigationDisabled} aria-current={!currentPath ? 'page' : undefined} onClick={() => onSelect('')}>全体</button>
      {crumbs.map((crumb) => <Fragment key={crumb}><span aria-hidden="true">›</span><button type="button" className="text-button" disabled={navigationDisabled} aria-current={crumb === currentPath ? 'page' : undefined} onClick={() => onSelect(crumb)}>{nodeLabel(value, schema, fields, crumb)}</button></Fragment>)}
    </nav>
    <div className="master-node-heading"><h3 tabIndex={-1} data-node-heading>{currentPath ? nodeLabel(value, schema, fields, currentPath) : '基本情報'}</h3><code>{currentPath || '/'}</code></div>
    <fieldset className="master-form-lock" disabled={disabled}>
      <MasterDataForm key={currentPath} value={current === undefined ? value : current} path={currentPath} fields={fields} references={references} schema={schemaAtPointer(schema, currentPath, value)} rootSchema={schema}
        onInvalid={onInvalid} onNavigate={onSelect} navigationDisabled={navigationDisabled} onChange={(next) => onChange(replaceAtPointer(value, currentPath, next))} />
    </fieldset>
  </section>
}
