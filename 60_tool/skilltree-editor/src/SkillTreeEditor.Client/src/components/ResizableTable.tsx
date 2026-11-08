import { useCallback, useSyncExternalStore, type FocusEvent, type ReactNode } from 'react'
import { ResizeHandle } from './ResizeHandle'
import './resizableTable.css'

interface TableColumn {
  id: string
  label: string
  width: number
  minWidth?: number
  title?: string
}

interface ResizableTableProps {
  label: string
  storageKey: string
  columns: readonly TableColumn[]
  caption?: ReactNode
  emptyState?: ReactNode
  className?: string
  tableClassName?: string
  children: ReactNode
}

const defaultMinimum = 64
const maximum = 960
const layoutChanges = new EventTarget()
const unsavedLayouts = new Map<string, string>()

function readLayout(key: string) {
  if (unsavedLayouts.has(key)) return unsavedLayouts.get(key)!
  try { return localStorage.getItem(key) ?? '{}' } catch { return '{}' }
}

function parseLayout(saved: string): Record<string, unknown> {
  try {
    const widths: unknown = JSON.parse(saved)
    if (widths && typeof widths === 'object' && !Array.isArray(widths)) return widths as Record<string, unknown>
  } catch { /* Start with the defaults if the stored layout is invalid. */ }
  return {}
}

function useColumnWidths(storageKey: string) {
  const key = `astralrecord.layout.table.${storageKey}`
  const subscribe = useCallback((notify: () => void) => {
    const sync = (event: StorageEvent) => { if (event.key === key || event.key === null) notify() }
    layoutChanges.addEventListener(key, notify)
    window.addEventListener('storage', sync)
    return () => {
      layoutChanges.removeEventListener(key, notify)
      window.removeEventListener('storage', sync)
    }
  }, [key])
  const saved = useSyncExternalStore(subscribe, () => readLayout(key))
  function setWidth(column: TableColumn, width: number) {
    // Several instances can share a layout, for example the rows in a change review.
    const current = readLayout(key)
    const widths = parseLayout(current)
    if (width === column.width) delete widths[column.id]
    else widths[column.id] = width
    const updated = JSON.stringify(widths)
    if (updated === current) return
    try {
      localStorage.setItem(key, updated)
      unsavedLayouts.delete(key)
    } catch {
      unsavedLayouts.set(key, updated)
    }
    layoutChanges.dispatchEvent(new Event(key))
  }
  return { widths: parseLayout(saved), setWidth }
}

function revealFocusedCell({ currentTarget: region, target }: FocusEvent<HTMLDivElement>) {
  const table = target.closest('table')
  if (!table || table.parentElement !== region || !target.closest('tbody')) return
  const header = table.tHead?.rows[0]?.cells[0]
  if (!header) return
  // Native focus scrolling can leave a control hidden behind the sticky header.
  const overlap = header.getBoundingClientRect().bottom + 2 - target.getBoundingClientRect().top
  if (overlap > 0) region.scrollTop -= overlap
}

export function ResizableTable({ label, storageKey, columns, caption, emptyState, className = '', tableClassName, children }: ResizableTableProps) {
  const { widths, setWidth } = useColumnWidths(storageKey)
  const sizes = columns.map(column => {
    const minimum = column.minWidth ?? defaultMinimum
    const saved = widths[column.id]
    const width = typeof saved === 'number' && Number.isFinite(saved) ? Math.round(saved) : column.width
    return { ...column, minimum, current: Math.min(maximum, Math.max(minimum, width)) }
  })
  return <div className={`resizable-table ${className}`} role="region" aria-label={label} tabIndex={0} onFocusCapture={revealFocusedCell}>
    <table aria-label={caption == null ? label : undefined} className={tableClassName} style={{ width: sizes.reduce((sum, column) => sum + column.current, 0) }}>
      {caption != null && <caption>{caption}</caption>}
      <colgroup>{sizes.map(column => <col key={column.id} style={{ width: column.current }} />)}</colgroup>
      <thead><tr>{sizes.map(column => <th key={column.id} scope="col" aria-label={column.label} title={column.title}>
        {column.label}
        <ResizeHandle label={`${column.label}列の幅を変更`} size={column.current} min={column.minimum} max={maximum}
          defaultSize={column.width} target="column" onResize={width => setWidth(column, width)} className="table-column-resizer" />
      </th>)}</tr></thead>
      {children}
    </table>
    {emptyState}
  </div>
}
