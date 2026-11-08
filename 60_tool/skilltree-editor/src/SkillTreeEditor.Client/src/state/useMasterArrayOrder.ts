import { useId, useRef, useState } from 'react'
import type { DragEvent, KeyboardEvent } from 'react'
import type { JsonValue } from '../types/editor'

/** Keep the source snapshot so a drop cannot move a different row after undo or reload. */
export function useMasterArrayOrder(values: JsonValue[], disabled: boolean, onChange: (next: JsonValue[]) => void, showRow: (index: number) => void) {
  const id = useId()
  const source = useRef<{ values: JsonValue[]; index: number } | null>(null)
  const [over, setOver] = useState<number | null>(null)
  const cancel = () => { source.current = null; setOver(null) }
  const move = (from: number, to: number) => {
    if (disabled || from === to || from < 0 || to < 0 || from >= values.length || to >= values.length) return false
    const next = values.slice()
    const [entry] = next.splice(from, 1)
    next.splice(to, 0, entry)
    onChange(next)
    showRow(to)
    return true
  }
  const handle = (index: number) => ({
    id: id + '-' + index,
    draggable: !disabled && values.length > 1,
    disabled: disabled || values.length < 2,
    onDragStart: (event: DragEvent<HTMLButtonElement>) => {
      if (disabled || values.length < 2) { event.preventDefault(); return }
      source.current = { values, index }
      event.dataTransfer.setData('application/x-astralrecord-master-row', String(index))
      event.dataTransfer.effectAllowed = 'move'
    },
    onDragEnd: cancel,
    onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => {
      if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return
      event.preventDefault()
      const to = index + (event.key === 'ArrowUp' ? -1 : 1)
      if (move(index, to)) requestAnimationFrame(() => document.getElementById(id + '-' + to)?.focus())
    },
  })
  const destination = (index: number) => ({
    'data-reorder-target': source.current?.values === values && over === index || undefined,
    onDragOver: (event: DragEvent<HTMLElement>) => {
      const drag = source.current
      if (disabled || drag?.values !== values) return
      event.preventDefault()
      event.stopPropagation()
      event.dataTransfer.dropEffect = 'move'
      setOver(drag.index === index ? null : index)
    },
    onDragLeave: (event: DragEvent<HTMLElement>) => {
      if (!(event.relatedTarget instanceof Node) || !event.currentTarget.contains(event.relatedTarget)) {
        setOver(current => current === index ? null : current)
      }
    },
    onDrop: (event: DragEvent<HTMLElement>) => {
      const drag = source.current
      if (!drag) return
      event.preventDefault()
      event.stopPropagation()
      cancel()
      if (drag.values === values) move(drag.index, index)
    },
  })
  return { handle, destination, move }
}
