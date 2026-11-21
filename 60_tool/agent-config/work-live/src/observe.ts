import type { Actor, ClaudeSession, LiveRequest, RolloutMeta } from '../types/index.d.ts'
import { actor, codeText, FLOW_WINDOW, inferVerb, namedActor, summarize, USER } from './model.ts'
import { codexVerb, matchesCodexLaunch, normalizePath, sessionKey } from './parse-tool.ts'
import { createRequest, detailOf, editRequest, rememberActor } from './state.ts'
import type { Draft } from './state.ts'
import type { CodexObservation } from './watch-codex.ts'

const CALLER_WINDOW = 30 * 60_000
const LAUNCH_TIMEOUT = 180_000

export function codexActor(id: string, name = '') {
  return namedActor('codex:' + id, 'codex', 'Codex', name)
}

export function claudeActor(id: string, name = '') {
  return namedActor('claude:' + sessionKey(id), 'claude', 'Claude', name)
}

export function knownActor(draft: Draft, fallback: Actor) {
  return draft.value.actors.find(item => item.id === fallback.id) ?? fallback
}

export function observedRequest(draft: Draft, id: string | undefined) {
  return id && draft.value.requests.find(item => item.id === id || item.observation === id) || undefined
}

export function findCodexLaunch(draft: Draft, meta: RolloutMeta, at: number, prompt = '') {
  if (meta.originator !== 'codex_exec' || (meta.parentThreadId && meta.parentThreadId !== meta.id)) return undefined
  const candidates = draft.value.requests.filter(request => !request.observation && request.codexLaunch &&
    matchesCodexLaunch(request.codexLaunch, request.at, meta.cwd, at),
  ).sort((a, b) => Math.abs(a.at - at) - Math.abs(b.at - at))
  const observed = codeText(prompt).trim()
  let prefix: LiveRequest | undefined
  let estimate: LiveRequest | undefined
  for (const request of candidates) {
    const expected = codeText(detailOf(draft, request.id).prompt).trim()
    if (!expected) estimate ??= request
    else if (expected === observed) return { request, estimated: false }
    else if (observed.length >= 200 && expected.slice(0, 200) === observed.slice(0, 200)) prefix ??= request
  }
  if (prefix) return { request: prefix, estimated: false }
  if (estimate) return { request: estimate, estimated: true }
}

function claudeCaller(cwd: string, at: number, sessions: readonly ClaudeSession[], now: number) {
  if (!cwd) return undefined
  const normalized = normalizePath(cwd)
  const match = sessions.filter(session => session.cwd && normalizePath(session.cwd) === normalized &&
    (session.isRunning || (session.lastActiveAt !== undefined && now - session.lastActiveAt <= CALLER_WINDOW)))
    .sort((a, b) => Math.abs((a.lastActiveAt ?? Infinity) - at) - Math.abs((b.lastActiveAt ?? Infinity) - at))[0]
  return match && claudeActor(match.id, match.label)
}

export function codexCaller(meta: RolloutMeta, sessions: readonly ClaudeSession[] = [], at = 0, now = at): Actor {
  if (meta.parentThreadId && meta.parentThreadId !== meta.id) return codexActor(meta.parentThreadId)
  if (meta.originator === 'codex_exec') return claudeCaller(meta.cwd, meta.timestamp ?? at, sessions, now)
    ?? actor('unknown', 'unknown', '不明な呼び出し元（codex exec）')
  return USER
}

export function resolveCodexCallers(draft: Draft, sessions: readonly ClaudeSession[], now: number) {
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

export function mergeObservation(draft: Draft, request: LiveRequest, observed: LiveRequest) {
  const prior = detailOf(draft, request.id)
  const detail = detailOf(draft, observed.id)
  request.observation = observed.observation
  editRequest(draft, request, {
    prompt: prior.prompt ? undefined : detail.prompt, promptSource: prior.prompt ? undefined : detail.promptSource,
    steps: detail.steps, replaceSteps: !!request.codexLaunch, status: observed.status, endedAt: observed.endedAt, result: observed.result,
  })
  for (const child of draft.value.requests) {
    if (child.parentId === observed.id) child.parentId = request.id
  }
  draft.value.requests = draft.value.requests.filter(item => item !== observed)
}

export function observeCodex(
  draft: Draft, changes: CodexObservation[], now: number, sessions: readonly ClaudeSession[] = [],
) {
  const names = new Map(changes.map(({ meta, label }) => [meta.id, codexActor(meta.id, label)]))
  for (const { meta, turns } of changes) {
    const to = names.get(meta.id)!
    if (draft.value.actors.some(item => item.id === to.id)) rememberActor(draft, to)
    // Claude can flush the tool invocation after the rollout was already read.
    for (const standalone of draft.value.requests.filter(item => item.to === to.id && item.id === item.observation)) {
      const prompt = detailOf(draft, standalone.id).prompt
      const match = findCodexLaunch(draft, meta, standalone.at, prompt)
      if (!match) continue
      const request = match.request
      request.to = to.id
      if (match.estimated) {
        request.estimatedObservation = true
        request.summary = summarize(prompt) || request.summary
        request.verb = codexVerb(request.codexLaunch!, prompt)
      }
      delete request.awaitingPrompt
      mergeObservation(draft, request, standalone)
    }
    for (const turn of turns) {
      const at = Math.min(turn.at, now)
      const activityAt = Math.max(at, turn.endedAt ?? 0, turn.steps.at(-1)?.at ?? 0)
      if (activityAt < now - FLOW_WINDOW && !draft.value.requests.some(item => item.observation === turn.id)) continue
      let request = draft.value.requests.find(item => item.observation === turn.id)
      const match = !request && turn.prompt ? findCodexLaunch(draft, meta, at, turn.prompt) : undefined
      if (match) {
        request = match.request
        if (match.estimated) request.estimatedObservation = true
      }
      if (!request) {
        if (!turn.prompt) continue
        const caller = codexCaller(meta, sessions, at, now)
        const from = caller.kind === 'codex' ? names.get(meta.parentThreadId) ?? knownActor(draft, caller) : caller
        request = createRequest(draft, turn.id, from, to, inferVerb(turn.prompt), turn.prompt, at)
        if (from.kind === 'unknown' && meta.cwd) request.codexOrigin = { cwd: meta.cwd, at: meta.timestamp ?? at }
      } else {
        rememberActor(draft, to)
        request.to = to.id
      }
      request.observation = turn.id
      if (turn.prompt) delete request.awaitingPrompt
      const detail = detailOf(draft, request.id)
      const prompt = turn.prompt && (!request.codexLaunch || !detail.prompt || detail.promptSource === 'Codex の記録')
        ? turn.prompt : undefined
      if (prompt) {
        request.summary = summarize(prompt) || '作業'
        request.verb = request.codexLaunch ? codexVerb(request.codexLaunch, prompt) : inferVerb(prompt)
      }
      editRequest(draft, request, {
        prompt, promptSource: prompt ? 'Codex の記録' : undefined, steps: turn.steps,
        replaceSteps: !!match, status: turn.status ?? (match ? 'running' : undefined),
        endedAt: turn.endedAt === undefined ? undefined : Math.min(turn.endedAt, now),
      })
    }
  }
}

export function expireCodexLaunches(draft: Draft, now: number) {
  for (const request of draft.value.requests) {
    const endedAt = request.codexLaunch?.shellEndedAt
    if (endedAt !== undefined && !request.observation && request.status === 'running' && now > request.at + LAUNCH_TIMEOUT) {
      editRequest(draft, request, { status: 'done', endedAt })
    }
  }
}

export function matchSessionRequest(draft: Draft, to: Actor, prompt: string, at: number) {
  const candidates = draft.value.requests.filter(item => item.awaitingSession && !item.observation &&
    item.status !== 'error' && item.to === to.id && at >= item.at - (item.awaitingSession === 'start' ? 5_000 : 0))
  for (const request of candidates) {
    const text = detailOf(draft, request.id).prompt.trim()
    const observed = prompt.trim()
    if (text === observed || (request.awaitingSession === 'start' && text.startsWith(observed + '\n\n'))) return request
  }
  return candidates.find(item => item.awaitingSession === 'start')
}

export function attachSession(draft: Draft, request: LiveRequest, id: string, now: number) {
  const pending = draft.value.actors.find(item => item.id === request.to)
  const to = knownActor(draft, { ...claudeActor(id), label: pending?.label ?? 'Claude' })
  rememberActor(draft, to)
  request.to = to.id

  const prior = detailOf(draft, request.id)
  const observed = draft.value.requests.filter(item => item.id === item.observation && item.to === to.id &&
    item.at >= request.at - (request.awaitingSession === 'start' ? 5_000 : 0) && item.at <= now)
  let matched = observed.find(item => {
    const prompt = detailOf(draft, item.id).prompt.trim()
    return prompt && (prior.prompt.trim() === prompt ||
      (request.awaitingSession === 'start' && prior.prompt.trim().startsWith(prompt + '\n\n')))
  })
  if (request.awaitingSession === 'start') matched ??= observed[0]
  if (!matched) return
  delete request.awaitingSession
  mergeObservation(draft, request, matched)
}
