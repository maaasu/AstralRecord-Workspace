import { useLayoutEffect } from 'react'
import { useStore } from '@xyflow/react'

type Bounds = Pick<DOMRectReadOnly, 'left' | 'top' | 'right' | 'bottom'>

function overlaps(first: Bounds, second: Bounds) {
  const gap = 3
  return first.left < second.right + gap && first.right + gap > second.left
    && first.top < second.bottom + gap && first.bottom + gap > second.top
}

/** 描画済みの直交線、角の曲線、交差をまたぐ弧のそれぞれを囲む。 */
function edgeBounds(path: SVGPathElement): Bounds[] {
  const transform = path.getScreenCTM?.()
  if (!transform) return []
  const bounds: Bounds[] = []
  let previous: [number, number] | undefined
  for (const part of path.getAttribute('d')?.match(/[MLQA][^MLQA]*/g) ?? []) {
    const command = part[0]
    const coordinates = part.slice(1).trim().split(/[\s,]+/).map(Number)
    const x = coordinates.at(-2)!
    const y = coordinates.at(-1)!
    if (previous && command !== 'M') {
      const [startX, startY] = previous
      const [controlX, controlY] = command === 'Q' ? coordinates : [x, y]
      const radius = command === 'A' ? Math.max(coordinates[0], coordinates[1]) : 0
      bounds.push({
        left: transform.e + (Math.min(startX, controlX, x) - radius) * transform.a,
        right: transform.e + (Math.max(startX, controlX, x) + radius) * transform.a,
        top: transform.f + (Math.min(startY, controlY, y) - radius) * transform.d,
        bottom: transform.f + (Math.max(startY, controlY, y) + radius) * transform.d,
      })
    }
    previous = [x, y]
  }
  return bounds
}

/** 通常ノードの下に収まる補足を表示する。パン中は位置関係が変わらない。 */
export function GameFlowNodeNotes() {
  const canvas = useStore(state => state.domNode)
  const nodes = useStore(state => state.nodes)
  const edges = useStore(state => state.edges)
  const readable = useStore(state => state.transform[2] >= .8)

  useLayoutEffect(() => {
    if (!canvas) return
    if (!readable) {
      canvas.querySelectorAll('.game-flow-token-summary[data-visible]').forEach(note => note.removeAttribute('data-visible'))
      return
    }
    const window = canvas.ownerDocument.defaultView!
    let frame: number | undefined
    const measure = () => {
      frame = undefined
      const notes = Array.from(canvas.querySelectorAll('.game-flow-token-summary'), note => ({
        note, bounds: note.getBoundingClientRect(),
      }))
      if (notes.length === 0) return
      const obstacles: Bounds[] = Array.from(canvas.querySelectorAll(
        '.game-flow-token-shape, .game-flow-token-name, .react-flow__edge-textbg, .react-flow__edge-text',
      ), element => element.getBoundingClientRect())
      for (const path of canvas.querySelectorAll<SVGPathElement>('.react-flow__edge-path')) {
        obstacles.push(...edgeBounds(path))
      }
      for (const { note, bounds } of notes) {
        const visible = bounds.width > 0 && bounds.height > 0 && !obstacles.some(obstacle => overlaps(bounds, obstacle))
        note.toggleAttribute('data-visible', visible)
        if (visible) obstacles.push(bounds)
      }
    }
    const schedule = () => { frame ??= window.requestAnimationFrame(measure) }
    schedule()
    const edgesLayer = canvas.querySelector('.react-flow__edges')
    const edgeObserver = new window.MutationObserver(schedule)
    if (edgesLayer) edgeObserver.observe(edgesLayer, { subtree: true, childList: true, attributes: true, attributeFilter: ['d', 'transform'] })
    const resizeObserver = new window.ResizeObserver(schedule)
    resizeObserver.observe(canvas)
    return () => {
      if (frame !== undefined) window.cancelAnimationFrame(frame)
      edgeObserver.disconnect()
      resizeObserver.disconnect()
    }
  }, [canvas, edges, nodes, readable])

  return null
}
