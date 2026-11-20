import { atom, memberOf, read, update } from 'claude-code'
import type { EngineInterface } from 'claude-code'
import type { Actor, CodexCheckpoint, DisplayScope, LiveBoard, SessionCheckpoint, LiveRequest, RequestDetail, RequestStatus, Step } from '../types'
import { stepIcon } from './icons'
import { emptyBoard, emptyDetail, MAX_PROMPT, MAX_STEP, notification, oneLine, requestResult, stepOf, summarize, summarizeSteps, truncate } from './model'

export const PANE = { id: 'work-live', title: '作業ライブ' }
const board = atom({ plugin: 'work-live', key: 'board' } as const, null, { shape: 'board-5' })
const previousBoard = atom({ plugin: 'work-live', key: 'board' } as const, null, { shape: 'board-4' })
const legacyBoard = atom({ plugin: 'work-live', key: 'board' } as const, null, { shape: 'board-3' })
const details = atom({ plugin: 'work-live', key: 'detail' } as const, emptyDetail, { shape: 'detail-4' })
const previousDetails = atom({ plugin: 'work-live', key: 'detail' } as const, emptyDetail, { shape: 'detail-3' })
const codex = atom({ plugin: 'work-live', key: 'codex' } as const, { files: [] }, { shape: 'codex-4' })
const sessions = atom({ plugin: 'work-live', key: 'sessions' } as const, { sessions: [], retryAt: 0 }, { shape: 'sessions-4' })

export type Runtime = { autoOpen: boolean; queue: Promise<unknown>; hintPending: boolean }
type Edit = {
  prompt?: string
  promptSource?: string
  steps?: Step[]
  status?: RequestStatus
  endedAt?: number
  result?: string
}

export async function readBoard($: EngineInterface): Promise<LiveBoard> {
  const current = await read($, board)
  if (current) return current
  const previous = await read($, previousBoard) ?? await read($, legacyBoard)
  if (!previous) return emptyBoard
  const requests = await Promise.all(previous.requests.map(async request => {
    const { steps } = await readDetail($, request.id)
    return { ...request, ...summarizeSteps(steps), result: request.result ?? requestResult(request, steps) }
  }))
  return { ...emptyBoard, ...previous, requests }
}

export async function readDetail($: EngineInterface, id: string): Promise<RequestDetail> {
  const current = await read($, memberOf(details, { requestId: id }))
  if (current.prompt || current.promptSource || current.steps.length) return boundDetail(current)
  // A hot reload can still have details written under the previous shape.
  const previous = await read($, memberOf(previousDetails, { requestId: id }))
  return previous.steps.length
    ? boundDetail({ ...previous, steps: previous.steps.map(step => ({ ...step, icon: stepIcon(step) })) })
    : previous
}

function boundDetail(detail: RequestDetail): RequestDetail {
  return { ...detail, steps: detail.steps.map(step => step.full.length <= MAX_STEP ? step
    : { ...step, full: truncate(step.full, MAX_STEP) }) }
}

export function readCodex($: EngineInterface): Promise<CodexCheckpoint> {
  return read($, codex)
}

export async function writeCodex($: EngineInterface, value: CodexCheckpoint): Promise<void> {
  await update($, codex, () => value)
}

export function readSessions($: EngineInterface): Promise<SessionCheckpoint> {
  return read($, sessions)
}

export async function writeSessions($: EngineInterface, value: SessionCheckpoint): Promise<void> {
  await update($, sessions, () => value)
}

export type Draft = {
  value: LiveBoard
  changedDetails: Map<string, RequestDetail>
  opened: Set<string>
  detail(id: string): Promise<RequestDetail>
}

export function rememberActor(draft: Draft, entry: Actor) {
  const at = draft.value.actors.findIndex(item => item.id === entry.id)
  if (at === -1) draft.value.actors.push(entry)
  else draft.value.actors[at] = entry
}

export function createRequest(
  draft: Draft, id: string, from: Actor, to: Actor, verb: string, prompt: string, at: number,
): LiveRequest {
  rememberActor(draft, from)
  rememberActor(draft, to)
  const request: LiveRequest = {
    id, from: from.id, to: to.id, verb, summary: summarize(prompt) || '作業',
    at, status: 'running', stepCount: 0, counts: {},
  }
  draft.value.requests.push(request)
  if (to.id !== 'self') draft.opened.add(id)
  return request
}

export async function editRequest(draft: Draft, request: LiveRequest, change: Edit) {
  if (change.status) request.status = change.status
  if (change.endedAt !== undefined) request.endedAt = change.endedAt
  const result = change.result && oneLine(change.result, 60)
  if (result) request.result = result
  if (change.prompt === undefined && !change.steps?.length && !change.status) return

  const prior = await draft.detail(request.id)
  const steps = new Map(prior.steps.map(step => [step.id, step]))
  for (const step of change.steps ?? []) steps.set(step.id, step)
  const recent = [...steps.values()].sort((a, b) => a.at - b.at).slice(-60)
  Object.assign(request, summarizeSteps(recent))
  if (!result) {
    const latestResult = requestResult(request, recent)
    if (latestResult && (change.status || requestResult(request, change.steps ?? []))) request.result = latestResult
  }
  const detail = boundDetail({
    prompt: change.prompt === undefined ? prior.prompt : truncate(change.prompt, MAX_PROMPT),
    promptSource: change.promptSource ?? prior.promptSource,
    steps: recent,
  })
  if (JSON.stringify(prior) !== JSON.stringify(detail)) draft.changedDetails.set(request.id, detail)
}

export async function recordNotification(draft: Draft, to: string, body: string, at: number) {
  const request = draft.value.requests.findLast(item => item.to === to)
  if (!request) return
  const step = stepOf('notice:' + ++draft.value.sequence, at, notification(body))
  await editRequest(draft, request, { steps: [step] })
}

async function openAutomatically($: EngineInterface, runtime: Runtime) {
  try {
    const result = await $.ui.open(PANE)
    if (result.isPlaced || runtime.hintPending) return
    runtime.hintPending = true
    try {
      if (!(await readBoard($)).placementHintShown) {
        await editBoard($, runtime, async draft => { draft.value.placementHintShown = true })
        await $.ui.toast('作業ライブ: /work-live で表示できます')
      }
    } finally {
      runtime.hintPending = false
    }
  } catch {
    // A placement failure does not affect the session or recorded events.
  }
}

function boundBoard(value: LiveBoard) {
  value.requests = value.requests.sort((a, b) => a.at - b.at).slice(-30)
  const kept = new Set(value.requests.map(request => request.id))
  const actors = new Set(value.requests.flatMap(request => [request.from, request.to]))
  value.actors = value.actors.filter(entry => actors.has(entry.id))
  value.selfQueue = value.selfQueue.filter(item => kept.has(item.id))
  if (value.selfTurn && !kept.has(value.selfTurn.request)) value.selfTurn = null
  if (value.selectedRequest && !kept.has(value.selectedRequest)) {
    value.selectedRequest = null
    value.expandedStep = null
    value.promptExpanded = false
    value.olderStepsExpanded = false
  }
}

export async function editBoard<T>(
  $: EngineInterface, runtime: Runtime, run: (draft: Draft) => Promise<T>,
): Promise<T> {
  const work = runtime.queue.then(async () => {
    const current = await read($, board)
    const previous = current ?? await readBoard($)
    const value: LiveBoard = {
      ...previous, actors: [...previous.actors],
      requests: previous.requests.map(request => ({ ...request })),
    }
    const changedDetails = new Map<string, RequestDetail>()
    const draft: Draft = {
      value, changedDetails, opened: new Set(),
      detail: async id => changedDetails.get(id) ?? await readDetail($, id),
    }
    const result = await run(draft)
    boundBoard(value)
    const retained = new Set(value.requests.map(request => request.id))
    for (const id of retained) {
      const detail = changedDetails.get(id) ?? (!current ? await readDetail($, id) : undefined)
      if (detail) await update($, memberOf(details, { requestId: id }), () => detail)
    }
    if (!current || JSON.stringify(previous) !== JSON.stringify(value)) await update($, board, () => value)
    for (const id of new Set([...previous.requests.map(request => request.id), ...changedDetails.keys()])) {
      if (retained.has(id)) continue
      await update($, memberOf(details, { requestId: id }), () => emptyDetail)
    }
    const shouldOpen = value.requests.some(request => request.status === 'running' && draft.opened.has(request.id))
    return { result, shouldOpen }
  })
  runtime.queue = work.catch(() => undefined)
  const { result, shouldOpen } = await work
  if (runtime.autoOpen && shouldOpen) await openAutomatically($, runtime)
  return result
}

export async function selectRequest($: EngineInterface, runtime: Runtime, id: string) {
  await editBoard($, runtime, async ({ value }) => {
    if (!value.requests.some(item => item.id === id)) return
    value.selectedRequest = value.selectedRequest === id ? null : id
    value.expandedStep = null
    value.promptExpanded = false
    value.olderStepsExpanded = false
  })
}

export async function selectScope($: EngineInterface, runtime: Runtime, scope: DisplayScope) {
  await editBoard($, runtime, async ({ value }) => { value.scope = scope })
}

export async function selectSession($: EngineInterface, runtime: Runtime, id: string) {
  await editBoard($, runtime, async ({ value }) => { value.selectedSession = id })
}

export async function toggleAllSessions($: EngineInterface, runtime: Runtime) {
  await editBoard($, runtime, async ({ value }) => { value.allSessionsExpanded = !value.allSessionsExpanded })
}

export async function toggleDetail(
  $: EngineInterface, runtime: Runtime, id: string, field: 'prompt' | 'older' | 'step', step?: string,
) {
  await editBoard($, runtime, async ({ value }) => {
    if (value.selectedRequest !== id) return
    if (field === 'step' && step) value.expandedStep = value.expandedStep === step ? null : step
    if (field === 'prompt') value.promptExpanded = !value.promptExpanded
    if (field === 'older') {
      value.olderStepsExpanded = !value.olderStepsExpanded
      value.expandedStep = null
    }
  })
}
