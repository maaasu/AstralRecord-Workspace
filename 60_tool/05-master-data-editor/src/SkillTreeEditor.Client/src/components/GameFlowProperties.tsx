import { useId, useMemo, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  edgeLabelSuggestions, isAnnotationEdge, kindLabels, refId, refKind, suggestedClasses,
  type FlowSuggestion, type GameFlow, type GameFlowContext, type GameFlowEdge, type GameFlowNode,
} from '../data/gameFlow'
import { stripMinecraftFormatting } from '../utils/minecraft'
import {
  flowNodeCopyTarget, flowReferenceCopyTarget,
  type FlowCopyContextMenu, type FlowCopyTarget, type ResolvedRef,
} from './GameFlowContextMenu'
import { ReferenceIcon } from './MasterReferenceCollection'
import { MaterialIconInput } from './MaterialIconInput'
import { MinecraftText } from './MinecraftText'
import { Modal } from './Modal'

type ResolveRef = (ref: string) => ResolvedRef | undefined
type FlowClass = { ref: string; name: string }

interface CopyProps {
  copyContextMenu?: FlowCopyContextMenu
  resolve?: ResolveRef
}

const plain = (value: string) => stripMinecraftFormatting(value).trim()

function classCopyTarget(ref: string, name: string, resolve?: ResolveRef): FlowCopyTarget {
  return { ...flowReferenceCopyTarget(ref, resolve?.(ref)), name: name === ref ? undefined : name }
}

function classesCopyTarget(refs: string[], className: (ref: string) => string, allLabel: string): FlowCopyTarget {
  return { values: [
    { label: '対象職業をコピー', value: refs.length ? refs.map(className).join('・') : allLabel },
    { label: '対象職業の参照をコピー', value: refs.join('\n') },
  ] }
}

function inputCopyTarget(title: string, value?: string | null): FlowCopyTarget {
  return { values: [{ label: `${title}をコピー`, value }] }
}

function FlowIconField({ label, value, placeholder, onChange, copyContextMenu }: {
  label: string; value?: string; placeholder: string; onChange: (value: string) => void; copyContextMenu?: FlowCopyContextMenu
}) {
  const id = useId()
  return <div onContextMenu={copyContextMenu?.(inputCopyTarget(label, value), 'アイコン')}>
    <label htmlFor={id}>{label}</label>
    <MaterialIconInput id={id} value={value ?? ''} placeholder={placeholder} onChange={onChange} />
  </div>
}

export function ClassToggles({ value, classes, onChange, emptyLabel, resolve, copyContextMenu }: {
  value: string[]; classes: FlowClass[]; onChange: (value: string[]) => void; emptyLabel: string
} & CopyProps) {
  const known = new Map(classes.map(entry => [entry.ref, entry.name]))
  const unknown = value.filter(entry => !known.has(entry))
  const className = (ref: string) => known.get(ref) ?? plain(resolve?.(ref)?.name ?? ref)
  return <div className="game-flow-class-toggles">
    {classes.map(entry => <label key={entry.ref} className="game-flow-class-toggle"
      onContextMenu={copyContextMenu?.(classCopyTarget(entry.ref, entry.name, resolve), '職業')}>
      <input type="checkbox" checked={value.includes(entry.ref)}
        onChange={event => onChange(event.target.checked ? [...value, entry.ref] : value.filter(item => item !== entry.ref))} />{entry.name}
    </label>)}
    {unknown.map(entry => <label key={entry} className="game-flow-class-toggle unknown"
      onContextMenu={copyContextMenu?.(classCopyTarget(entry, entry, resolve), '職業')}>
      <input type="checkbox" checked onChange={() => onChange(value.filter(item => item !== entry))} />{entry}
    </label>)}
    <small className="game-flow-muted" onContextMenu={copyContextMenu?.(classesCopyTarget(value, className, emptyLabel), '対象職業')}>
      {value.length ? `${value.length}職業` : emptyLabel}
    </small>
  </div>
}

export function FlowProperties({ flow, path, className, onEditClasses, onChange, resolve, copyContextMenu }: {
  flow: GameFlow; path?: string; className: (ref: string) => string; onEditClasses: () => void; onChange: (change: Partial<GameFlow>) => void
} & CopyProps) {
  const nodes = new Map(flow.nodes.map(node => [node.id, node]))
  const annotationCount = flow.edges.filter(edge => isAnnotationEdge(nodes.get(edge.from), nodes.get(edge.to))).length
  const arrowCount = flow.edges.length - annotationCount
  const classSummary = `対象職業: ${flow.classes.length ? flow.classes.map(className).join('・') : '全職業'}`
  const classTarget = flow.classes.length === 1
    ? classCopyTarget(flow.classes[0], className(flow.classes[0]), resolve)
    : classesCopyTarget(flow.classes, className, '全職業')
  return <section className="game-flow-props">
    <h3>フロー</h3>
    <label onContextMenu={copyContextMenu?.({ ...inputCopyTarget('フローID', flow.id), path }, 'フローID')}>
      ID<input value={flow.id} readOnly title="ID はファイル名と一致させるため変更できません" />
    </label>
    <label onContextMenu={copyContextMenu?.(inputCopyTarget('表示名', flow.name), '表示名')}>
      表示名<input value={flow.name} onChange={event => onChange({ name: event.target.value })} />
    </label>
    <FlowIconField label="フローのアイコン（任意）" value={flow.icon} placeholder="他の図から参照したときに表示"
      copyContextMenu={copyContextMenu} onChange={icon => onChange({ icon })} />
    <label onContextMenu={copyContextMenu?.(inputCopyTarget('説明', flow.description), '説明')}>
      説明<textarea rows={3} value={flow.description ?? ''} onChange={event => onChange({ description: event.target.value })} />
    </label>
    <div className="game-flow-class-summary">
      <span title={classSummary} onContextMenu={copyContextMenu?.(classTarget, '対象職業')}>{classSummary}</span>
      <button type="button" className="button compact" onClick={onEditClasses}>変更</button>
    </div>
    <p className="game-flow-muted">
      <span onContextMenu={copyContextMenu?.(inputCopyTarget('ノード件数', String(flow.nodes.length)), 'ノード件数')}>ノード {flow.nodes.length} 件</span>
      {' / '}
      <span onContextMenu={copyContextMenu?.(inputCopyTarget('矢印の本数', String(arrowCount)), '矢印の本数')}>矢印 {arrowCount} 本</span>
      {' / '}
      <span onContextMenu={copyContextMenu?.(inputCopyTarget('注釈線の本数', String(annotationCount)), '注釈線の本数')}>注釈線 {annotationCount} 本</span>
    </p>
  </section>
}

function SuggestionList({ title, items, resolve, prefixes, onAccept, copyContextMenu }: {
  title: string; items: FlowSuggestion[]; resolve: ResolveRef; prefixes: Readonly<Record<string, string>>
  onAccept: (list: FlowSuggestion[]) => void; copyContextMenu?: FlowCopyContextMenu
}) {
  if (!items.length) return null
  return <div className="game-flow-suggestions">
    <div className="game-flow-suggestions-head"><strong>{title}（{items.length}）</strong>
      <button type="button" className="button compact" onClick={() => onAccept(items)}>すべて配置</button></div>
    <ul>{items.map(item => {
      const resolved = resolve(item.ref)
      const kind = refKind(item.ref, prefixes)
      const target = {
        ...flowReferenceCopyTarget(item.ref, resolved), nodeId: item.existingNodeId,
        values: [{ label: 'ラベルをコピー', value: item.label }],
      }
      return <li key={item.direction + item.ref}>
        <button type="button" className="game-flow-suggestion" title={item.existingNodeId ? '配置済みのノードへ矢印をつなぎます' : 'ノードを配置して矢印でつなぎます'}
          onClick={() => onAccept([item])} onContextMenu={copyContextMenu?.(target, '候補')}>
          <ReferenceIcon file={resolved?.file} kind={kind} />
          <MinecraftText value={resolved?.name ?? item.ref} />
          <small>{item.label}{item.existingNodeId ? '・接続のみ' : ''}</small>
        </button>
      </li>
    })}</ul>
  </div>
}

export function NodeProperties({ node, kind, resolved, classes, context, flow, resolveName, onChange, onPick, onOpenMaster, onShopItems, onDelete,
  suggestions, resolve, prefixes, onAccept, copyContextMenu }: {
  node: GameFlowNode; kind: string; resolved?: ResolvedRef; classes: FlowClass[]
  context: GameFlowContext | null; flow: GameFlow; resolveName: (ref: string) => string | undefined
  onChange: (change: Partial<GameFlowNode>) => void; onPick: (kind: string) => void; onOpenMaster: (path: string) => void
  onShopItems: () => void; onDelete: () => void
  suggestions: { next: FlowSuggestion[]; previous: FlowSuggestion[] } | null
  resolve: ResolveRef; prefixes: Readonly<Record<string, string>>
  onAccept: (list: FlowSuggestion[]) => void; copyContextMenu?: FlowCopyContextMenu
}) {
  const shop = kind === 'shop' ? context?.shops.find(entry => entry.id === refId(node.ref)) : undefined
  const neighbors = new Map(flow.nodes.map(entry => [entry.id, entry]))
  let outgoing = 0
  let incoming = 0
  let annotations = 0
  for (const edge of flow.edges) {
    if (edge.from !== node.id && edge.to !== node.id) continue
    if (isAnnotationEdge(neighbors.get(edge.from), neighbors.get(edge.to))) annotations++
    else if (edge.from === node.id) outgoing++
    else incoming++
  }
  const suggestion = kind === 'item' ? suggestedClasses(context, refId(node.ref)) : []
  const kindLabel = kindLabels[kind] ?? kind
  const title = kind === 'note' ? '付箋' : kind === 'action' ? '操作' : `${kindLabel}のノード`
  const nameLabel = kind === 'note' ? 'メモ' : '表示名'
  const reference = flowReferenceCopyTarget(node.ref, resolved)
  const suggestionName = (ref: string) => resolveName(ref) ?? ref
  return <section className="game-flow-props" data-kind={kind}>
    <h3>{title}</h3>
    <label onContextMenu={copyContextMenu?.({ nodeId: node.id }, 'ノードID')}>
      ノードID<input value={node.id} readOnly />
    </label>
    {node.ref && <div className="game-flow-ref">
      <span className="game-flow-ref-name" onContextMenu={copyContextMenu?.(reference, 'マスター参照')}>
        {resolved ? <><ReferenceIcon file={resolved.file} kind={kind} /><MinecraftText value={resolved.name} /></> : <em>参照先が見つかりません</em>}
      </span>
      <code onContextMenu={copyContextMenu?.(reference, 'マスター参照')}>{node.ref}</code>
      <div className="game-flow-actions">
        <button type="button" className="button compact" onClick={() => onPick(kind)}>参照を変更</button>
        {resolved?.file && <button type="button" className="button compact" onContextMenu={copyContextMenu?.(reference, 'マスター参照')}
          onClick={() => onOpenMaster(resolved.file!.path)}>マスターを開く ↗</button>}
      </div>
    </div>}
    <label onContextMenu={copyContextMenu?.(inputCopyTarget(nameLabel, node.label), nameLabel)}>
      {node.ref ? '表示名の上書き（任意）' : nameLabel}
      <input value={node.label ?? ''} placeholder={node.ref ? '参照先の名前を表示' : ''} onChange={event => onChange({ label: event.target.value })} />
    </label>
    <FlowIconField label={node.ref ? 'アイコンの上書き（任意）' : 'アイコン（任意）'} value={node.icon}
      placeholder={node.ref ? '参照先のアイコンを表示' : '未設定'} copyContextMenu={copyContextMenu} onChange={icon => onChange({ icon })} />
    <label onContextMenu={copyContextMenu?.(inputCopyTarget('補足', node.note), '補足')}>
      補足<textarea rows={3} value={node.note ?? ''} onChange={event => onChange({ note: event.target.value })} />
    </label>
    <fieldset><legend>対象職業</legend>
      <ClassToggles value={node.classes} classes={classes} emptyLabel="未指定: 全職業共通" onChange={value => onChange({ classes: value })}
        resolve={resolve} copyContextMenu={copyContextMenu} />
      {suggestion.length > 0 && suggestion.join() !== node.classes.join() && <button type="button" className="button compact subtle"
        onContextMenu={copyContextMenu?.(classesCopyTarget(suggestion, suggestionName, ''), '装備条件の職業')}
        onClick={() => onChange({ classes: suggestion })}>装備条件の職業にする（{suggestion.map(suggestionName).join('・')}）</button>}
    </fieldset>
    {kind === 'shop' && <div className="game-flow-actions">
      <button type="button" className="button compact primary" disabled={!shop?.items.length} onClick={onShopItems}>商品を購入アイテムとして追加…</button>
      {shop && <small className="game-flow-muted"
        onContextMenu={copyContextMenu?.(inputCopyTarget('商品件数', String(shop.items.length)), '商品件数')}>商品 {shop.items.length} 件</small>}
    </div>}
    {suggestions && <>
      <SuggestionList title="次に置く候補" items={suggestions.next} resolve={resolve} prefixes={prefixes} onAccept={onAccept} copyContextMenu={copyContextMenu} />
      <SuggestionList title="入手元・前の候補" items={suggestions.previous} resolve={resolve} prefixes={prefixes} onAccept={onAccept} copyContextMenu={copyContextMenu} />
    </>}
    <p className="game-flow-muted">
      {kind !== 'note' && <>
        <span onContextMenu={copyContextMenu?.(inputCopyTarget('入る矢印の本数', String(incoming)), '入る矢印')}>入る矢印 {incoming} 本</span>
        {' / '}
        <span onContextMenu={copyContextMenu?.(inputCopyTarget('出る矢印の本数', String(outgoing)), '出る矢印')}>出る矢印 {outgoing} 本</span>
        {' / '}
      </>}
      <span onContextMenu={copyContextMenu?.(inputCopyTarget('注釈線の本数', String(annotations)), '注釈線')}>注釈線 {annotations} 本</span>
    </p>
    {kind === 'note' && <button type="button" className="button compact" onClick={() => onChange({ role: 'action' })}>操作に変更</button>}
    <button type="button" className="button compact danger subtle" onClick={onDelete}>{kind === 'note' ? 'メモを削除' : 'ノードを削除'}</button>
  </section>
}

export function EdgeProperties({ edge, flow, nodeName, classes, onChange, onDelete, resolve, copyContextMenu }: {
  edge: GameFlowEdge; flow: GameFlow; nodeName: (node: GameFlowNode) => string; classes: FlowClass[]
  onChange: (change: Partial<GameFlowEdge>) => void; onDelete: () => void
} & CopyProps) {
  const from = flow.nodes.find(entry => entry.id === edge.from)
  const to = flow.nodes.find(entry => entry.id === edge.to)
  const annotation = isAnnotationEdge(from, to)
  const kindLabel = annotation ? '注釈線' : '矢印'
  const routeLabel = annotation ? '注釈のつながり' : '矢印の向き'
  const separator = annotation ? '—' : '→'
  const fromName = from ? nodeName(from) : edge.from
  const toName = to ? nodeName(to) : edge.to
  const fromText = plain(fromName)
  const toText = plain(toName)
  const routeTarget = inputCopyTarget(routeLabel, fromText && toText ? `${fromText} ${separator} ${toText}` : undefined)
  const endpoint = (node: GameFlowNode | undefined, id: string, name: string, title: string) => {
    const target = node ? flowNodeCopyTarget(node, node.ref ? resolve?.(node.ref) : undefined) : { nodeId: id }
    return <span onContextMenu={copyContextMenu?.({ ...target, values: routeTarget.values }, title)}><MinecraftText value={name} /></span>
  }
  return <section className="game-flow-props" data-kind={annotation ? 'note' : undefined}>
    <h3>{kindLabel}</h3>
    <p className="game-flow-edge-route" onContextMenu={copyContextMenu?.(routeTarget, routeLabel)}>
      {endpoint(from, edge.from, fromName, annotation ? '注釈線の接続先' : '矢印の起点')}
      {` ${separator} `}
      {endpoint(to, edge.to, toName, annotation ? '注釈線の接続先' : '矢印の終点')}
    </p>
    <label onContextMenu={copyContextMenu?.(inputCopyTarget('ラベル', edge.label), 'ラベル')}>
      ラベル<input list={annotation ? undefined : 'game-flow-edge-labels'} value={edge.label ?? ''} onChange={event => onChange({ label: event.target.value })} />
    </label>
    {!annotation && <datalist id="game-flow-edge-labels">{edgeLabelSuggestions.map(label => <option key={label} value={label} />)}</datalist>}
    <label onContextMenu={copyContextMenu?.(inputCopyTarget('補足', edge.note), '補足')}>
      補足<textarea rows={3} value={edge.note ?? ''} onChange={event => onChange({ note: event.target.value })} />
    </label>
    <fieldset><legend>対象職業</legend>
      <ClassToggles value={edge.classes} classes={classes} emptyLabel="未指定: 全職業共通" onChange={value => onChange({ classes: value })}
        resolve={resolve} copyContextMenu={copyContextMenu} />
    </fieldset>
    <button type="button" className="button compact danger subtle" onClick={onDelete}>{kindLabel}を削除</button>
  </section>
}

export function ShopItemsDialog({ document, flow, shopNodeId, context, resolve, className, isItem, onAdd, onClose, resolveClass, copyContextMenu }: {
  document: Document; flow: GameFlow; shopNodeId: string; context: GameFlowContext | null
  resolve: ResolveRef; className: (ref: string) => string
  isItem: (ref: string | undefined, itemId: string) => boolean
  onAdd: (itemIds: string[]) => void; onClose: () => void; resolveClass?: ResolveRef; copyContextMenu?: FlowCopyContextMenu
}) {
  const shopNode = flow.nodes.find(node => node.id === shopNodeId)
  const shop = context?.shops.find(entry => entry.id === refId(shopNode?.ref))
  const connectedRefs = useMemo(() => flow.edges.filter(edge => edge.from === shopNodeId)
    .map(edge => flow.nodes.find(node => node.id === edge.to)?.ref), [flow, shopNodeId])
  const connected = useMemo(() => new Set((shop?.items ?? []).filter(id => connectedRefs.some(ref => isItem(ref, id)))
    .map(id => 'item:' + id)), [connectedRefs, isItem, shop])
  const [query, setQuery] = useState('')
  const [chosen, setChosen] = useState<Set<string>>(new Set())
  const rows = useMemo(() => (shop?.items ?? []).map(id => {
    const resolved = resolve(id)
    return { id, name: resolved?.name ?? id, file: resolved?.file, resolved, classes: suggestedClasses(context, id), done: connected.has('item:' + id) }
  }).filter(row => !query.trim() || plain(row.name).includes(query.trim()) || row.id.includes(query.trim())), [connected, context, query, resolve, shop])
  const selectable = rows.filter(row => !row.done)
  const toggle = (id: string, on: boolean) => setChosen(current => {
    const next = new Set(current)
    if (on) next.add(id)
    else next.delete(id)
    return next
  })
  return createPortal(<Modal ariaLabel="商品を購入アイテムとして追加" className="game-flow-dialog" ownerDocument={document} onClose={onClose}>
    <header className="modal-header"><h2>商品を購入アイテムとして追加</h2></header>
    <div className="modal-body">
      <p className="game-flow-muted">選んだ商品をアイテムのノードとして追加し、ショップから「購入」の矢印でつなぎます。装備条件に職業がある商品は、その職業を対象職業に設定します。</p>
      <div className="game-flow-dialog-tools">
        <input type="search" placeholder="名前・IDで絞り込み" value={query} onChange={event => setQuery(event.target.value)} aria-label="商品を絞り込み" />
        <button type="button" className="button compact" onClick={() => setChosen(new Set(selectable.map(row => row.id)))}>表示中をすべて選択</button>
        <button type="button" className="button compact subtle" onClick={() => setChosen(new Set())}>選択を解除</button>
      </div>
      <ul className="game-flow-shop-items">
        {rows.map(row => <li key={row.id} onContextMenu={copyContextMenu?.(flowReferenceCopyTarget('item:' + row.id, row.resolved), '商品')}>
          <label className={row.done ? 'done' : undefined}>
            <input type="checkbox" disabled={row.done} checked={row.done || chosen.has(row.id)} onChange={event => toggle(row.id, event.target.checked)} />
            <ReferenceIcon file={row.file} kind="item" /><MinecraftText value={row.name} /><code>{row.id}</code>
            {row.classes.length > 0 && <span className="game-flow-chips">{row.classes.map(ref => {
              const name = className(ref)
              return <span key={ref} className="game-flow-chip"
                onContextMenu={copyContextMenu?.(classCopyTarget(ref, name, resolveClass), '職業')}>{name}</span>
            })}</span>}
            {row.done && <small>追加済み</small>}
          </label>
        </li>)}
        {!rows.length && <li className="game-flow-muted">該当する商品がありません。</li>}
      </ul>
    </div>
    <footer className="modal-footer">
      <button type="button" className="button" onClick={onClose}>キャンセル</button>
      <button type="button" className="button primary" disabled={!chosen.size} onClick={() => onAdd((shop?.items ?? []).filter(id => chosen.has(id)))}>{chosen.size}件を追加</button>
    </footer>
  </Modal>, document.body)
}
