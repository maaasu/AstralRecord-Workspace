import type { EngineInterface, Register } from 'claude-code'
import { fingerprint, FLOW_WINDOW, inferVerb, isNotification, SELF, USER } from './model'
import { flowRows, groupFlows, needsTimeRefresh, visibleFlows } from './flow'
import { expireCodexLaunches, observeCodex, observeSessions, resolveCodexCallers } from './observe'
import { renderPane } from './pane'
import { createRequest, editBoard, editRequest, PANE, readBoard, readCodex, readSessions, recordNotification, writeCodex, writeSessions } from './state'
import type { Runtime } from './state'
import { recordToolResult, recordToolStart } from './tool-events'
import { pollCodex } from './watch-codex'
import { pollSessions } from './watch-sessions'

function pollEvery($: EngineInterface, period: number, poll: () => Promise<void>) {
  let pending = false
  $.clock.every(period, async () => {
    if (pending) return
    pending = true
    try {
      await poll()
    } catch {
      // The next poll retries a failed observation.
    } finally {
      pending = false
    }
  })
}

function startMonitors(
  $: EngineInterface, runtime: Runtime, observations: ObservationQueue, watchCodex: boolean, watchSessions: boolean,
) {
  let previousTick = -Infinity
  pollEvery($, 5_000, async () => {
    const now = await $.clock.now()
    const live = await readBoard($)
    const mayExpire = live.requests.some(request => request.status === 'running' &&
      !request.observation && request.codexLaunch?.shellEndedAt !== undefined)
    const aged = live.requests.some(request => request.at < now - FLOW_WINDOW)
    if (mayExpire || aged) await editBoard($, runtime, async draft => {
      expireCodexLaunches(draft, now)
      if (aged) {
        const retained = new Set(visibleFlows(groupFlows({ ...draft.value, now }), now, 'all')
          .flatMap(flow => flowRows(flow.root).map(({ node }) => node.request.id)))
        draft.value.requests = draft.value.requests.filter(request => retained.has(request.id) ||
          request.at >= now - FLOW_WINDOW)
      }
    })
    if (needsTimeRefresh(live.requests, previousTick)) $.ui.invalidate('ui.render')
    previousTick = now
  })
  if (watchCodex) {
    pollEvery($, 2_000, async () => {
      const prior = await readCodex($)
      const now = await $.clock.now()
      const result = await pollCodex($, prior, now)
      const sessions = await readSessions($)
      // Let an enqueued launch record its prompt before deciding whether the rollout matches.
      if (observations.draining) return
      await editBoard($, runtime, async draft => {
        draft.value.monitors = { ...draft.value.monitors, codex: result.monitor }
        await observeCodex(draft, result.changes, now, sessions.sessions)
        resolveCodexCallers(draft, sessions.sessions, now)
      })
      if (JSON.stringify(prior) !== JSON.stringify(result.checkpoint)) await writeCodex($, result.checkpoint)
    })
  }
  if (watchSessions) {
    const lastRead = new Map<string, number>()
    pollEvery($, 4_000, async () => {
      const prior = await readSessions($)
      const now = await $.clock.now()
      if (now < prior.retryAt) return
      const ownId = await $.session.id()
      const result = await pollSessions($, prior, lastRead, now, ownId)
      await editBoard($, runtime, async draft => {
        draft.value.monitors = { ...draft.value.monitors, sessions: result.monitor }
        await observeSessions(draft, result.changes, ownId)
        resolveCodexCallers(draft, result.checkpoint.sessions, now)
      })
      if (JSON.stringify(prior) !== JSON.stringify(result.checkpoint)) await writeSessions($, result.checkpoint)
    })
  }
}

async function beginPrompt($: EngineInterface, runtime: Runtime, prompt: string, at: number) {
  if (!prompt.trim()) return undefined
  return editBoard($, runtime, async draft => {
    if (isNotification(prompt)) {
      await recordNotification(draft, SELF.id, prompt, at)
      return undefined
    }
    const id = 'self-request:' + at + ':' + ++draft.value.sequence
    const request = createRequest(draft, id, USER, SELF, inferVerb(prompt), prompt, at)
    await editRequest(draft, request, { prompt, promptSource: '入力した依頼' })
    draft.value.selfQueue = [...draft.value.selfQueue, { id, fingerprint: fingerprint(prompt) }]
    return id
  })
}

async function bindMainTurn($: EngineInterface, runtime: Runtime, text: string, id: string) {
  await editBoard($, runtime, async ({ value }) => {
    const key = fingerprint(text)
    const queued = value.selfQueue.find(item => item.fingerprint === key)
    if (!queued) {
      value.selfTurn = null
      return
    }
    value.selfQueue = value.selfQueue.filter(item => item.id !== queued.id)
    value.selfTurn = { id, request: queued.id }
  })
}

async function closePrompt($: EngineInterface, runtime: Runtime, id: string, ok: boolean, now: number, result?: string) {
  await editBoard($, runtime, async draft => {
    const current = draft.value.selfTurn
    const request = draft.value.requests.find(item => item.id === (current?.id === id ? current.request : id))
    if (!request) return
    if (current?.request === request.id) draft.value.selfTurn = null
    draft.value.selfQueue = draft.value.selfQueue.filter(item => item.id !== request.id)
    await editRequest(draft, request, { status: ok ? 'done' : 'error', endedAt: now, result })
  })
}

type ObservationQueue = {
  pending: { at: number; run: (at: number) => Promise<unknown> }[]
  draining: boolean
}

async function drainObservations($: EngineInterface, queue: ObservationQueue) {
  try {
    while (queue.pending.length) {
      const observation = queue.pending.shift()!
      try {
        await observation.run(Math.min(observation.at, await $.clock.now()))
      } catch {
        // Continue with later observations when an individual event fails.
      }
    }
  } finally {
    queue.draining = false
  }
}

function enqueueObservation($: EngineInterface, queue: ObservationQueue, run: (at: number) => Promise<unknown>) {
  queue.pending.push({ at: Date.now(), run })
  if (queue.draining) return
  queue.draining = true
  try {
    $.clock.after(0, () => drainObservations($, queue))
  } catch {
    queue.draining = false
  }
}

export const register: Register = (on, options) => {
  const watchCodex = options.watchCodex !== false
  const watchSessions = options.watchSessions !== false
  const showSelf = options.showSelf !== false
  const runtime: Runtime = { autoOpen: options.autoOpen !== false, queue: Promise.resolve(), hintPending: false }

  const observations: ObservationQueue = { pending: [], draining: false }

  on('session.start', async ($, e, next) => {
    try {
      await $.command.register({ name: 'work-live', description: '作業ライブのペインを開く' })
      startMonitors($, runtime, observations, watchCodex, watchSessions)
      const waiting = { status: 'unavailable', reason: '確認中' } as const
      await editBoard($, runtime, async ({ value }) => {
        value.monitors = {
          codex: watchCodex ? waiting : { status: 'off' },
          sessions: watchSessions ? waiting : { status: 'off' },
        }
      })
    } catch {
      // A failed display initialization need not prevent the session from starting.
    }
    return next(e)
  })

  on('command.run', { command: 'work-live' }, async $ => {
    await $.ui.open(PANE)
    return { text: '作業ライブを開きました。', isTextDisplayOnly: true }
  })

  if (showSelf) {
    on('prompt.submit', async ($, e, next) => {
      const observe = isNotification(e.text) || (!e.isMeta && !e.command)
      let id: string | undefined
      if (observe) enqueueObservation($, observations, async at => {
        id = await beginPrompt($, runtime, e.text, at)
      })
      const result = await next(e)
      if (observe && result.drop !== undefined) enqueueObservation($, observations, async at => {
        if (id) await closePrompt($, runtime, id, false, at)
      })
      return result
    })

    on('turn.start', async ($, e, next) => {
      if (!e.agentId) enqueueObservation($, observations, () => bindMainTurn($, runtime, e.text, e.turnId))
      return next(e)
    })

    on('turn.complete', async ($, e, next) => {
      if (!e.agentId) enqueueObservation($, observations,
        at => closePrompt($, runtime, e.turnId, e.reason === 'answer', at, e.answer))
      return next(e)
    })
  }

  on('tool.call', async ($, e, next) => {
    let observed: Awaited<ReturnType<typeof recordToolStart>> | undefined
    enqueueObservation($, observations, async at => {
      observed = await recordToolStart($, runtime, e, showSelf, at)
    })
    const result = await next(e)
    enqueueObservation($, observations, async at => {
      if (observed) await recordToolResult($, runtime, observed, result, at)
    })
    return result
  })

  on('ui.render', { component: 'Pane', requestId: 'work-live' }, ($, e) => renderPane($, e, runtime))
}
