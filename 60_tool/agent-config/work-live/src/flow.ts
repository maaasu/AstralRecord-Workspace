import type { Actor, DisplayScope, IconName, LiveRequest, StepCount } from '../types/index.d.ts'
import { flowMotionDefs, flowMotionStyle, recent, returnParticle, runningParticles } from './flow-motion.ts'
import { actorIcon, iconBody, iconColor, iconLabel } from './icons.ts'
import { displayText, fingerprint, FLOW_WINDOW, oneLine, shortName, stepCountCategory } from './model.ts'

const MINUTE = 60_000
const PARENT_GRACE = 5 * MINUTE
const IDLE_AFTER = 3 * MINUTE
export type Status = LiveRequest['status'] | 'idle'
export type FlowRequest = {
  request: LiveRequest
  from: Actor
  to: Actor
  status: Status
  idleMinutes: number
  children: FlowRequest[]
}
export type Flow = { root: FlowRequest; activityAt: number; status: Status; running: number }
export type FlowInput = { actors: readonly Actor[]; requests: readonly LiveRequest[]; now: number }

export function needsTimeRefresh(requests: readonly LiveRequest[], previousTick: number): boolean {
  return requests.some(request => request.status === 'running' && previousTick < activityAt(request) + IDLE_AFTER)
}

export function activityAt(request: LiveRequest): number {
  return Math.max(request.at, request.lastStepAt ?? 0, request.endedAt ?? 0)
}

function isRootCaller(actor?: Actor): boolean {
  return !actor || actor.kind === 'user' || actor.kind === 'unknown'
}

function chronologicalRequests(requests: readonly LiveRequest[], names: Map<string, Actor>, now: number): LiveRequest[] {
  const byTime = new Map<number, LiveRequest[]>()
  for (const request of requests) {
    if (request.at > now) continue
    const bucket = byTime.get(request.at) ?? []
    bucket.push(request)
    byTime.set(request.at, bucket)
  }
  const ordered: LiveRequest[] = []
  for (const [, bucket] of [...byTime].sort(([a], [b]) => a - b)) {
    // Several events can share a polling timestamp. Place the caller's request first.
    while (bucket.length) {
      const ready = bucket.findIndex(request => request.parentId !== undefined
        ? !bucket.some(candidate => candidate.id === request.parentId)
        : isRootCaller(names.get(request.from)) ||
          !bucket.some(candidate => candidate !== request && candidate.to === request.from))
      ordered.push(bucket.splice(Math.max(0, ready), 1)[0]!)
    }
  }
  return ordered
}

export function groupFlows({ actors, requests, now }: FlowInput): Flow[] {
  const names = new Map(actors.map(actor => [actor.id, actor]))
  const flows: Flow[] = []
  const incoming = new Map<string, { node: FlowRequest; flow: Flow }[]>()
  const byId = new Map<string, { node: FlowRequest; flow: Flow }>()
  for (const request of chronologicalRequests(requests, names, now)) {
    const from = names.get(request.from) ?? { id: request.from, kind: 'unknown', label: '不明な呼び出し元' }
    const to = names.get(request.to) ?? { id: request.to, kind: 'unknown', label: '不明' }
    const idleMinutes = Math.floor((now - Math.max(request.at, request.lastStepAt ?? 0)) / MINUTE)
    const status = request.status === 'running' && idleMinutes >= IDLE_AFTER / MINUTE ? 'idle' : request.status
    const node: FlowRequest = { request, from, to, status, idleMinutes, children: [] }
    const parent = request.parentId !== undefined ? byId.get(request.parentId) : isRootCaller(from) ? undefined
      : incoming.get(request.from)?.findLast(({ node }) =>
        node.request.endedAt === undefined || request.at <= node.request.endedAt + PARENT_GRACE)
    const activity = activityAt(request)
    const flow = parent?.flow ?? { root: node, activityAt: activity, status, running: 0 }
    if (parent) parent.node.children.push(node)
    else flows.push(flow)
    flow.activityAt = Math.max(flow.activityAt, activity)
    if (status === 'running') flow.running++
    if (status === 'error' || (flow.status !== 'error' && status === 'running') ||
      (flow.status === 'done' && (status === 'idle' || status === 'stopped'))) flow.status = status
    const parents = incoming.get(request.to) ?? []
    parents.push({ node, flow })
    byId.set(request.id, { node, flow })
    incoming.set(request.to, parents)
  }
  return flows.sort((a, b) => b.activityAt - a.activityAt || b.root.request.at - a.root.request.at)
}

export function visibleFlows(flows: readonly Flow[], now: number, scope: DisplayScope): Flow[] {
  return flows.filter(flow => flow.activityAt >= now - FLOW_WINDOW &&
    (scope === 'running' ? flow.running > 0 : scope === 'all' || flow.activityAt >= now - 60 * MINUTE))
}

export function flowRows(root: FlowRequest) {
  const rows: { node: FlowRequest; depth: number; number: number; parent?: number; treePrefix: string }[] = []
  function visit(node: FlowRequest, depth: number, stem: string, last: boolean, parent?: number) {
    const index = rows.length
    rows.push({ node, depth, number: index + 1, parent, treePrefix: stem + (last ? '└─' : '├─') })
    node.children.forEach((child, i) => visit(child, depth + 1, stem + (last ? '    ' : '│   '),
      i === node.children.length - 1, index))
  }
  visit(root, 0, '', true)
  return rows
}

export function durationText(request: LiveRequest, now: number): string {
  const end = request.endedAt ?? (request.status === 'running' ? now : activityAt(request))
  const minutes = Math.max(0, Math.floor((end - request.at) / MINUTE))
  const hours = Math.floor(minutes / 60)
  const remainder = minutes % 60
  return hours ? hours + ' 時間' + (remainder ? ' ' + remainder + ' 分' : '') : minutes + ' 分'
}

function requestLine({ status, request }: FlowRequest): { prefix: string; text: string; icon?: IconName } {
  if (status === 'running' && request.lastStep) {
    return { prefix: 'いま: ', text: request.lastStep.title, icon: request.lastStep.icon }
  }
  if (request.status !== 'running' && request.result) return { prefix: '結果: ', text: request.result }
  return { prefix: '', text: request.summary }
}

export function buildFlowTree(root: FlowRequest, now: number): string[] {
  return [shortName(root.from.label), ...flowRows(root).map(({ node, number, treePrefix }) => {
    const { prefix, text } = requestLine(node)
    return treePrefix + number + '. ' + node.request.verb + ' → ' + shortName(node.to.label) +
      ' [' + iconLabel(node.status) + '] ' + (prefix || (node.request.status === 'running' ? 'いま: ' : '結果: ')) +
      text + ' （' + durationText(node.request, now) + '）'
  })]
}

const nodeWidth = 208
const nodeHeight = 92
const gutter = 160
const rowGap = 20
const inset = 16
const labelHeight = 34
const labelRight = 8
const labelClearance = 10
const maxLabelWidth = gutter - labelRight - labelClearance - 8
const badgePitch = 19
const statusWidth = 56
const detailedEdges = 6
const svgLimit = 131_072

type Rect = { x: number; y: number; width: number; height: number }
type Entry = { node: FlowRequest; number: number }
type Card = {
  actor: Actor
  incoming: Entry[]
  children: Card[]
  rows: number
  primary?: FlowRequest
  bounds: Rect
  kindText: string
  kindX: number
  statusX: number
}
type Arrow = {
  from: Card
  to: Card
  label: Rect
  bendX: number
  numbers: string[]
  badgeWidth: number
  verb: string
  summary: string
}

function textWidth(value: string, size: number): number {
  return Array.from(value).reduce((sum, char) => sum +
    (/^[\x20-\x7E]$/u.test(char) && !/[MW@%]/u.test(char) ? size * 0.68 : size), 0)
}

function fit(value: string, size: number, width: number, limit = Infinity): string {
  const clean = oneLine(displayText(value), value.length)
  const chars = Array.from(clean)
  if (chars.length <= limit && textWidth(clean, size) <= width) return clean
  let text = ''
  for (const char of chars.slice(0, limit - 1)) {
    if (textWidth(text + char + '…', size) > width) break
    text += char
  }
  return text + '…'
}

const statusRank: Record<Status, number> = { running: 3, error: 2, idle: 1, stopped: 1, done: 0 }

function makeCard(actor: Actor, incoming: Entry[], children: Card[]): Card {
  const primary = incoming.reduce<FlowRequest | undefined>((best, { node }) => {
    if (!best || statusRank[node.status] > statusRank[best.status] ||
      (node.status === best.status && activityAt(node.request) >= activityAt(best.request))) return node
    return best
  }, undefined)
  return {
    actor, incoming, children, primary, rows: children.reduce((sum, child) => sum + child.rows, 0) || 1,
    bounds: { x: 0, y: 0, width: nodeWidth, height: nodeHeight },
    kindText: '', kindX: 0, statusX: 0,
  }
}

function childCards(entries: Entry[], numbers: Map<FlowRequest, number>): Card[] {
  const byRecipient = new Map<string, Entry[]>()
  for (const entry of entries) {
    const group = byRecipient.get(entry.node.to.id) ?? []
    group.push(entry)
    byRecipient.set(entry.node.to.id, group)
  }
  return [...byRecipient.values()].map(group => {
    const children = group.flatMap(({ node }) => node.children.map(child => ({
      node: child, number: numbers.get(child)!,
    })))
    return makeCard(group[0]!.node.to, group, childCards(children, numbers))
  })
}

function arrowLabel(to: Card) {
  const request = to.primary!.request
  const count = to.incoming.length
  const numbers = to.incoming.slice(0, 3).map(({ number }) => String(number))
  const overflow = count > 3 ? '+' + (count - 3) : ''
  const badgeWidth = numbers.length * badgePitch + (overflow ? 16 : 0)
  if (overflow) numbers.push(overflow)
  const verb = fit(request.verb, 11, maxLabelWidth - badgeWidth - 8)
  const summary = fit(request.summary, 10, maxLabelWidth, 12)
  return {
    numbers, badgeWidth, verb, summary,
    width: Math.ceil(Math.max(badgeWidth + textWidth(verb, 11) + 8, textWidth(summary, 10))),
  }
}

export function layoutFlow(root: FlowRequest) {
  const numbers = new Map(flowRows(root).map(({ node, number }) => [node, number]))
  const tree = makeCard(root.from, [], childCards([{ node: root, number: 1 }], numbers))
  const cards: Card[] = []
  const arrows: Arrow[] = []
  let columns = 0

  function place(card: Card, depth: number, top: number) {
    columns = Math.max(columns, depth + 1)
    cards.push(card)
    let next = top
    for (const child of card.children) {
      place(child, depth + 1, next)
      next += child.rows
    }
    const first = card.children[0]
    const last = card.children.at(-1)
    const x = inset + depth * (nodeWidth + gutter)
    const y = first && last ? (first.bounds.y + last.bounds.y) / 2 : inset + top * (nodeHeight + rowGap)
    card.bounds = { x, y, width: nodeWidth, height: nodeHeight }
    card.kindX = x + 30
    card.statusX = x + nodeWidth - 8 - statusWidth
    card.kindText = fit(iconLabel(actorIcon(card.actor.kind)), 12, card.statusX - card.kindX - 6)

    const labels = card.children.map(arrowLabel)
    // Keep the shared bend at mid-gap when possible and move it left to clear wider labels.
    const bendX = Math.min(x + nodeWidth + gutter / 2,
      x + nodeWidth + gutter - labelRight - labelClearance - Math.max(0, ...labels.map(label => label.width)))
    card.children.forEach((child, index) => {
      const { width, ...label } = labels[index]!
      arrows.push({
        from: card, to: child, bendX, ...label,
        label: { x: child.bounds.x - labelRight - width, y: child.bounds.y + nodeHeight / 2 - labelHeight,
          width, height: labelHeight },
      })
    })
  }

  place(tree, 0, 0)
  const rows = tree.rows
  return {
    cards, arrows, columns, rows,
    width: inset * 2 + columns * nodeWidth + (columns - 1) * gutter,
    height: inset * 2 + rows * nodeHeight + (rows - 1) * rowGap,
  }
}

const entities: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }
function xml(value: string): string {
  return oneLine(value, value.length).replace(/[&<>"']/gu, char => entities[char] ?? char)
}

function svgIcon(icon: IconName, x: number, y: number, size: number, animated = false): string {
  return `<g transform="translate(${x} ${y}) scale(${size / 16})">${iconBody(icon, animated)}</g>`
}

function svgText(value: string, x: number, y: number, size: number, color: string, attributes = ''): string {
  return `<text x="${x}" y="${y}" font-size="${size}" fill="${color}"${attributes}>${xml(value)}</text>`
}

function requestTitle(node: FlowRequest, now: number): string {
  const { from, to, status, request } = node
  return from.label + ' が ' + to.label + ' に' + request.verb + '（' + request.summary + '） ' +
    iconLabel(status) + ' ' + durationText(request, now)
}

const gray = '#9CA3AF'
const cardFill = '#1F2937'
const pillColors = { running: '#EF4444', done: '#22C55E', error: '#EF4444', idle: '#6B7280', stopped: '#6B7280', waiting: '#6B7280' }
const countIcons: Record<StepCount, IconName> = {
  read: 'file', search: 'search', edit: 'edit', command: 'terminal', web: 'web', agent: 'agent', delegate: 'delegate', error: 'error',
}

type Effects = { detailed: boolean; enter?: string; step?: string; end?: 'done' | 'error'; endKey?: string }
const noEffects: Effects = { detailed: false }

function animationKey(request: LiveRequest, name: string, at: number, count = 0) {
  return name + '-' + fingerprint(request.id + ':' + at + ':' + count)
}

function once(key: string): string {
  return ' data-once="' + key + '"'
}

function cardEffects(card: Card, now: number, detailed: boolean, animate: boolean): Effects {
  const { primary, incoming } = card
  if (!animate || !primary) return noEffects
  const { status, request } = primary
  const end = (status === 'done' || status === 'error') && recent(now, request.endedAt) ? status : undefined
  const entering = incoming.findLast(({ node }) => recent(now, node.request.at))?.node.request
  return {
    detailed, end,
    enter: entering && animationKey(entering, 'enter', entering.at),
    step: status === 'running' && recent(now, request.lastStep?.at)
      ? animationKey(request, 'step', request.lastStep!.at, request.stepCount) : undefined,
    endKey: end && animationKey(request, end, request.endedAt!),
  }
}

function typingDots(x: number, y: number): string {
  return `<g class="motion-only" fill="#CBD5E1"><circle class="step-dot" cx="${x}" cy="${y}" r="1.25"/>` +
    `<circle class="step-dot dot-next" cx="${x + 5}" cy="${y}" r="1.25"/><circle class="step-dot dot-last" cx="${x + 10}" cy="${y}" r="1.25"/></g>`
}

function renderCard(card: Card, now: number, focus: string | undefined, related: Set<string>, id: string, effects: Effects, animate: boolean): string {
  const { actor, bounds: { x, y }, primary, incoming, children, kindText, kindX, statusX } = card
  const icon = actorIcon(actor.kind)
  const color = iconColor(icon)
  const status = primary?.status ?? 'waiting'
  const outgoing = children.flatMap(child => child.incoming)
  const details = (incoming.length ? incoming : outgoing).map(({ node }) => requestTitle(node, now)).join(' / ')
  const title = actor.label + (details ? ' / ' + details : ' / 待機')
  const canMove = actor.kind !== 'user'
  const { detailed, enter, step: stepChanged, end, endKey } = canMove ? effects : noEffects
  const dash = canMove && status === 'idle' ? ' class="idle-border" stroke-dasharray="2 3"' : ''
  const outline = detailed
    ? `<rect class="card-halo motion-only" x="${x - 2}" y="${y - 2}" width="${nodeWidth + 4}" height="${nodeHeight + 4}" rx="12" fill="none" stroke="${color}" opacity="0"/>` : ''
  const borderShape = `x="${x}" y="${y}" width="${nodeWidth}" height="${nodeHeight}" rx="10" fill="none"`
  const border = `<rect ${borderShape} stroke="${color}" stroke-width="${focus === actor.id ? 3 : 1}"${dash}/>`
  const borderFlash = end ? `<rect${once(endKey!)} class="${end === 'done' ? 'done-border' : 'error-flash'} motion-only" ${borderShape} stroke="${pillColors[end]}" opacity="0"/>` : ''
  const band = `<path d="M${x} ${y + 24} V${y + 10} q0 -10 10 -10 h188 q10 0 10 10 v14Z" fill="${color}" fill-opacity=".25"/>`
  const sheen = detailed ? `<g class="motion-only" transform="translate(${x} ${y})"><g clip-path="url(#${id}-band)"><rect class="card-sheen" width="70" height="24" fill="url(#${id}-sheen)"/></g></g>` : ''
  const statusDot = status === 'running'
    ? `<circle${detailed ? ' class="status-dot"' : ''} cx="${statusX + 7}" cy="${y + 12}" r="2" fill="#111827"/>` : ''
  const badge = `<rect x="${statusX}" y="${y + 4}" width="${statusWidth}" height="16" rx="4" fill="${pillColors[status]}"/>` + statusDot +
    svgText(status === 'waiting' ? '待機' : iconLabel(status), statusX + statusWidth / 2, y + 16, 11,
      status === 'waiting' || status === 'idle' || status === 'stopped' ? '#FFFFFF' : '#111827', ' text-anchor="middle" font-weight="bold"')
  const name = fit(actor.label, 14, nodeWidth - 20) || kindText
  let body = svgText(name, x + 10, y + 43, 14, '#F9FAFB', ' font-weight="bold"')
  if (actor.kind === 'user' || !primary) {
    body += svgText('依頼 ' + outgoing.length + ' 件', x + 10, y + 64, 12, '#CBD5E1')
  } else {
    const { prefix, text, icon: step } = requestLine(primary)
    const start = x + 10 + Math.ceil(textWidth(prefix, 12))
    const textX = start + (step ? 15 : 0)
    let line = prefix ? svgText(prefix, x + 10, y + 62, 12, '#CBD5E1') : ''
    if (step) line += svgIcon(step, start, y + 51, 12, detailed)
    const typing = Boolean(step && detailed)
    const lineWidth = x + nodeWidth - 10 - textX
    const lineText = fit(text, 12, lineWidth)
    const textEnd = Math.ceil(textWidth(lineText, 12))
    const displayWidth = typing ? Math.min(textEnd, lineWidth - 16) : textEnd
    line += svgText(lineText, textX, y + 62, 12, '#CBD5E1',
      displayWidth < textEnd ? ` textLength="${displayWidth}" lengthAdjust="spacingAndGlyphs"` : '')
    if (typing) line += typingDots(textX + displayWidth + 4, y + 60)
    body += stepChanged ? `<g${once(stepChanged)} class="step-enter">${line}</g>` : line

    const duration = durationText(primary.request, now)
    body += svgText(duration, x + 10, y + 81, 11, gray)
    let countX = x + 18 + Math.ceil(textWidth(duration, 11))
    const counts = (Object.keys(countIcons) as StepCount[])
      .filter(category => primary.request.counts[category]).slice(0, 4)
    const changedCount = stepChanged && step ? stepCountCategory(step) : undefined
    for (const category of counts) {
      const count = String(primary.request.counts[category])
      const countWidth = Math.ceil(textWidth(count, 11))
      if (countX + 14 + countWidth > x + nodeWidth - 10) break
      body += svgIcon(countIcons[category], countX, y + 71, 12)
      body += svgText(count, countX + 14, y + 81, 11, gray,
        category === changedCount && stepChanged ? once(stepChanged) + ' class="count-flash"' : '')
      countX += 21 + countWidth
    }
  }
  const opacity = focus && !related.has(actor.id) ? ' opacity="0.45"' : ''
  const heading = band + sheen + svgIcon(icon, x + 8, y + 4, 16) + svgText(kindText, kindX, y + 16, 12, '#E5E7EB') +
    (actor.kind === 'user' ? '' : end === 'done' ? `<g${once(endKey!)} class="done-pop">${badge}</g>` : badge)
  let content = `${outline}<rect x="${x}" y="${y}" width="${nodeWidth}" height="${nodeHeight}" rx="10" fill="${cardFill}"/>${heading}${body}${border}${borderFlash}`
  if (canMove && status === 'idle') content = `<g${animate ? ' class="card-idle"' : ' opacity=".75"'}>${content}</g>`
  if (end === 'error') content = `<g${once(endKey!)} class="card-shake">${content}</g>`
  if (enter) content = `<g${once(enter)} class="card-enter">${content}</g>`
  return `<g data-actor="${xml(actor.id)}"${opacity}><title>${xml(title)}</title>${content}</g>`
}

function arrowPath(arrow: Arrow): string {
  const { from, to, bendX } = arrow
  const startX = from.bounds.x + nodeWidth
  const endX = to.bounds.x
  const startY = from.bounds.y + nodeHeight / 2
  const endY = to.bounds.y + nodeHeight / 2
  if (startY === endY) return `M${startX} ${startY} H${endX}`
  const direction = Math.sign(endY - startY)
  const radius = Math.min(8, Math.abs(endY - startY) / 2)
  return `M${startX} ${startY} H${bendX - radius} Q${bendX} ${startY} ${bendX} ${startY + direction * radius} V${endY - direction * radius} Q${bendX} ${endY} ${bendX + radius} ${endY} H${endX}`
}

function renderArrow(arrow: Arrow, now: number, id: string, markers: Map<string, string>, effects: Effects, animate: boolean): string {
  const { to, numbers, badgeWidth, verb, summary, label: { x, y, width } } = arrow
  const status = to.primary!.status
  const edgeId = id + '-edge-' + to.incoming[0]!.number
  const color = status === 'running' ? iconColor(actorIcon(to.actor.kind)) : status === 'error' ? '#EF4444' : gray
  const title = to.incoming.map(({ node, number }) => number + '. ' + requestTitle(node, now)).join(' / ')
  const markerId = id + '-tip-' + color.slice(1) + (effects.detailed ? '-live' : '')
  if (!markers.has(markerId)) markers.set(markerId,
    `<marker id="${markerId}" viewBox="0 0 8 8" refX="8" refY="4" markerWidth="6" markerHeight="6" orient="auto"><path${effects.detailed ? ' class="edge-tip" stroke="' + color + '" stroke-width="0"' : ''} d="M0 0L8 4L0 8Z" fill="${color}"/></marker>`)
  const dash = status === 'running' ? (animate ? ' class="edge-dash"' : ' stroke-dasharray="6 6"')
    : status === 'idle' ? ' stroke-dasharray="2 3"' : ''
  const d = arrowPath(arrow)
  const path = `<path id="${edgeId}" d="${d}" fill="none" stroke="${color}" stroke-width="1.5"${dash} marker-end="url(#${markerId})"/>`
  const draw = effects.enter ? `<path${once(effects.enter)} class="edge-draw motion-only" d="${d}" pathLength="1" fill="none" stroke="${color}" stroke-width="1.5" opacity="0"/>` : ''
  const particles = effects.detailed ? runningParticles(id, edgeId, color) : ''
  const movingLine = effects.enter ? `<g${once(effects.enter)} class="edge-reveal">${path}${particles}</g>${draw}` : path + particles
  const resultMotion = effects.end === 'done' ? returnParticle(id, edgeId, effects.enter ? .6 : 0, effects.endKey!)
    : effects.end === 'error' ? `<path${once(effects.endKey!)} class="error-flash motion-only${effects.enter ? ' edge-after-draw' : ''}" d="${d}" fill="none" stroke="${pillColors.error}" opacity="0"/>` : ''
  const targetX = to.bounds.x
  const targetY = to.bounds.y + nodeHeight / 2
  const indicator = status === 'done' || status === 'error'
    ? `<circle cx="${targetX - 16}" cy="${targetY}" r="8" fill="#111827"/>` + svgIcon(status, targetX - 22, targetY - 6, 12) : ''
  const resultIcon = effects.end === 'done'
    ? `<g${once(effects.endKey!)} class="done-pop${effects.enter ? ' edge-after-draw' : ''}">${indicator}</g>` : indicator
  const badges = numbers.map((number, index) => {
    const left = x + index * badgePitch
    return number.startsWith('+')
      ? svgText(number, left, y + 13, 10, '#F9FAFB',
        number.length > 2 ? ' textLength="16" lengthAdjust="spacingAndGlyphs"' : '')
      : `<circle cx="${left + 9}" cy="${y + 9}" r="9" fill="${cardFill}" stroke="${color}"/>` +
        svgText(number, left + 9, y + 12.5, number.length > 2 ? 8 : 10, '#FFFFFF', ' text-anchor="middle" font-weight="bold"')
  }).join('')
  const verbX = x + badgeWidth
  const verbBadge = `<rect x="${verbX}" y="${y + 1}" width="${Math.ceil(textWidth(verb, 11)) + 8}" height="16" rx="4" fill="${cardFill}"/>` +
    svgText(verb, verbX + 4, y + 13, 11, '#E5E7EB')
  const label = `<g data-label="${edgeId}"${effects.enter ? once(effects.enter) + ' class="edge-badges"' : ''}>${badges}${verbBadge}${svgText(summary, x + width, y + 30, 10, gray, ' text-anchor="end"')}</g>`
  return `<g><title>${xml(title)}</title>${movingLine}${resultMotion}${resultIcon}${label}</g>`
}

type FlowGraphic = { source: string; width: number; height: number }
const graphics = new Map<string, { version: string; graphic: FlowGraphic }>()

export function cachedFlow(root: FlowRequest, focus: string | undefined, now: number): FlowGraphic {
  const id = root.request.id
  const version = JSON.stringify([focus, flowRows(root).map(({ node, parent }) => {
    const { request, from, to, status } = node
    return [parent, request, from, to, status, durationText(request, now), recent(now, request.at),
      recent(now, request.lastStep?.at), recent(now, request.endedAt)]
  })])
  const cached = graphics.get(id)
  if (cached?.version === version) return cached.graphic
  const graphic = buildFlow(root, focus, now)
  graphics.delete(id)
  graphics.set(id, { version, graphic })
  const oldest = graphics.keys().next().value
  if (graphics.size > 30 && oldest !== undefined) graphics.delete(oldest)
  return graphic
}

export function buildFlow(root: FlowRequest, focus: string | undefined, now: number): FlowGraphic {
  const { cards, arrows, width, height } = layoutFlow(root)
  const related = new Set<string>()
  if (focus !== undefined) {
    related.add(focus)
    for (const { from, to } of arrows) {
      if (from.actor.id === focus) related.add(to.actor.id)
      if (to.actor.id === focus) related.add(from.actor.id)
    }
  }
  const id = 'flow-' + fingerprint(root.request.id).replace(':', '-')
  const running = arrows.filter(({ to }) => to.primary?.status === 'running')
    .sort((a, b) => b.to.primary!.request.at - a.to.primary!.request.at ||
      activityAt(b.to.primary!.request) - activityAt(a.to.primary!.request))
  const detailed = new Set(running.slice(0, detailedEdges).map(({ to }) => to))
  const state = running.length ? 'running' : 'waiting'

  function render(animate: boolean, detailedCards: ReadonlySet<Card>) {
    const effects = new Map(cards.map(card => [card, cardEffects(card, now, detailedCards.has(card), animate)]))
    const markers = new Map<string, string>()
    const edges = arrows.map(arrow => renderArrow(arrow, now, id, markers, effects.get(arrow.to)!, animate)).join('')
    const nodes = cards.map(card => renderCard(card, now, focus, related, id, effects.get(card)!, animate)).join('')
    const badge = svgIcon(state, width - 72, 0, 12, animate && state === 'running') +
      svgText(running.length ? 'LIVE' : '待機中', width - 56, 11, 12, iconColor(state))
    const defs = `<defs>${[...markers.values()].join('')}${animate ? flowMotionDefs(id) : ''}</defs>`
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" font-family="sans-serif">${defs}${animate ? flowMotionStyle() : ''}<rect width="${width}" height="${height}" rx="8" fill="#111827"/>${edges}${nodes}${badge}</svg>`
  }

  let source = render(true, detailed)
  if (source.length > svgLimit && detailed.size) source = render(true, new Set())
  if (source.length > svgLimit) source = render(false, new Set())
  // The browser shows the same requests as a tree when even the static SVG is too large.
  if (source.length > svgLimit) source = ''
  return { source, width, height }
}
