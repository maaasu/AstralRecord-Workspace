import { useViewport, type XYPosition } from '@xyflow/react'
import { useLayoutEffect, useRef, type KeyboardEvent } from 'react'
import { usePanelDocument } from '../docking/dockPanel'
import './gameFlowInlineEditor.css'

export interface FlowInlineAnchor {
  canvas: HTMLElement
  element?: Element | null
  position: XYPosition
}

export function GameFlowInlineEditor({ anchor, value, label, placeholder, multiline, onChange, onFinish }: {
  anchor: FlowInlineAnchor
  value: string
  label: string
  placeholder?: string
  multiline: boolean
  onChange: (value: string) => void
  onFinish: (value: string, action: 'commit' | 'cancel' | 'blur') => void
}) {
  const box = useRef<HTMLDivElement>(null)
  const input = useRef<HTMLInputElement | HTMLTextAreaElement>(null)
  const composing = useRef(false)
  const commitAfterComposition = useRef(false)
  const panelDocument = usePanelDocument()
  const { x, y, zoom } = useViewport()
  useLayoutEffect(() => {
    input.current?.focus({ preventScroll: true })
    input.current?.select()
  }, [])

  useLayoutEffect(() => {
    const root = box.current
    if (!root) return
    const { canvas, element, position } = anchor
    const place = () => {
      const bounds = canvas.getBoundingClientRect()
      const attached = element?.isConnected ? element.getBoundingClientRect() : undefined
      const centerX = attached ? attached.left + attached.width / 2 - bounds.left : position.x * zoom + x
      const centerY = attached ? attached.top + attached.height / 2 - bounds.top : position.y * zoom + y
      root.style.left = `${Math.max(8, Math.min(centerX - root.offsetWidth / 2, bounds.width - root.offsetWidth - 8))}px`
      root.style.top = `${Math.max(8, Math.min(centerY - root.offsetHeight / 2, bounds.height - root.offsetHeight - 8))}px`
    }
    place()
    const observer = new canvas.ownerDocument.defaultView!.ResizeObserver(place)
    observer.observe(canvas)
    observer.observe(root)
    if (element?.isConnected) observer.observe(element)
    return () => observer.disconnect()
  }, [anchor, panelDocument, x, y, zoom])

  useLayoutEffect(() => {
    const root = box.current
    if (!root) return
    const document = root.ownerDocument
    const onClick = (event: MouseEvent) => {
      if (!input.current || root.contains(event.target as Node)) return
      if (composing.current) commitAfterComposition.current = true
      else onFinish(input.current.value, 'blur')
    }
    document.addEventListener('click', onClick, true)
    return () => document.removeEventListener('click', onClick, true)
  }, [onFinish])

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    event.stopPropagation()
    if (composing.current || event.nativeEvent.isComposing || event.keyCode === 229) return
    if (event.key === 'Escape' || event.key === 'Enter' && !(multiline && event.shiftKey)) {
      event.preventDefault()
      onFinish(event.currentTarget.value, event.key === 'Escape' ? 'cancel' : 'commit')
    }
  }
  const props = {
    'aria-label': label,
    value,
    placeholder,
    onKeyDown,
    onCompositionStart: () => { composing.current = true },
    onCompositionEnd: () => {
      composing.current = false
      if (commitAfterComposition.current && input.current) onFinish(input.current.value, 'blur')
    },
    onBlur: () => {
      if (composing.current) commitAfterComposition.current = true
      else if (input.current) onFinish(input.current.value, 'blur')
    },
  }
  return <div ref={box} className="game-flow-inline-editor" onDoubleClick={event => event.stopPropagation()}>
    {multiline
      ? <textarea {...props} rows={3} ref={element => { input.current = element }} onChange={event => onChange(event.target.value)} />
      : <input {...props} ref={element => { input.current = element }} onChange={event => onChange(event.target.value)} />}
  </div>
}
