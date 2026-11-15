import { useId, useState } from 'react'
import type { DragEvent } from 'react'
import { referenceObject, referenceText, type PlacementKind } from '../data/masterReferenceCollection'
import type { JsonObject, JsonValue } from '../types/editor'
import type { MasterFileSummary } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import './masterGridPlacement.css'

const columns = 7
const slotCount = columns * 4

const placementContent = {
  shop: { title: 'ショップの配置', key: 'itemId', noun: '商品', missing: '商品ID・アイテムを確認', fallbackIcon: null },
  quest_board: { title: '掲示板の配置', key: 'questId', noun: 'クエスト', missing: 'クエストを確認', fallbackIcon: 'WRITABLE_BOOK' },
} as const

interface PlacementEntry {
  index: number
  value: JsonObject | null
  reference: string
  hasIdentity: boolean
  page: number
  slot: number | null
  usesCoordinates: boolean
}

interface MoveSource {
  entry: PlacementEntry
  values: JsonValue[]
  dragging: boolean
}

// The game accepts quoted integers and uses 0 for malformed non-null coordinates.
function placementInteger(value: JsonValue | undefined, fallback: number): number {
  if (typeof value === 'number') {
    if (Number.isInteger(value)) return value | 0
    return Math.trunc(Math.max(-2147483648, Math.min(value, 2147483647)))
  }
  if (typeof value !== 'string' || !/^[+-]?\d+$/.test(value.trim())) return fallback
  const integer = Number(value)
  return integer >= -2147483648 && integer <= 2147483647 ? integer : fallback
}

function readPlacement(raw: JsonValue, index: number, kind: PlacementKind): PlacementEntry {
  const value = referenceObject(raw)
  const reference = referenceText(value?.[placementContent[kind].key])
  const hasIdentity = (kind !== 'shop' || Boolean(referenceText(value?.id))) && Boolean(reference.slice(reference.indexOf(':') + 1).trim())
  const page = Math.max(1, placementInteger(value?.page, 1))
  const usesCoordinates = value?.slot == null && value?.row != null && value?.column != null
  let slot = -1
  if (value?.slot != null) slot = placementInteger(value.slot, 0)
  else if (usesCoordinates) slot = ((placementInteger(value.row, 0) - 1) * columns + placementInteger(value.column, 0) - 1) | 0
  return { index, value, reference, hasIdentity, page, slot: slot >= 0 && slot < slotCount ? slot : null, usesCoordinates }
}

export function isPlacementEntryUnplaced(value: JsonValue, kind: PlacementKind) {
  const entry = readPlacement(value, 0, kind)
  return entry.hasIdentity && entry.slot === null
}

function entryLabel(entry: PlacementEntry, file: MasterFileSummary | undefined): string {
  return file?.name || entry.reference
}

function PlacementIcon({ label, file, fallbackIcon }: { label: string; file?: MasterFileSummary; fallbackIcon: string | null }) {
  return <>
    <MinecraftIcon icon={file?.icon ?? fallbackIcon} customModelData={file?.customModelData} iconTexture={file?.iconTexture} className="master-grid-placement-icon" />
    <span className="master-grid-placement-name"><MinecraftText value={label} /></span>
  </>
}

export function MasterGridPlacement({ kind, values, fileForEntry, onChange, disabled }: {
  kind: PlacementKind
  values: JsonValue[]
  fileForEntry: (entry: JsonObject) => MasterFileSummary | undefined
  onChange: (values: JsonValue[]) => void
  disabled?: boolean
}) {
  const titleId = useId()
  const [selectedPage, setSelectedPage] = useState(1)
  const [pageText, setPageText] = useState<string | null>(null)
  const [source, setSource] = useState<MoveSource | null>(null)
  const activeSource = !disabled && source?.values === values ? source : null
  if (source && !activeSource) setSource(null)

  const content = placementContent[kind]
  const entries = values.map((value, index) => readPlacement(value, index, kind))
  const lastPage = entries.reduce((last, entry) => Math.max(last, entry.page), 1)
  const maxPage = Math.min(lastPage + 1, 2147483647)
  const typedPage = Number(pageText)
  const page = Number.isInteger(typedPage) && typedPage >= 1 && typedPage <= maxPage ? typedPage : Math.min(selectedPage, maxPage)
  const pageEntries = entries.filter((entry) => entry.page === page)
  const slots = Array.from({ length: slotCount }, () => [] as PlacementEntry[])
  for (const entry of pageEntries) {
    if (entry.hasIdentity && entry.slot !== null) slots[entry.slot].push(entry)
  }
  const issues = pageEntries.filter(({ hasIdentity, slot }) => !hasIdentity || slot === null || slots[slot].length > 1)
  const selected = activeSource?.entry
  const selectedLabel = selected?.value ? entryLabel(selected, fileForEntry(selected.value)) : ''

  function place(slot: number) {
    if (!selected?.value || slots[slot].length > 0) return
    const next = { ...selected.value }
    if (selected.page !== page) next.page = page
    if (selected.slot !== slot) {
      if (selected.usesCoordinates) {
        next.row = Math.floor(slot / columns) + 1
        next.column = slot % columns + 1
      } else {
        next.slot = slot
      }
    }
    setSource(null)
    onChange(values.map((entry, index) => index === selected.index ? next : entry))
  }

  function entryActions(entry: PlacementEntry) {
    return {
      draggable: !disabled,
      onClick: () => setSource(selected?.index === entry.index ? null : { entry, values, dragging: false }),
      onDragStart: (event: DragEvent<HTMLButtonElement>) => {
        if (disabled) return
        event.dataTransfer.setData('application/x-astralrecord-grid-entry', String(entry.index))
        event.dataTransfer.effectAllowed = 'move'
        setSource({ entry, values, dragging: true })
      },
      onDragEnd: () => setSource(null),
    }
  }

  return <section className="master-grid-placement" aria-labelledby={titleId} onKeyDown={(event) => {
    if (event.key === 'Escape' && !event.nativeEvent.isComposing && activeSource) {
      event.preventDefault()
      event.stopPropagation()
      setSource(null)
    }
  }}>
    <header className="master-grid-placement-header">
      <h5 id={titleId}>{content.title}</h5>
      <label>ページ
        <input type="number" aria-label="配置ページ" min={1} max={maxPage} value={pageText ?? page}
          onChange={event => setPageText(event.target.value)} onBlur={() => { setSelectedPage(page); setPageText(null) }}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
              event.preventDefault()
              event.currentTarget.blur()
            }
          }} />
        <span>{page > lastPage ? '新しいページ' : `/ ${lastPage}`}</span>
      </label>
    </header>
    <div className="master-grid-placement-help">
      <span role="status">{selected ? <><MinecraftText value={selectedLabel} />を移動中。空きマスを選んでください。</>
        : `${content.noun}を空きマスへドラッグするか、移動元と移動先を順に選びます。`}</span>
      {selected && !activeSource.dragging && <button type="button" className="text-button" onClick={() => setSource(null)}>選択を解除</button>}
    </div>
    <div className="master-grid-placement-grid" role="group" aria-label={`${page}ページの${content.noun}マス`}>
      {slots.map((occupants, slot) => {
        const slotLabel = `スロット ${slot}`
        if (occupants.length > 1) return <div className="master-grid-placement-cell collision" key={slot} title={`${slotLabel} · 重複 ${occupants.length} 件`}>
          <span className="master-grid-placement-number">{slot}</span><span>重複</span>
        </div>
        const entry = occupants[0]
        if (entry) {
          const file = entry.value ? fileForEntry(entry.value) : undefined
          const label = entryLabel(entry, file)
          const description = `${slotLabel} · ${stripMinecraftFormatting(label)}`
          return <button type="button" className="master-grid-placement-cell occupied" key={slot} disabled={disabled}
            aria-pressed={selected?.index === entry.index} aria-label={description} title={description} {...entryActions(entry)}>
            <span className="master-grid-placement-number">{slot}</span><PlacementIcon label={label} file={file} fallbackIcon={content.fallbackIcon} />
          </button>
        }
        return <button type="button" className="master-grid-placement-cell empty" key={slot}
          disabled={disabled} tabIndex={selected ? 0 : -1} aria-disabled={!selected} aria-label={`${slotLabel} · 空き`}
          onClick={() => { if (!activeSource?.dragging) place(slot) }}
          onDragOver={(event) => {
            if (!activeSource?.dragging) return
            event.preventDefault()
            event.dataTransfer.dropEffect = 'move'
          }}
          onDrop={(event) => {
            if (!activeSource?.dragging) return
            event.preventDefault()
            place(slot)
          }}>
          <span className="master-grid-placement-number">{slot}</span>
        </button>
      })}
    </div>
    {issues.length > 0 && <div className="master-grid-placement-issues" role="group" aria-label={`配置を確認する${content.noun}`}>
      <span>配置を確認</span>
      {issues.map((entry) => {
        const entryNumber = `${content.noun} ${entry.index + 1}`
        if (!entry.value) return <span key={entry.index}>{entryNumber} · 詳細で形式を確認</span>
        if (!entry.hasIdentity) return <span key={entry.index}>{entryNumber} · {content.missing}</span>
        const file = fileForEntry(entry.value)
        const label = entryLabel(entry, file)
        const reason = entry.slot === null ? '未配置' : `スロット ${entry.slot} で重複`
        const description = `${stripMinecraftFormatting(label)} · ${entryNumber} · ${reason}`
        return <button type="button" className="master-grid-placement-issue" key={entry.index} disabled={disabled}
          aria-label={description} title={description} aria-pressed={selected?.index === entry.index} {...entryActions(entry)}>
          <PlacementIcon label={label} file={file} fallbackIcon={content.fallbackIcon} /><small>{reason}</small>
        </button>
      })}
    </div>}
  </section>
}
