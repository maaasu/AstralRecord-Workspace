import type { Actor, LiveRequest } from '../types'
import { activityAt, flowRows } from './flow'
import type { Flow, FlowRequest, Status } from './flow'
import { SELF } from './model'

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
  return kind === 'claude' || kind === 'claude-remote' || kind === 'codex'
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
    stepCount: active.reduce((total, { request }) => total + request.stepCount, 0), hasRequests: related.length > 0 }
}

export function sessionView(actors: readonly Actor[], flows: readonly Flow[], selection: string | null) {
  const sessions = new Map(actors.filter(sessionKind).map(actor => [actor.id, { actor, related: [] as FlowRequest[] }]))
  if (!sessions.has(SELF.id)) sessions.set(SELF.id, { actor: SELF, related: [] })
  for (const { root } of flows) {
    for (const { node } of flowRows(root)) {
      for (const id of new Set([node.request.from, node.request.to])) sessions.get(id)?.related.push(node)
    }
  }
  const self = sessions.get(SELF.id)!
  const current = sessionInfo(self.actor, self.related)
  const tabs = [current, ...[...sessions.values()].filter(({ actor }) => actor.id !== SELF.id)
    .map(({ actor, related }) => sessionInfo(actor, related))
    .sort((a, b) => Number(b.status === 'running') - Number(a.status === 'running') || b.activity - a.activity)]
  const fallback = current.hasRequests ? current : tabs.find(tab => tab.status === 'running') ?? current
  const selected = selection === ALL_SESSIONS ? undefined : tabs.find(tab => tab.actor.id === selection) ?? fallback
  return { tabs, selected, session: selected?.actor.id ?? ALL_SESSIONS }
}

export type SessionTab = ReturnType<typeof sessionInfo>
