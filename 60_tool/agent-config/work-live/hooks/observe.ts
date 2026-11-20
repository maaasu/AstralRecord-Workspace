import type { Actor, LiveRequest, RolloutMeta, SessionCursor } from '../types'
import { actor, FLOW_WINDOW, inferVerb, namedActor, SELF, summarize, USER } from './model'
import { codexVerb, matchesCodexLaunch, normalizePath, sessionKey } from './parse-tool'
import { createRequest, editRequest, rememberActor } from './state'
import type { Draft } from './state'
import type { CodexObservation } from './watch-codex'
import type { SessionObservation, SessionTurn } from './watch-sessions'
import { SESSION_WINDOW } from './watch-sessions'

const launchTimeout = 180_000

export function codexActor(id: string, name = '') {
  return namedActor('codex:' + id, 'codex', 'Codex', name)
}

export function claudeActor(id: string, name = '', remote = id.startsWith('session_')) {
  return namedActor('claude:' + id, remote ? 'claude-remote' : 'claude', remote ? 'クラウド' : 'Claude', name)
}

function knownActor(draft: Draft, fallback: Actor) {
  return draft.value.actors.find(item => item.id === fallback.id) ?? fallback
}

export async function findCodexLaunch(draft: Draft, meta: RolloutMeta, at: number, prompt = '') {
  if (meta.originator !== 'codex_exec' || (meta.parentThreadId && meta.parentThreadId !== meta.id)) return undefined
  const candidates = draft.value.requests.filter(request => !request.observation && request.codexLaunch &&
    matchesCodexLaunch(request.codexLaunch, request.at, meta.cwd, at),
  ).sort((a, b) => Math.abs(a.at - at) - Math.abs(b.at - at))
  const observed = prompt.trim()
  let estimate: LiveRequest | undefined
  for (const request of candidates) {
    const expected = (await draft.detail(request.id)).prompt.trim()
    if (!expected) estimate ??= request
    else if (expected === observed || (observed.length >= 200 && expected.slice(0, 200) === observed.slice(0, 200))) {
      return { request, estimated: false }
    }
  }
  if (estimate) return { request: estimate, estimated: true }
}

function claudeCaller(cwd: string, at: number, sessions: readonly SessionCursor[], now: number) {
  if (!cwd) return undefined
  const normalized = normalizePath(cwd)
  const match = sessions.filter(session => session.cwd && normalizePath(session.cwd) === normalized &&
    (session.isRunning || (session.lastActiveAt !== undefined && now - session.lastActiveAt <= SESSION_WINDOW)))
    .sort((a, b) => Math.abs((a.lastActiveAt ?? Infinity) - at) - Math.abs((b.lastActiveAt ?? Infinity) - at))[0]
  return match && namedActor('claude:' + match.id, 'claude', 'Claude', match.label)
}

export function codexCaller(meta: RolloutMeta, sessions: readonly SessionCursor[] = [], at = 0, now = at): Actor {
  if (meta.parentThreadId && meta.parentThreadId !== meta.id) return codexActor(meta.parentThreadId)
  if (meta.originator === 'codex_exec') return claudeCaller(meta.cwd, meta.timestamp ?? at, sessions, now)
    ?? actor('unknown', 'unknown', '不明な呼び出し元（codex exec）')
  return USER
}

export function resolveCodexCallers(draft: Draft, sessions: readonly SessionCursor[], now: number) {
  for (const request of draft.value.requests) {
    if (!request.codexOrigin) continue
    const { cwd, at } = request.codexOrigin
    const from = claudeCaller(cwd, at, sessions, now)
    if (!from) continue
    rememberActor(draft, from)
    request.from = from.id
    delete request.codexOrigin
  }
}

export async function observeCodex(
  draft: Draft, changes: CodexObservation[], now: number, sessions: readonly SessionCursor[] = [],
) {
  for (const { meta, label, turns } of changes) {
    const to = codexActor(meta.id, label)
    if (draft.value.actors.some(item => item.id === to.id)) rememberActor(draft, to)
    for (const turn of turns) {
      const at = Math.min(turn.at, now)
      const activityAt = Math.max(at, turn.endedAt ?? 0, turn.steps.at(-1)?.at ?? 0)
      if (activityAt < now - FLOW_WINDOW && !draft.value.requests.some(item => item.observation === turn.id)) continue
      let request = draft.value.requests.find(item => item.observation === turn.id)
      if (!request) {
        const match = await findCodexLaunch(draft, meta, at, turn.prompt)
        if (match) {
          request = match.request
          if (match.estimated) request.estimatedObservation = true
        }
      }
      if (!request) {
        if (!turn.prompt) continue
        const caller = codexCaller(meta, sessions, at, now)
        const from = caller.kind === 'codex' ? knownActor(draft, caller) : caller
        request = createRequest(draft, turn.id, from, to, inferVerb(turn.prompt), turn.prompt, at)
        if (from.kind === 'unknown' && meta.cwd) request.codexOrigin = { cwd: meta.cwd, at: meta.timestamp ?? at }
      } else {
        rememberActor(draft, to)
        request.to = to.id
      }
      request.observation = turn.id
      if (turn.prompt) delete request.awaitingPrompt
      const detail = await draft.detail(request.id)
      const prompt = turn.prompt && (!request.codexLaunch || !detail.prompt || detail.promptSource === 'Codex の記録')
        ? turn.prompt : undefined
      if (prompt) {
        request.summary = summarize(prompt) || '作業'
        request.verb = request.codexLaunch ? codexVerb(request.codexLaunch, prompt) : inferVerb(prompt)
      }
      if (turn.status && request.codexLaunch) request.codexFinished = true
      await editRequest(draft, request, {
        prompt, promptSource: prompt ? 'Codex の記録' : undefined, steps: turn.steps,
        status: turn.status, endedAt: turn.endedAt === undefined ? undefined : Math.min(turn.endedAt, now),
      })
    }
  }
}

export function expireCodexLaunches(draft: Draft, now: number) {
  for (const request of draft.value.requests) {
    const endedAt = request.codexLaunch?.shellEndedAt
    if (endedAt !== undefined && !request.observation && request.status === 'running' && now > request.at + launchTimeout) {
      request.status = 'done'
      request.endedAt = endedAt
    }
  }
}

function sameClaudeSession(a: string, b: string) {
  return a.startsWith('claude:') && b.startsWith('claude:') && sessionKey(a.slice(7)) === sessionKey(b.slice(7))
}

export function sessionCaller(startedBy: string, ownId: string): Actor {
  if (!startedBy) return USER
  return ownId && sessionKey(startedBy) === sessionKey(ownId) ? SELF : claudeActor(startedBy)
}

async function matchSessionRequest(draft: Draft, to: Actor, turn: SessionTurn) {
  const prompt = turn.prompt
  if (prompt === undefined) return undefined
  const candidates = draft.value.requests.filter(item => item.awaitingSession && !item.observation &&
    item.status !== 'error' && sameClaudeSession(item.to, to.id) &&
    (item.awaitingSession !== 'message' || turn.at >= item.at))
  for (const request of candidates) {
    const text = (await draft.detail(request.id)).prompt.trim()
    const observed = prompt.trim()
    if (text === observed || (request.awaitingSession === 'start' && observed && text.startsWith(observed + '\n\n'))) {
      return request
    }
  }
  return candidates.find(item => item.awaitingSession === 'start')
}

export async function observeSessions(draft: Draft, changes: SessionObservation[], ownId: string) {
  // Register names first so a child can refer to a parent found in the same listing.
  for (const { row } of changes) {
    if (draft.value.actors.some(item => sameClaudeSession(item.id, 'claude:' + row.id))) {
      const entry = claudeActor(row.id, row.label, row.isRemote)
      rememberActor(draft, entry)
      for (const request of draft.value.requests) {
        if (sameClaudeSession(request.to, entry.id)) request.to = entry.id
        if (sameClaudeSession(request.from, entry.id)) request.from = entry.id
      }
    }
  }
  const listed = new Map(changes.map(({ row }) => [sessionKey(row.id), claudeActor(row.id, row.label, row.isRemote)]))
  for (const { row, turns } of changes) {
    const to = listed.get(sessionKey(row.id)) ?? claudeActor(row.id, row.label, row.isRemote)
    for (const turn of turns) {
      let request = draft.value.requests.find(item => item.observation === turn.id)
        ?? await matchSessionRequest(draft, to, turn)
      if (!request) {
        if (turn.prompt === undefined) continue
        const caller = sessionCaller(row.startedBy, ownId)
        const from = caller.id.startsWith('claude:')
          ? listed.get(sessionKey(row.startedBy)) ?? knownActor(draft, caller) : caller
        request = createRequest(draft, turn.id, from, to, inferVerb(turn.prompt), turn.prompt, turn.at)
      }
      rememberActor(draft, to)
      request.to = to.id
      request.observation = turn.id
      delete request.awaitingSession
      const prior = await draft.detail(request.id)
      const prompt = !prior.prompt || prior.promptSource === 'Claude のセッション記録' ? turn.prompt : undefined
      if (prompt !== undefined && request.id === turn.id) {
        request.summary = summarize(prompt) || '作業'
        request.verb = inferVerb(prompt)
      }
      await editRequest(draft, request, {
        prompt, promptSource: prompt === undefined ? undefined : 'Claude のセッション記録',
        steps: turn.steps, endedAt: turn.endedAt,
        status: turn.status,
      })
    }
  }
}

export async function attachSession(draft: Draft, request: LiveRequest, session: string, now: number) {
  const pending = draft.value.actors.find(item => item.id === request.to)
  const existing = draft.value.actors.find(item => sameClaudeSession(item.id, 'claude:' + session))
  const to = existing ?? actor('claude:' + session, pending?.kind ?? 'claude', pending?.label ?? 'Claude')
  rememberActor(draft, to)
  request.to = to.id

  const prior = await draft.detail(request.id)
  // A poll can see the new session during next(e), before the tool returns its id.
  const observed = draft.value.requests.filter(item => item.id === item.observation && sameClaudeSession(item.to, to.id) &&
    item.at >= request.at - 5_000 && item.at <= now)
  let matched: LiveRequest | undefined
  for (const candidate of observed) {
    const detail = await draft.detail(candidate.id)
    const prompt = detail.prompt.trim()
    if (prompt && (prior.prompt.trim() === prompt || prior.prompt.trim().startsWith(prompt + '\n\n'))) {
      matched = candidate
      break
    }
  }
  matched ??= observed[0]
  if (!matched) return
  const detail = await draft.detail(matched.id)
  request.observation = matched.observation
  delete request.awaitingSession
  await editRequest(draft, request, {
    prompt: prior.prompt ? undefined : detail.prompt, promptSource: detail.promptSource,
    steps: detail.steps, status: matched.status, endedAt: matched.endedAt,
  })
  draft.value.requests = draft.value.requests.filter(item => item.id !== matched.id)
  if (draft.value.selectedRequest === matched.id) draft.value.selectedRequest = request.id
}
