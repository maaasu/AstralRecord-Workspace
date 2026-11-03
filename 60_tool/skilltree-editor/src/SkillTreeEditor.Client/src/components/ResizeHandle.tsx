import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'

/** Keep the preferred size across visits without making browser storage required. */
export function usePanelSize(key: string, initial: number, min: number, max: number) {
  const storageKey = `astralrecord.layout.${key}`
  const [size, setSize] = useState(() => {
    try {
      const saved = localStorage.getItem(storageKey)
      const value = saved === null ? initial : Number(saved)
      return Number.isFinite(value) ? clamp(value, min, max) : initial
    } catch { return initial }
  })
  useEffect(() => {
    try { localStorage.setItem(storageKey, String(size)) } catch { /* Storage can be unavailable. */ }
  }, [size, storageKey])
  return [size, setSize] as const
}

interface ResizeHandleProps {
  label: string
  size: number
  min: number
  max: number
  defaultSize: number
  direction?: 1 | -1
  orientation?: 'vertical' | 'horizontal'
  /** Space reserved for the other panes, excluding this seven-pixel divider. */
  minRemaining?: number
  onResize: (size: number) => void
  className?: string
}

export function ResizeHandle({ label, size, min, max, defaultSize, direction = 1, orientation = 'vertical', minRemaining = 0, onResize, className = '' }: ResizeHandleProps) {
  const handle = useRef<HTMLDivElement>(null)
  const cleanup = useRef<() => void>(() => undefined)
  const [available, setAvailable] = useState(Infinity)
  const vertical = orientation === 'vertical'
  const maximum = Math.max(min, Math.min(max, available - minRemaining - 7))
  const value = clamp(size, min, maximum)

  useEffect(() => {
    const parent = handle.current?.parentElement
    if (!parent || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      const length = vertical ? parent.clientWidth : parent.clientHeight
      if (length > 0) setAvailable(length)
    })
    observer.observe(parent)
    return () => observer.disconnect()
  }, [vertical])
  useEffect(() => () => cleanup.current(), [])

  const start = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    event.preventDefault()
    cleanup.current()
    const startPosition = vertical ? event.clientX : event.clientY
    const pane = direction === 1 ? event.currentTarget.previousElementSibling : event.currentTarget.nextElementSibling
    const rect = pane?.getBoundingClientRect()
    const startSize = (vertical ? rect?.width : rect?.height) || value
    const pointerId = event.pointerId
    document.body.classList.add('resizing-panels')
    if (!vertical) document.body.classList.add('resizing-rows')
    const move = (next: PointerEvent) => {
      if (next.pointerId !== pointerId) return
      onResize(clamp(startSize + ((vertical ? next.clientX : next.clientY) - startPosition) * direction, min, maximum))
    }
    const stop = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', stop)
      window.removeEventListener('pointercancel', stop)
      window.removeEventListener('blur', stop)
      document.body.classList.remove('resizing-panels', 'resizing-rows')
    }
    cleanup.current = stop
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', stop)
    window.addEventListener('pointercancel', stop)
    window.addEventListener('blur', stop)
  }

  return <div ref={handle} className={`workspace-resizer ${vertical ? '' : 'row-resizer'} ${className}`} role="separator" aria-label={label} aria-orientation={orientation} aria-valuemin={min} aria-valuemax={maximum} aria-valuenow={value} aria-valuetext={`${value}px`} tabIndex={0} onPointerDown={start} onDoubleClick={() => onResize(clamp(defaultSize, min, maximum))} onKeyDown={event => {
    const increase = vertical ? 'ArrowRight' : 'ArrowDown'
    const decrease = vertical ? 'ArrowLeft' : 'ArrowUp'
    if (![increase, decrease, 'Home', 'End', 'Enter'].includes(event.key)) return
    event.preventDefault()
    onResize(event.key === 'Home' ? min : event.key === 'End' ? maximum : event.key === 'Enter' ? clamp(defaultSize, min, maximum) : clamp(value + (event.key === increase ? 1 : -1) * direction * (event.shiftKey ? 48 : 16), min, maximum))
  }} title={`${label}（ドラッグ・矢印キーで調整、ダブルクリック・Enterで初期サイズ）`} />
}

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, Math.round(value)))
