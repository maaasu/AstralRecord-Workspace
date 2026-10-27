import { Fragment, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import type { JsonObject, JsonValue } from '../types/editor'
import type { MasterField, MasterReference } from '../types/masterData'
import { closestContainerPointer, parentPointer, replaceAtPointer, schemaAtPointer, summarizeMasterValue, valueAtPointer } from '../data/masterDataNavigation'
import { fieldForPath, MasterDataForm, pointerKey } from './MasterDataForm'
import { MinecraftText } from './MinecraftText'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { masterFieldTitle } from '../data/masterDataPresentation'
import { masterValueLabel } from '../data/masterValuePresentation'

const structured = (value: JsonValue | undefined): value is JsonObject | JsonValue[] => value !== null && typeof value === 'object'
const decode = (key: string) => key.replaceAll('~1', '/').replaceAll('~0', '~')
const children = (value: JsonValue, path: string) => structured(value)
  ? Object.entries(value as JsonObject).flatMap(([key, child]) => child === undefined ? [] : [{ path: path + '/' + pointerKey(key), key, value: child }])
  : []

function nodeLabel(value: JsonValue, schema: JsonObject, fields: MasterField[], path: string) {
  if (!path) return '基本情報'
  const key = decode(path.split('/').at(-1) ?? '')
  if (Array.isArray(valueAtPointer(value, parentPointer(path)))) return '要素 ' + (Number(key) + 1)
  return masterFieldTitle(key, schemaAtPointer(schema, path, value).title ?? fieldForPath(fields, path)?.label) || '空のキー'
}

interface SearchProps {
  value: JsonValue
  fields: MasterField[]
  schema: JsonObject
  disabled?: boolean
  onSelect: (path: string) => void
  onFocus: (path: string) => void
}

export function MasterDataSearch({ value, fields, schema, disabled, onSelect, onFocus }: SearchProps) {
  const [query, setQuery] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const resultsRef = useRef<HTMLUListElement>(null)
  const search = query.trim().toLocaleLowerCase()
  const matches = useMemo(() => {
    if (!search) return []
    const found: { path: string; label: string; value: JsonValue }[] = []
    function visit(current: JsonValue, path: string) {
      const label = nodeLabel(value, schema, fields, path)
      const searchableValue = typeof current === 'string' ? `${current} ${masterValueLabel(path, current)}` : summarizeMasterValue(current)
      if (path && [path, label, searchableValue, stripMinecraftFormatting(searchableValue)].some((text) => text.toLocaleLowerCase().includes(search))) found.push({ path, label, value: current })
      children(current, path).forEach((child) => visit(child.value, child.path))
    }
    visit(value, '')
    return found
  }, [value, fields, schema, search])
  const focusResult = (index: number) => resultsRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')[index]?.focus()
  const onInputKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (disabled || event.nativeEvent.isComposing || event.keyCode === 229 || !matches.length) return
    if (event.key === 'ArrowDown' || event.key === 'Enter') {
      event.preventDefault()
      focusResult(0)
    }
  }
  const onResultKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (event.nativeEvent.isComposing || event.keyCode === 229) return
    const count = Math.min(matches.length, 100)
    if (event.key === 'Escape') {
      event.preventDefault()
      inputRef.current?.focus()
      return
    }
    const next = event.key === 'ArrowDown' ? (index + 1) % count
      : event.key === 'ArrowUp' ? (index - 1 + count) % count
        : event.key === 'Home' ? 0 : event.key === 'End' ? count - 1 : -1
    if (next < 0) return
    event.preventDefault()
    focusResult(next)
  }
  return <div className="master-form-search">
    <label>フォーム内検索<input ref={inputRef} aria-label="フォーム内検索" placeholder="日本語の項目名 / キー / 値" value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={onInputKeyDown} /></label>
    {search && <div className="master-form-search-results"><p className="master-muted" role="status">{matches.length}件{matches.length > 100 ? '（先頭100件）' : ''}</p><ul ref={resultsRef}>{matches.slice(0, 100).map((match, index) => <li key={match.path}><button type="button" className="master-search-result" disabled={disabled} aria-label={match.path + ' に移動'} onKeyDown={(event) => onResultKeyDown(event, index)} onClick={() => { if (structured(match.value)) onSelect(match.path); onFocus(match.path); setQuery('') }}><span>{match.label}</span><small><MinecraftText value={summarizeMasterValue(typeof match.value === 'string' ? masterValueLabel(match.path, match.value) : match.value)} /></small><code>{match.path}</code></button></li>)}</ul></div>}
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
  onFocus: (path: string) => void
  onInvalid: (path: string, message: string | null, text?: string) => void
  invalidValues?: Record<string, string>
  onChange: (value: JsonValue) => void
}

export function MasterDataDetails({ value, path, fields, references, schema, disabled, navigationDisabled, onSelect, onFocus, onInvalid, invalidValues, onChange }: DetailsProps) {
  const selectAndFocus = (nextPath: string) => {
    if (navigationDisabled) return
    onSelect(nextPath)
    onFocus(nextPath)
  }
  const currentPath = closestContainerPointer(value, path)
  const current = valueAtPointer(value, currentPath)
  const crumbs = currentPath ? currentPath.split('/').slice(1).map((_, index, segments) => '/' + segments.slice(0, index + 1).join('/')) : []
  const sections = children(value, '').filter((child) => structured(child.value))
  return <section className="master-node-editor" aria-label="マスター項目の編集">
    <div className="master-form-navigation">
      <nav className="master-section-links" aria-label="編集セクション">
        <button type="button" className={'button compact ' + (!currentPath ? 'active' : '')} aria-current={!currentPath ? 'page' : undefined} disabled={navigationDisabled} onClick={() => selectAndFocus('')}>基本情報</button>
        {sections.map((section) => <button type="button" key={section.path} className={'button compact ' + (currentPath === section.path || currentPath.startsWith(section.path + '/') ? 'active' : '')} aria-current={currentPath === section.path || currentPath.startsWith(section.path + '/') ? 'page' : undefined} disabled={navigationDisabled} title={section.key} onClick={() => selectAndFocus(section.path)}>{nodeLabel(value, schema, fields, section.path)}{Array.isArray(section.value) && <small>{section.value.length} 件</small>}</button>)}
      </nav>
      <MasterDataSearch value={value} fields={fields} schema={schema} disabled={navigationDisabled} onSelect={onSelect} onFocus={onFocus} />
    </div>
    {currentPath && <nav className="master-breadcrumbs" aria-label="現在の階層">
      <button type="button" className="text-button" disabled={navigationDisabled} onClick={() => selectAndFocus('')}>基本情報</button>
      {crumbs.map((crumb) => <Fragment key={crumb}><span aria-hidden="true">›</span><button type="button" className="text-button" disabled={navigationDisabled} aria-current={crumb === currentPath ? 'page' : undefined} onClick={() => selectAndFocus(crumb)}>{nodeLabel(value, schema, fields, crumb)}</button></Fragment>)}
    </nav>}
    <div className="master-node-heading"><h3 tabIndex={-1} data-node-heading>{nodeLabel(value, schema, fields, currentPath)}</h3>{currentPath && <code>{currentPath}</code>}</div>
    <fieldset className="master-form-lock" disabled={disabled}>
      <MasterDataForm key={currentPath} value={current === undefined ? value : current} path={currentPath} fields={fields} references={references} schema={schemaAtPointer(schema, currentPath, value)} rootSchema={schema}
        onInvalid={onInvalid} invalidValues={invalidValues} onNavigate={selectAndFocus} navigationDisabled={navigationDisabled} onChange={(next) => onChange(replaceAtPointer(value, currentPath, next))} />
    </fieldset>
  </section>
}
