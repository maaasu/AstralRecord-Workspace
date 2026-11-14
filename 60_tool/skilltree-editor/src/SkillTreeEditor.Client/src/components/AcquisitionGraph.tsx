import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { PointerEvent } from 'react'
import {
  acquisitionColumns, buildAcquisitionDiagram, formatDesignFields, graphName, indexAcquisition,
  selectedAcquisitionPath, sourceKindNames,
} from '../data/progressionGraph'
import type { AcquisitionNode } from '../data/progressionGraph'
import type { ProgressionDesignCatalog } from '../types/progressionDesign'
import { MinecraftIcon } from './MinecraftIcon'
import './AcquisitionGraph.css'

interface AcquisitionGraphProps {
  catalog: ProgressionDesignCatalog | null
  itemId: string | null
  selectedSourceId?: string | null
  loading?: boolean
  onSelectItem(itemId: string): void
  onSelectSource(sourceId: string): void
}

const offsets = [20, 310, 600, 890]
const cardWidth = 220
const cardHeight = 92
const step = 114
const boardWidth = 1130
const firstRow = 66
const minZoom = .08
const maxZoom = 1.5
const sourceGlyphs: Record<string, string> = {
  recipe: '⚒', shop: '⇄', mob: '⚔', gathering: '⛏', dungeon: '⚑', fishing: '♒', lootTable: '▤', lootPool: '◇',
}

export function AcquisitionGraph({ catalog, itemId, selectedSourceId, loading, onSelectItem, onSelectSource }: AcquisitionGraphProps) {
  const index = useMemo(() => catalog && indexAcquisition(catalog), [catalog])
  const diagram = useMemo(() => index && itemId ? buildAcquisitionDiagram(index, itemId, selectedSourceId) : null,
    [index, itemId, selectedSourceId])
  const active = useMemo(() => diagram ? selectedAcquisitionPath(diagram, selectedSourceId) : new Set<string>(), [diagram, selectedSourceId])
  const headingId = useId()
  const markerId = useId()
  const viewport = useRef<HTMLDivElement>(null)
  const grip = useRef<{ id: number; x: number; y: number; left: number; top: number } | null>(null)
  const [zoom, setZoom] = useState(1)
  const [view, setView] = useState<'graph' | 'list'>('graph')
  const [dragging, setDragging] = useState(false)
  useEffect(() => { if (viewport.current) viewport.current.scrollTop = viewport.current.scrollLeft = 0 }, [itemId])

  const layout = useMemo(() => {
    if (!diagram) return null
    const directCount = diagram.edges.filter(edge => edge.from.startsWith('0:') && edge.to.startsWith('3:')).length
    const rowStart = firstRow + directCount * 8
    const height = rowStart + Math.max(2, ...diagram.columns.map(column => column.length)) * step
    const positions = new Map<string, { x: number; y: number; column: number }>()
    diagram.columns.forEach((nodes, column) => nodes.forEach((node, row) => {
      positions.set(node.key, { x: offsets[column], y: rowStart + row * step, column })
    }))
    return { height, positions }
  }, [diagram])

  function changeZoom(value: number) {
    setZoom(Math.min(maxZoom, Math.max(minZoom, Math.round(value * 100) / 100)))
  }
  function recenter(fit: boolean) {
    const element = viewport.current
    if (!element || !layout) return
    changeZoom(fit ? Math.min(element.clientWidth / boardWidth, element.clientHeight / layout.height, 1) : 1)
    element.scrollTop = element.scrollLeft = 0
  }
  function startPan(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0 || event.pointerType !== 'mouse' || event.target instanceof Element && event.target.closest('button')) return
    const element = event.currentTarget
    grip.current = { id: event.pointerId, x: event.clientX, y: event.clientY, left: element.scrollLeft, top: element.scrollTop }
    element.setPointerCapture(event.pointerId)
    setDragging(true)
  }
  function movePan(event: PointerEvent<HTMLDivElement>) {
    const pending = grip.current
    if (pending?.id !== event.pointerId) return
    event.currentTarget.scrollLeft = pending.left + pending.x - event.clientX
    event.currentTarget.scrollTop = pending.top + pending.y - event.clientY
  }
  function stopPan(event: PointerEvent<HTMLDivElement>) {
    if (grip.current?.id !== event.pointerId) return
    grip.current = null
    setDragging(false)
  }
  function nodeCard(node: AcquisitionNode) {
    const source = node.source
    const selected = node.sourceIds?.includes(selectedSourceId ?? '') ?? false
    const kind = source ? sourceKindNames[source.kind] ?? source.kind : node.key.startsWith('3:') ? '選択中の装備' : '必要素材'
    const numbers = source ? [formatDesignFields(source.quantity), formatDesignFields(source.costs), source.rates.length && `設定値 ${formatDesignFields(source.rates)}`].filter(Boolean).join(' · ') : ''
    const context = source ? source.context.map(entry => graphName(entry.name, entry.id)).filter(value => value !== node.label).join(' › ') : ''
    return <button type="button" className="acquisition-node" data-kind={source ? (source.kind === 'shop' || source.kind === 'recipe' ? 'operation' : 'source') : (node.key.startsWith('3:') ? 'result' : 'material')}
      data-selected={selected || undefined} data-on-path={active.has(node.key) || undefined} aria-pressed={source ? selected : undefined}
      aria-label={[kind, node.label, numbers, ...node.notes].filter(Boolean).join('、')}
      title={[node.label, source?.path || node.itemId, context, numbers, ...node.notes].filter(Boolean).join('\n')}
      disabled={!source && !node.item}
      onClick={() => source ? onSelectSource(source.id) : node.itemId && onSelectItem(node.itemId)}>
      <span className="acquisition-node-icon" aria-hidden="true">{source ? <span>{sourceGlyphs[source.kind] ?? '◇'}</span> : <MinecraftIcon icon={node.item?.icon ?? null} customModelData={node.item?.customModelData} iconTexture={node.item?.iconTexture} />}</span>
      <span className="acquisition-node-content">
        <span className="acquisition-node-kind">{kind}{source?.level != null ? ` · Lv ${source.level}` : ''}{node.sourceIds && node.sourceIds.length > 1 ? ` · ${node.sourceIds.length} 件を集約` : ''}</span>
        <strong>{node.label}</strong>
        {source && (context || numbers) && <span className="acquisition-node-meta">{numbers || context}</span>}
        {!source && node.itemId && <span className="acquisition-node-meta">{node.itemId}</span>}
        {node.notes.length > 0 && <span className="acquisition-node-notes">{node.notes.join(' · ')}</span>}
      </span>
    </button>
  }
  let directLane = 0
  return <section className="acquisition-graph" aria-labelledby={headingId} aria-busy={loading || undefined}>
    <header className="acquisition-header">
      <div><h3 id={headingId}>入手経路</h3><p>保存済みマスターと反映中の編集内容から表示</p></div>
      {diagram && <div className="acquisition-actions">
        <div role="group" aria-label="入手経路の表示形式" className="acquisition-toggle">
          <button type="button" aria-pressed={view === 'graph'} onClick={() => setView('graph')}>図</button>
          <button type="button" aria-pressed={view === 'list'} onClick={() => setView('list')}>一覧</button>
        </div>
        {view === 'graph' && <div role="group" aria-label="図の拡大・縮小" className="acquisition-zoom">
          <button type="button" aria-label="縮小" disabled={zoom <= minZoom} onClick={() => changeZoom(zoom - .15)}>−</button>
          <button type="button" title="100% と起点に戻す" aria-label="表示倍率を100%に戻す" onClick={() => recenter(false)}>{Math.round(zoom * 100)}%</button>
          <button type="button" aria-label="拡大" disabled={zoom >= maxZoom} onClick={() => changeZoom(zoom + .15)}>＋</button>
          <button type="button" onClick={() => recenter(true)}>全体表示</button>
        </div>}
      </div>}
    </header>
    {loading && <p className="acquisition-empty" role="status">入手経路を読み込み中…</p>}
    {!loading && !diagram && <p className="acquisition-empty">{catalog ? '装備を選択すると入手経路を表示します。' : '入手経路のデータがありません。'}</p>}
    {diagram && layout && <>
      <div className="acquisition-summary"><span>{diagram.routes} 経路</span><span>素材 {diagram.columns[1].length} 件</span>
        {diagram.hidden > 0 && <span className="acquisition-limit">上限により {diagram.hidden} 件省略</span>}
        {diagram.routes === 0 && <span className="acquisition-limit">入手先の設定が見つかりません</span>}
      </div>
      {view === 'graph' ? <div ref={viewport} className="acquisition-viewport" data-dragging={dragging || undefined} tabIndex={0} role="region" aria-label="入手経路図、左から右へ"
        onPointerDown={startPan} onPointerMove={movePan} onPointerUp={stopPan} onPointerCancel={stopPan} onLostPointerCapture={stopPan}>
        <div className="acquisition-extent" style={{ width: boardWidth * zoom, height: layout.height * zoom }}>
          <div className="acquisition-board" style={{ width: boardWidth, height: layout.height, transform: `scale(${zoom})` }}>
            <svg aria-hidden="true" className="acquisition-connectors" width={boardWidth} height={layout.height}>
              <defs><marker id={markerId} markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M 0 0 L 7 3.5 L 0 7 z" fill="context-stroke" /></marker></defs>
              {diagram.edges.map(edge => {
                const from = layout.positions.get(edge.from)!
                const to = layout.positions.get(edge.to)!
                const x1 = from.x + cardWidth + 2, y1 = from.y + cardHeight / 2
                const x2 = to.x - 5, y2 = to.y + cardHeight / 2
                const middle = (x1 + x2) / 2
                const direct = to.column - from.column > 1
                const lane = direct ? 54 + directLane++ * 8 : 0
                const activeEdge = active.has(edge.from) && active.has(edge.to)
                const path = direct ? `M ${x1} ${y1} h 8 q 12 0 12 -12 V ${lane + 10} q 0 -10 10 -10 H ${x2 - 30} q 10 0 10 10 V ${y2 - 12} q 0 12 12 12 H ${x2}` :
                  `M ${x1} ${y1} C ${middle} ${y1}, ${middle} ${y2}, ${x2} ${y2}`
                return <path key={`${edge.from}/${edge.to}`} d={path} data-active={activeEdge || undefined} markerEnd={`url(#${markerId})`} />
              })}
            </svg>
            {diagram.columns.map((nodes, column) => <div key={acquisitionColumns[column]} className="acquisition-column" style={{ left: offsets[column], width: cardWidth }}>
              <div className="acquisition-column-label"><span>0{column + 1}</span>{acquisitionColumns[column]}</div>
              {nodes.map(node => {
                const inputs = diagram.edges.filter(edge => edge.from === node.key && edge.label)
                const labels = Array.from(new Set(inputs.map(edge => edge.label)))
                return <div className="acquisition-card-position" key={node.key} style={{ top: layout.positions.get(node.key)!.y, height: cardHeight }}>
                  {nodeCard(node)}
                  {labels.length > 0 && <span className="acquisition-quantity" title={labels.join(' / ')}>{labels.length === 1 ? labels[0] : '数量は経路ごとに異なります'}</span>}
                </div>
              })}
              {nodes.length === 0 && <p className="acquisition-empty-column">該当なし</p>}
            </div>)}
          </div>
        </div>
      </div> : <div className="acquisition-list">
        {diagram.columns.map((nodes, column) => <section key={acquisitionColumns[column]}>
          <h4>{acquisitionColumns[column]}</h4>
          {nodes.length ? <ul>{nodes.map(node => <li key={node.key}>
            {nodeCard(node)}
            {diagram.edges.filter(edge => edge.to === node.key).map(edge => <span key={edge.from} className="acquisition-list-connection">← {diagram.columns.flat().find(entry => entry.key === edge.from)?.label}{edge.label ? ` · ${edge.label}` : ''}</span>)}
          </li>)}</ul> : <p>該当なし</p>}
        </section>)}
      </div>}
      <p className="acquisition-footnote">カードを選ぶと詳細を表示します。設定値はマスター上の値です。実際の出現場所や入手を保証しません。</p>
    </>}
  </section>
}

export default AcquisitionGraph
