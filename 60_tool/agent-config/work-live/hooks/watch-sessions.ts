import type { EngineInterface, McpToolResult } from 'claude-code'
import type { Monitor, RequestStatus, SessionCheckpoint, SessionCursor, Step } from '../types'
import { isNotification, notification, stepOf, unavailable } from './model'
import { sessionKey } from './parse-tool'
import { newSessionEvents, parseSessionList, sessionStep } from './parse-sessions'
import type { SessionRow } from './parse-sessions'

export type SessionTurn = { id: string; at: number; prompt?: string; steps: Step[]; endedAt?: number; status?: Exclude<RequestStatus, 'running'> }
export type SessionObservation = { row: SessionRow; turns: SessionTurn[] }
export const SESSION_WINDOW = 30 * 60_000

function resultText(result: McpToolResult): string {
  const text = result.content.map(part => part.text ?? '').filter(Boolean).join('\n')
  if (result.isError || !text) throw new Error(text || 'セッション管理 MCP が応答しません')
  return text
}

function collectSessionTurns(session: SessionCursor, row: SessionRow, text: string, now: number): SessionTurn[] {
  const firstRead = session.seen.length === 0
  const at = Math.min(row.lastActiveAt ?? now, now)
  const fresh = newSessionEvents(text, session.seen)
  session.seen = fresh.seen
  const turns = new Map<string, SessionTurn>()
  const alreadyStopped = !session.isRunning && !session.needsFinal ? session.request : undefined
  const current = (id = session.request, start = session.lastRequestAt ?? at) => {
    if (!id) return undefined
    let turn = turns.get(id)
    if (!turn) {
      turn = { id, at: start, steps: [] }
      turns.set(turn.id, turn)
    }
    return turn
  }
  function addStep(turn: SessionTurn, tag: string, body: string) {
    const step = stepOf(turn.id + ':step:' + session.sequence, at, sessionStep(tag, body))
    turn.steps.push(step)
    if (tag === 'result') {
      turn.endedAt = Math.max(turn.at, at)
      turn.status = step.kind === 'error' ? 'error' : 'done'
      session.request = undefined
    }
  }
  if (fresh.revised) {
    const { tag, body } = fresh.revised
    const notice = tag === 'user' && isNotification(body)
    const turn = notice || tag === 'result' ? (session.lastRequest ? current(session.lastRequest) : undefined) : current()
    if (turn && tag === 'user' && !notice && body.trim()) turn.prompt = body
    else if (turn && (notice || tag !== 'user')) {
      if (notice) turn.steps.push(stepOf(turn.id + ':step:' + session.sequence, at, notification(body)))
      else addStep(turn, tag, body)
    }
  }
  for (const { tag, body } of fresh.events) {
    session.sequence++
    if (tag === 'user') {
      if (!body.trim()) continue
      if (isNotification(body)) {
        const turn = session.lastRequest && current(session.lastRequest)
        if (turn) turn.steps.push(stepOf(turn.id + ':step:' + session.sequence, at, notification(body)))
        continue
      }
      const previous = session.request === alreadyStopped ? undefined : current()
      if (previous) {
        previous.endedAt = Math.max(previous.at, at)
        previous.status = 'stopped'
      }
      session.request = 'claude-turn:' + sessionKey(session.id) + ':' + session.sequence
      const start = firstRead && !session.lastRequest ? Math.min(row.startedAt ?? at, at) : at
      session.lastRequest = session.request
      session.lastRequestAt = start
      const turn = current(session.request, start)
      if (turn) turn.prompt = body
      continue
    }
    const turn = current()
    if (!turn) continue
    addStep(turn, tag, body)
  }
  if (!session.isRunning && session.request !== alreadyStopped) {
    const turn = current()
    if (turn) {
      turn.endedAt = Math.max(turn.at, at)
      turn.status = 'stopped'
    }
  }
  return [...turns.values()]
}

export async function pollSessions(
  $: { mcp: Pick<EngineInterface['mcp'], 'call'> },
  prior: SessionCheckpoint,
  lastRead: Map<string, number>,
  now: number,
  ownId = '',
): Promise<{ checkpoint: SessionCheckpoint; monitor: Monitor; changes: SessionObservation[] }> {
  const changes: SessionObservation[] = []
  let sessions = prior.sessions
  try {
    const listing = resultText(await $.mcp.call('ccd_session_mgmt', 'list_sessions', { limit: 20 }))
    const tracked = new Map(prior.sessions.map(session => [session.id, session]))
    const rows = parseSessionList(listing).slice(0, 20)
      .filter(row => !ownId || sessionKey(row.id) !== sessionKey(ownId))
    const items = rows.map(row => {
      const before = tracked.get(row.id)
      const lastActiveAt = row.lastActiveAt ?? (before?.isRunning && !row.isRunning ? now : before?.lastActiveAt)
      if (!row.isRunning && (lastActiveAt === undefined || now - lastActiveAt > SESSION_WINDOW) &&
        !before?.isRunning && !before?.needsFinal) return undefined
      const change: SessionObservation = { row, turns: [] }
      changes.push(change)
      const session: SessionCursor = {
        id: row.id, isRunning: row.isRunning, cwd: row.cwd || before?.cwd || '',
        label: row.label, isRemote: row.isRemote, lastActiveAt, fetchedActivityAt: before?.fetchedActivityAt,
        needsFinal: !row.isRunning && (!before || before.isRunning || before.needsFinal),
        seen: before?.seen ?? [], sequence: before?.sequence ?? 0,
        ...(before?.request ? { request: before.request } : {}),
        ...(before?.lastRequest ? { lastRequest: before.lastRequest, lastRequestAt: before.lastRequestAt } : {}),
      }
      return { row, change, session }
    }).filter(item => item !== undefined)
    const listed = new Set(items.map(item => item.row.id))
    sessions = [...items.map(item => item.session), ...prior.sessions.filter(session =>
      !listed.has(session.id) && session.lastActiveAt !== undefined && now - session.lastActiveAt <= SESSION_WINDOW)]
    for (const id of lastRead.keys()) {
      if (!listed.has(id)) lastRead.delete(id)
    }
    const due = items
      .filter(({ session }) => session.isRunning || session.needsFinal ||
        (session.lastActiveAt !== undefined && session.lastActiveAt > (session.fetchedActivityAt ?? -Infinity)))
      .sort((a, b) => Number(tracked.get(b.row.id)?.isRunning && !b.row.isRunning || false)
        - Number(tracked.get(a.row.id)?.isRunning && !a.row.isRunning || false)
        || (lastRead.get(a.row.id) ?? -1) - (lastRead.get(b.row.id) ?? -1))
      .slice(0, 3)
    let failure: Monitor | undefined
    for (const { row, change, session } of due) {
      lastRead.set(row.id, now)
      try {
        const text = resultText(await $.mcp.call('ccd_session_mgmt', 'list_events', { session_id: row.id, limit: 20 }))
        change.turns = collectSessionTurns(session, row, text, now)
        if (change.turns.length && row.lastActiveAt === undefined) session.lastActiveAt = now
        session.fetchedActivityAt = session.lastActiveAt
        session.needsFinal = false
      } catch (error) {
        failure ??= unavailable(error, 'セッション管理 MCP を利用できません')
      }
    }
    return { changes, checkpoint: { sessions, retryAt: failure ? now + 60_000 : 0 }, monitor: failure ?? { status: 'ok' } }
  } catch (error) {
    return {
      changes, checkpoint: { sessions, retryAt: now + 60_000 },
      monitor: unavailable(error, 'セッション管理 MCP を利用できません'),
    }
  }
}
