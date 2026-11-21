import type { Actor, LiveRequest } from '../types/index.d.ts'
import { activityAt, flowRows } from './flow.ts'
import type { Flow, FlowRequest, Status } from './flow.ts'

export const ALL_SESSIONS = 'all'
export const TAB_LIMIT = 8

export function involves(request: LiveRequest, id: string): boolean {
  return request.from === id || request.to === id
}

export function sessionFlows(flows: readonly Flow[], session: string): Flow[] {
  return flows.filter(({ root }) => session === ALL_SESSIONS ||
    flowRows(root).some(({ node }) => involves(node.request, session)))
}

function sessionKind({ kind }: Actor): boolean {
  return kind === 'claude' || kind === 'codex'
}

function sessionInfo(actor: Actor, related: readonly FlowRequest[]) {
  const incoming = related.filter(({ request }) => request.to === actor.id)
  const outgoing = related.filter(({ request }) => request.from === actor.id)
  const ordered = [...related].sort((a, b) => activityAt(b.request) - activityAt(a.request))
  const own = incoming.length ? ordered.filter(({ request }) => request.to === actor.id) : ordered
  const active = own.filter(({ status }) => status === 'running')
  const latest = active.at(0) ?? own.at(0)
  const status: Status | 'waiting' = latest?.status ?? 'waiting'
  const received = new Map<string, { from: Actor; count: number }>()
  for (const { from } of incoming) {
    const count = (received.get(from.id)?.count ?? 0) + 1
    received.set(from.id, { from, count })
  }
  const first = related.reduce<number | undefined>((start, { request }) =>
    start === undefined ? request.at : Math.min(start, request.at), undefined)
  return { actor, status, latest, first, incoming: [...received.values()], outgoing: outgoing.length,
    activity: ordered[0] ? activityAt(ordered[0].request) : -Infinity,
    stepCount: active.reduce((total, { request }) => total + request.stepCount, 0) }
}

export function sessionView(actors: readonly Actor[], flows: readonly Flow[], selection: string | null) {
  const sessions = new Map(actors.filter(sessionKind).map(actor => [actor.id, { actor, related: [] as FlowRequest[] }]))
  for (const { root } of flows) {
    for (const { node } of flowRows(root)) {
      for (const id of new Set([node.request.from, node.request.to])) sessions.get(id)?.related.push(node)
    }
  }
  const tabs = [...sessions.values()].map(({ actor, related }) => sessionInfo(actor, related))
    .sort((a, b) => Number(b.status === 'running') - Number(a.status === 'running') || b.activity - a.activity)
  const selected = selection === ALL_SESSIONS ? undefined : tabs.find(tab => tab.actor.id === selection) ?? tabs[0]
  return { tabs, selected, session: selected?.actor.id ?? ALL_SESSIONS }
}

export type SessionTab = ReturnType<typeof sessionInfo>
