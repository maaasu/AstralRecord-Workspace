import { useEffect, useRef, type KeyboardEvent, type PointerEvent } from 'react'
import { useReactFlow } from '@xyflow/react'
import { gameFlowNoteSize } from '../data/gameFlow'

export type NoteSize = { width: number; height: number }

type Props = NoteSize & {
  onPreview: (size: NoteSize | null) => void
  onCommit: (size: NoteSize) => void
}

function noteSize(width: number, height: number): NoteSize {
  return {
    width: Math.max(gameFlowNoteSize.minWidth, Math.round(width)),
    height: Math.max(gameFlowNoteSize.minHeight, Math.round(height)),
  }
}

export function GameFlowNoteResize({ width, height, onPreview, onCommit }: Props) {
  const { getZoom } = useReactFlow()
  const stop = useRef<(() => void) | null>(null)
  useEffect(() => () => stop.current?.(), [])

  function begin(event: PointerEvent<HTMLButtonElement>) {
    if (event.button !== 0 || stop.current) return
    event.preventDefault()
    event.stopPropagation()
    const target = event.currentTarget
    const window = target.ownerDocument.defaultView!
    const { pointerId, clientX: startX, clientY: startY } = event
    const zoom = getZoom()
    target.focus({ preventScroll: true })
    target.setPointerCapture?.(pointerId)

    const sizeAt = (next: globalThis.PointerEvent) => {
      return noteSize(width + (next.clientX - startX) / zoom, height + (next.clientY - startY) / zoom)
    }
    const move = (next: globalThis.PointerEvent) => {
      if (next.pointerId === pointerId) onPreview(sizeAt(next))
    }
    const finish = (next: globalThis.PointerEvent) => {
      if (next.pointerId !== pointerId) return
      const size = sizeAt(next)
      clear()
      if (size.width !== width || size.height !== height) onCommit(size)
    }
    const cancel = (next: globalThis.PointerEvent) => {
      if (next.pointerId === pointerId) clear()
    }
    const escape = (next: globalThis.KeyboardEvent) => {
      if (next.key !== 'Escape') return
      next.preventDefault()
      next.stopPropagation()
      clear()
    }
    const clear = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', finish)
      window.removeEventListener('pointercancel', cancel)
      target.removeEventListener('lostpointercapture', cancel)
      window.removeEventListener('keydown', escape, true)
      window.removeEventListener('blur', clear)
      stop.current = null
      if (target.hasPointerCapture?.(pointerId)) target.releasePointerCapture(pointerId)
      onPreview(null)
    }
    stop.current = clear
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', finish)
    window.addEventListener('pointercancel', cancel)
    target.addEventListener('lostpointercapture', cancel)
    window.addEventListener('keydown', escape, true)
    window.addEventListener('blur', clear)
  }

  function resizeWithKeyboard(event: KeyboardEvent<HTMLButtonElement>) {
    const directions: Record<string, [number, number]> = {
      ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1],
    }
    const direction = directions[event.key]
    if (!direction || event.ctrlKey || event.altKey || event.metaKey) return
    event.preventDefault()
    event.stopPropagation()
    if (stop.current) return
    const step = event.shiftKey ? 24 : 8
    const size = noteSize(width + direction[0] * step, height + direction[1] * step)
    if (size.width !== width || size.height !== height) onCommit(size)
  }

  return <button type="button" className="game-flow-note-resize nodrag nopan" aria-label="付箋のサイズを変更"
    onPointerDown={begin} onKeyDown={resizeWithKeyboard} onClick={event => event.stopPropagation()}
    onDoubleClick={event => event.stopPropagation()} />
}
