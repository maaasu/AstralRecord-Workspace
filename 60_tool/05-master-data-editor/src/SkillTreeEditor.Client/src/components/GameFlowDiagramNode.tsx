import { memo, useId, useLayoutEffect, useRef, useState, type CSSProperties } from 'react'
import { createPortal } from 'react-dom'
import { Handle, Position, useViewport, type Node, type NodeProps } from '@xyflow/react'
import { gameFlowNoteSize, gameFlowPorts, kindLabels, type GameFlowPort } from '../data/gameFlow'
import { usePanelDocument } from '../docking/dockPanel'
import type { MasterFileSummary } from '../types/masterData'
import { ReferenceIcon, referenceSymbol } from './MasterReferenceCollection'
import { MinecraftText } from './MinecraftText'
import { MinecraftIcon, MinecraftSkinIcon } from './MinecraftIcon'
import { GameFlowNoteResize, type NoteSize } from './GameFlowNoteResize'

export { GameFlowNodeNotes } from './GameFlowNodeNotes'

export interface FlowNodeData extends Record<string, unknown> {
  name: string
  kind: string
  icon?: string
  file?: MasterFileSummary
  skinRevision?: number
  ref?: string
  referenceName?: string
  classNames: string[]
  note?: string
  noteWidth?: number
  noteHeight?: number
  onNoteResize?: (id: string, width: number, height: number) => void
  dimmed: boolean
  missing: boolean
  suggestion?: string
  connectedPorts?: ReadonlySet<GameFlowPort>
}

export const shapeLegend: [string, string][] = [
  ['game_flow', '見開きの本'], ['progression_preset', '胸当て'], ['guide', '折り目付き用紙'], ['mail', '封筒'],
  ['dungeon', '門形'], ['shop', '六角形'], ['item', '角丸の四角'], ['mob', '円'], ['npc', '人物'],
  ['class', 'ひし形'], ['world', '八角形'], ['gathering', '葉形'], ['recipe', '平行四辺形'],
  ['skill', '盾形'], ['mob_spawner', '点線の円'], ['gathering_spawner', '点線の四角'],
  ['action', '右向き矢印'], ['note', '付箋'], ['loot_table', '巻物'], ['loot_pool', '器'],
  ['buff', '上向き矢印'], ['enchant', '星形'], ['set_effect', '重なったカード'], ['pet', 'ハート形'],
  ['fishing_area', '波形'], ['fishing_fish', '魚形'], ['fishing_rules', 'のぼり形'],
]

const tokenSymbols: Record<string, string> = {
  dungeon: '⚑', shop: '⇄', mob: '⚔', item: '◆', gathering: '⛏', world: '◎', class: '◇', skill: '✦', recipe: '⚒',
  mob_spawner: '◌', gathering_spawner: '◌', loot_table: '▦', loot_pool: '▦', fishing_area: '♒', fishing_fish: '♒',
  fishing_rules: '♒', action: '▶', mail: '✉', enchant: '✧', buff: '↑', set_effect: '▣', pet: '♥',
  game_flow: '▤', progression_preset: '♜',
}

const positionByPort: Record<GameFlowPort, Position> = {
  top: Position.Top, right: Position.Right, bottom: Position.Bottom, left: Position.Left,
}

function FlowNodeIcon({ data }: { data: FlowNodeData }) {
  if (data.icon?.trim()) return <MinecraftIcon icon={data.icon} />
  const masterIcon = data.file?.icon || data.file?.iconModel ? <ReferenceIcon file={data.file} kind={data.kind} /> : null
  if (data.kind === 'npc') {
    return <MinecraftSkinIcon texture={data.file?.skinTexture} revision={data.skinRevision} className="game-flow-token-skin"
      fallback={masterIcon ?? <span className="game-flow-token-person" aria-hidden="true" />} />
  }
  if (masterIcon) return masterIcon
  if (data.kind === 'guide') return <span className="game-flow-token-book" aria-hidden="true" />
  return <span className="game-flow-token-symbol" aria-hidden="true">{tokenSymbols[data.kind] ?? referenceSymbol(data.kind) ?? '◆'}</span>
}

const FlowNodeView = memo(function FlowNodeView({ id, data, selected, isConnectable }: NodeProps<Node<FlowNodeData>>) {
  const [previewSize, setPreviewSize] = useState<NoteSize | null>(null)
  const isNote = data.kind === 'note'
  const resize = isNote && selected && data.onNoteResize && !data.suggestion ? data.onNoteResize : undefined
  const { width, height } = resize && previewSize ? previewSize
    : { width: data.noteWidth ?? gameFlowNoteSize.width, height: data.noteHeight ?? gameFlowNoteSize.height }
  const noteStyle = isNote ? {
    '--note-width': `${width}px`,
    '--note-height': `${height}px`,
    // 補足がある場合は本文が枠を使い切らないよう、残りの領域を確保する。
    '--note-lines': Math.max(1, Math.floor((height - (data.note ? 16 : 12)) / (data.note ? 31.2 : 15.6))),
  } as CSSProperties : undefined
  const canConnect = isConnectable && !data.suggestion
  const labelPosition = !data.connectedPorts?.has('top') ? 'top' : data.connectedPorts.has('bottom') ? 'side' : 'bottom'
  const summary = !isNote && !data.suggestion && !data.dimmed && !data.connectedPorts?.has('bottom') ? data.note : undefined
  return <div className={`game-flow-token${selected ? ' selected' : ''}${data.dimmed ? ' dimmed' : ''}${data.missing ? ' missing' : ''}${data.suggestion ? ' ghost' : ''}`}
    data-kind={data.kind} data-label-position={labelPosition} style={noteStyle}>
    <div className="game-flow-token-name">
      {data.suggestion && <span className="game-flow-ghost-badge">＋ {data.suggestion}</span>}
      {isNote ? <span className="game-flow-token-kind">メモ</span> : <MinecraftText value={data.name} />}
    </div>
    <div className="game-flow-token-shape" data-shape={data.kind}>
      <span className="game-flow-token-fill" aria-hidden="true" />
      {isNote
        ? <span className="game-flow-token-note-content" data-detailed={data.note ? 'true' : undefined}>
          <span className="game-flow-note-heading">
            {data.icon?.trim() && <MinecraftIcon icon={data.icon} className="game-flow-note-icon" />}
            <span className="game-flow-token-note"><MinecraftText value={data.name} /></span>
          </span>
          {data.note && <span className="game-flow-note-detail">{data.note}</span>}
        </span>
        : <span className="game-flow-token-icon"><FlowNodeIcon data={data} /></span>}
      {summary && <span className="game-flow-token-summary" aria-hidden="true">{summary}</span>}
      {data.missing && <span className="game-flow-token-warning" aria-hidden="true">!</span>}
      {gameFlowPorts.map(port => <Handle key={port} id={port} type="source" position={positionByPort[port]}
        isConnectable={canConnect} className="game-flow-port" aria-hidden="true" />)}
      {resize && <GameFlowNoteResize width={width} height={height} onPreview={setPreviewSize}
        onCommit={size => resize(id, size.width, size.height)} />}
    </div>
  </div>
})

export const gameFlowNodeTypes = { flow: FlowNodeView }

/** 別ウィンドウに移したパネルでも、図の拡大率とクリップを引き継がずに詳細を表示する。 */
export function FlowNodeTooltip({ anchor, data }: { anchor: HTMLElement; data: FlowNodeData }) {
  const panelDocument = usePanelDocument()
  const panelWindow = panelDocument.defaultView!
  const element = useRef<HTMLDivElement>(null)
  const id = useId()
  const { x, y, zoom } = useViewport()
  useLayoutEffect(() => {
    const tooltip = element.current
    if (!tooltip) return
    const margin = 8
    const gap = 12
    const place = () => {
      const rect = anchor.querySelector('.game-flow-token-shape')?.getBoundingClientRect() ?? anchor.getBoundingClientRect()
      const canvas = anchor.closest('.react-flow')?.getBoundingClientRect()
      const visible = !canvas || (rect.right >= canvas.left && rect.left <= canvas.right
        && rect.bottom >= canvas.top && rect.top <= canvas.bottom)
      tooltip.style.visibility = visible ? 'visible' : 'hidden'
      const { width, height } = tooltip.getBoundingClientRect()
      const rightSideFits = rect.right + gap + width <= panelWindow.innerWidth - margin
      const leftSideFits = rect.left - gap - width >= margin
      const beside = rightSideFits || leftSideFits
      const left = rightSideFits ? rect.right + gap : leftSideFits ? rect.left - gap - width : rect.left
      const top = beside ? rect.top : rect.bottom + gap + height <= panelWindow.innerHeight - margin
        ? rect.bottom + gap : rect.top - gap - height
      tooltip.style.left = `${Math.max(margin, Math.min(left, panelWindow.innerWidth - width - margin))}px`
      tooltip.style.top = `${Math.max(margin, Math.min(top, panelWindow.innerHeight - height - margin))}px`
    }
    place()
    const observer = new panelWindow.ResizeObserver(place)
    observer.observe(tooltip)
    observer.observe(anchor)
    panelWindow.addEventListener('resize', place)
    panelWindow.addEventListener('scroll', place, true)
    return () => {
      observer.disconnect()
      panelWindow.removeEventListener('resize', place)
      panelWindow.removeEventListener('scroll', place, true)
    }
  }, [anchor, panelWindow, x, y, zoom])
  useLayoutEffect(() => {
    const description = anchor.getAttribute('aria-describedby')
    anchor.setAttribute('aria-describedby', description ? `${description} ${id}` : id)
    return () => {
      if (description) anchor.setAttribute('aria-describedby', description)
      else anchor.removeAttribute('aria-describedby')
    }
  }, [anchor, id])

  return createPortal(<div ref={element} id={id} role="tooltip" className="game-flow-node-tooltip" data-kind={data.kind}>
    <div className="game-flow-tooltip-kind">{kindLabels[data.kind] ?? data.kind}</div>
    <strong><MinecraftText value={data.name} /></strong>
    <dl>
      {data.ref && <><dt>参照</dt><dd>
        {data.referenceName && data.referenceName !== data.ref && <MinecraftText value={data.referenceName} />}
        <code>{data.ref}</code>
      </dd></>}
      {data.note && <><dt>補足</dt><dd className="game-flow-tooltip-note">{data.note}</dd></>}
      <dt>対象職業</dt><dd>{data.classNames.join('・') || '全職業共通'}</dd>
      {data.suggestion && <><dt>候補</dt><dd>{data.suggestion}（クリックで配置）</dd></>}
    </dl>
    {data.missing && <p className="game-flow-node-warning">参照先が見つかりません</p>}
  </div>, panelDocument.body)
}
