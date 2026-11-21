import type { Actor, ClaudeSession, DisplayScope } from '../types/index.d.ts'
import { buildFlowTree, cachedFlow, flowRows, groupFlows, visibleFlows } from './flow.ts'
import type { FlowRequest } from './flow.ts'
import { actorIcon, iconLabel } from './icons.ts'
import { codeText, displayText, shortName } from './model.ts'
import { claudeActor } from './observe.ts'
import { ALL_SESSIONS, involves, sessionFlows, sessionView, TAB_LIMIT } from './session-view.ts'
import { detailOf } from './state.ts'
import type { Draft } from './state.ts'

export function displayScope(value: string | null): DisplayScope {
  return value === 'running' || value === 'all' ? value : 'hour'
}

function actorName(actor: Actor, short = false) {
  const name = displayText(actor.label) || iconLabel(actorIcon(actor.kind))
  return short ? shortName(name) : name
}

function statusText(node: FlowRequest, detail = false) {
  if (node.status === 'error') return 'エラーあり'
  if (node.status === 'idle') return '応答なし ' + node.idleMinutes + ' 分'
  if (node.status === 'stopped' && detail) return '停止（結果不明）'
  return iconLabel(node.status) + (node.status === 'running' && !detail ? '・' + node.request.stepCount + ' 件' : '')
}

export function boardFor(draft: Draft, sessions: readonly ClaudeSession[], selection: string | null, scope: DisplayScope, now: number) {
  const all = visibleFlows(groupFlows({ ...draft.value, now }), now, 'all')
  const relatedIds = new Set(all.flatMap(({ root }) => flowRows(root).flatMap(({ node }) => [node.from.id, node.to.id])))
  const actors = new Map(draft.value.actors.filter(actor => relatedIds.has(actor.id)).map(actor => [actor.id, actor]))
  const processes = new Map(sessions.filter(item => item.isRunning).map(item => ['claude:' + item.id, item]))
  for (const [id, item] of processes) {
    if (!actors.has(id)) actors.set(id, claudeActor(item.id, item.label))
  }
  const { tabs, selected, session } = sessionView([...actors.values()], all, selection)
  const selectedFlows = sessionFlows(all, session)
  const includes = (node: FlowRequest) => session === ALL_SESSIONS || involves(node.request, session)
  const activeCount = selectedFlows.flatMap(flow => flowRows(flow.root))
    .filter(({ node }) => node.status === 'running' && includes(node)).length
  return {
    session, scope, activeCount, tabLimit: TAB_LIMIT, hasFlows: all.length > 0, monitors: draft.value.monitors,
    tabs: tabs.map(tab => ({
      id: tab.actor.id, label: actorName(tab.actor), icon: actorIcon(tab.actor.kind), status: tab.status,
      processRunning: processes.has(tab.actor.id),
      statusText: tab.status === 'running' ? '実行中・' + tab.stepCount + ' 件'
        : tab.latest ? statusText(tab.latest) : processes.has(tab.actor.id) ? '起動中' : '待機中',
    })),
    heading: selected && {
      label: actorName(selected.actor), icon: actorIcon(selected.actor.kind),
      first: processes.get(selected.actor.id)?.startedAt ?? selected.first,
      incoming: selected.incoming.map(({ from, count }) => ({ from: actorName(from, true), count })),
      outgoing: selected.outgoing,
    },
    flows: visibleFlows(selectedFlows, now, scope).map(({ root, status }) => {
      const { source } = cachedFlow(root, selected?.actor.id, now)
      return {
        id: root.request.id, summary: displayText(root.request.summary) || '作業', at: root.request.at, status,
        svg: source, tree: source ? [] : buildFlowTree(root, now),
        rows: flowRows(root).map(({ node, number, depth }) => {
          const { request, from, to } = node
          const verb = displayText(request.verb)
          return {
            id: request.id, number, depth, status: node.status, statusText: statusText(node),
            detailStatusText: statusText(node, true), dimmed: !includes(node), fromIcon: actorIcon(from.kind),
            toIcon: actorIcon(to.kind), at: request.at, endedAt: request.endedAt, detailVersion: request.detailVersion ?? 0,
            label: actorName(from, true) + ' が ' + actorName(to, true) + ' に' + verb,
            fullLabel: actorName(from) + ' が ' + actorName(to) + ' に' + verb + (request.estimatedObservation ? '（推定）' : ''),
          }
        }),
      }
    }),
  }
}

export function detailFor(draft: Draft, id: string) {
  if (!draft.value.requests.some(request => request.id === id)) return undefined
  const detail = detailOf(draft, id)
  return {
    prompt: codeText(detail.prompt),
    promptSource: codeText(detail.promptSource),
    steps: detail.steps.map(step => ({ ...step, title: displayText(step.title) || '作業', full: codeText(step.full) })),
  }
}
