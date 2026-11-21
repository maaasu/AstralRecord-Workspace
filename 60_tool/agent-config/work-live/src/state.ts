import type { Actor, LiveBoard, LiveRequest, RequestDetail, RequestStatus, Step } from '../types/index.d.ts'
import { activityAt, flowRows, groupFlows, visibleFlows } from './flow.ts'
import { emptyBoard, emptyDetail, FLOW_WINDOW, MAX_PROMPT, MAX_STEP, oneLine, requestResult, summarize, summarizeSteps, truncate } from './model.ts'

export const REQUEST_LIMIT = 30

export type Draft = { value: LiveBoard; details: Map<string, RequestDetail> }
type Edit = {
  prompt?: string
  promptSource?: string
  steps?: Step[]
  replaceSteps?: boolean
  status?: RequestStatus
  endedAt?: number
  result?: string
}

export function newDraft(): Draft {
  return { value: { ...emptyBoard, actors: [], requests: [] }, details: new Map() }
}

export function detailOf(draft: Draft, id: string): RequestDetail {
  return draft.details.get(id) ?? emptyDetail
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
  return request
}

export function editRequest(draft: Draft, request: LiveRequest, change: Edit) {
  if (change.status) request.status = change.status
  if (change.status === 'running') {
    delete request.endedAt
  }
  if (change.replaceSteps || change.status === 'running') delete request.result
  if (change.endedAt !== undefined) request.endedAt = change.endedAt
  const result = change.result && oneLine(change.result, 60)
  if (result) request.result = result
  if (change.prompt === undefined && !change.steps?.length && !change.replaceSteps && !change.status) return

  const prior = detailOf(draft, request.id)
  const steps = new Map(change.replaceSteps ? [] : prior.steps.map(step => [step.id, step]))
  for (const step of change.steps ?? []) steps.set(step.id, step.full.length <= MAX_STEP ? step
    : { ...step, full: truncate(step.full, MAX_STEP) })
  const recent = [...steps.values()].sort((a, b) => a.at - b.at).slice(-60)
  Object.assign(request, summarizeSteps(recent))
  if (!result) {
    const latestResult = requestResult(request, recent)
    if (latestResult && (change.status || requestResult(request, change.steps ?? []))) request.result = latestResult
  }
  const detail = {
    prompt: change.prompt === undefined ? prior.prompt : truncate(change.prompt, MAX_PROMPT),
    promptSource: change.promptSource ?? prior.promptSource,
    steps: recent,
  }
  if (JSON.stringify(prior) !== JSON.stringify(detail)) {
    draft.details.set(request.id, detail)
    request.detailVersion = (request.detailVersion ?? 0) + 1
  }
}

export function prune(draft: Draft, now: number) {
  const { value } = draft
  if (value.requests.some(request => activityAt(request) < now - FLOW_WINDOW)) {
    const kept = new Set(visibleFlows(groupFlows({ ...value, now }), now, 'all')
      .flatMap(flow => flowRows(flow.root).map(({ node }) => node.request.id)))
    value.requests = value.requests.filter(request => kept.has(request.id))
  }
  value.requests = value.requests.sort((a, b) => a.at - b.at).slice(-REQUEST_LIMIT)
  const kept = new Set(value.requests.map(request => request.id))
  const actors = new Set(value.requests.flatMap(request => [request.from, request.to]))
  value.actors = value.actors.filter(entry => actors.has(entry.id))
  for (const id of draft.details.keys()) {
    if (!kept.has(id)) draft.details.delete(id)
  }
}
