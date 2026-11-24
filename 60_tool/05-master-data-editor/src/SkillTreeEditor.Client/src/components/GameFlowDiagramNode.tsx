import { memo, useId, useLayoutEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { Handle, Position, useViewport, type Node, type NodeProps } from '@xyflow/react'
import { gameFlowPorts, kindLabels, type GameFlowPort } from '../data/gameFlow'
import { usePanelDocument } from '../docking/dockPanel'
import type { MasterFileSummary } from '../types/masterData'
import { ReferenceIcon } from './MasterReferenceCollection'
import { MinecraftText } from './MinecraftText'

export interface FlowNodeData extends Record<string, unknown> {
  name: string
  kind: string
  file?: MasterFileSummary
  ref?: string
  referenceName?: string
  classNames: string[]
  note?: string
  dimmed: boolean
  missing: boolean
  suggestion?: string
  connectedPorts?: ReadonlySet<GameFlowPort>
}

export const shapeLegend: [string, string][] = [
  ['dungeon', '門形'], ['shop', '六角形'], ['item', '角丸の四角'], ['mob', '円'], ['npc', '人物'],
  ['class', 'ひし形'], ['world', '八角形'], ['gathering', '葉形'], ['recipe', '平行四辺形'],
  ['skill', '盾形'], ['mob_spawner', '点線の円'], ['action', 'プレイマーク'], ['note', '付箋'],
]

const tokenSymbols: Record<string, string> = {
  dungeon: '⚑', shop: '⇄', mob: '⚔', item: '◆', gathering: '⛏', world: '◎', class: '◇', skill: '✦', recipe: '⚒',
  mob_spawner: '◌', gathering_spawner: '◌', loot_table: '▦', loot_pool: '▦', fishing_area: '♒', fishing_fish: '♒',
  fishing_rules: '♒', action: '▶', mail: '✉', enchant: '✧', buff: '↑', set_effect: '▣', pet: '♥',
}

const positionByPort: Record<GameFlowPort, Position> = {
  top: Position.Top, right: Position.Right, bottom: Position.Bottom, left: Position.Left,
}

function FlowNodeIcon({ data }: { data: FlowNodeData }) {
  if (data.kind === 'npc') return <span className="game-flow-token-person" aria-hidden="true" />
  if (data.kind === 'guide') return <span className="game-flow-token-book" aria-hidden="true" />
  if (data.kind !== 'mail' && (data.file?.icon || data.file?.iconModel)) return <ReferenceIcon file={data.file} kind={data.kind} />
  return <span className="game-flow-token-symbol" aria-hidden="true">{tokenSymbols[data.kind] ?? '◆'}</span>
}

const FlowNodeView = memo(function FlowNodeView({ data, selected, isConnectable }: NodeProps<Node<FlowNodeData>>) {
  const isNote = data.kind === 'note'
  const canConnect = isConnectable && !data.suggestion
  const labelPosition = !data.connectedPorts?.has('top') ? 'top' : data.connectedPorts.has('bottom') ? 'side' : 'bottom'
  return <div className={`game-flow-token${selected ? ' selected' : ''}${data.dimmed ? ' dimmed' : ''}${data.missing ? ' missing' : ''}${data.suggestion ? ' ghost' : ''}`}
    data-kind={data.kind} data-label-position={labelPosition}>
    <div className="game-flow-token-name">
      {data.suggestion && <span className="game-flow-ghost-badge">＋ {data.suggestion}</span>}
      {isNote ? <span className="game-flow-token-kind">メモ</span> : <MinecraftText value={data.name} />}
    </div>
    <div className="game-flow-token-shape" data-shape={data.kind}>
      <span className="game-flow-token-fill" aria-hidden="true" />
      {isNote
        ? <span className="game-flow-token-note"><MinecraftText value={data.name} /></span>
        : <span className="game-flow-token-icon"><FlowNodeIcon data={data} /></span>}
      {data.missing && <span className="game-flow-token-warning" aria-hidden="true">!</span>}
      {gameFlowPorts.map(port => <Handle key={port} id={port} type="source" position={positionByPort[port]}
        isConnectable={canConnect} className="game-flow-port" aria-hidden="true" />)}
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
