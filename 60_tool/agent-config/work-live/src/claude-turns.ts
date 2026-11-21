import type { Actor, ClaudeSession, LiveRequest } from '../types/index.d.ts'
import { agentActor, attachAgent, beginTool, finishTool, toolRequestId } from './claude-tools.ts'
import type { ToolCall } from './claude-tools.ts'
import { describeMessage } from './describe.ts'
import { inferVerb, isNotification, notification, recordOf, stepOf, textOf, USER } from './model.ts'
import { claudeActor, knownActor, matchSessionRequest, observedRequest } from './observe.ts'
import { blocksOf, claudePrompt, contentText, isClaudePrompt, taskCompletion } from './parse-claude.ts'
import type { ClaudeRow } from './parse-claude.ts'
import { createRequest, editRequest, rememberActor } from './state.ts'
import type { Draft } from './state.ts'
import type { AgentRecord, Transcript } from './watch-claude.ts'

type ChatCursor = { current?: string; cwd?: string; parents: Map<string, string>; tools: Map<string, ToolCall> }
const PARENT_LIMIT = 512
const TOOL_LIMIT = 120
const SOURCE = 'Claude のセッション記録'

function retain<K, V>(items: Map<K, V>, limit: number) {
  while (items.size > limit) items.delete(items.keys().next().value!)
}

function stopTurn(draft: Draft, request: LiveRequest) {
  if (request.status === 'running') {
    editRequest(draft, request, { status: 'stopped', endedAt: request.lastStepAt ?? request.at })
  }
}

function beginPrompt(draft: Draft, cursor: ChatCursor, id: string, from: Actor, to: Actor, prompt: string,
  at: number, launched?: LiveRequest, recovered = false) {
  const prior = observedRequest(draft, cursor.current)
  const existing = observedRequest(draft, id)
  if (prior && prior !== existing && prior !== launched) stopTurn(draft, prior)
  let request = existing ?? (launched && !launched.observation ? launched : undefined) ??
    matchSessionRequest(draft, to, prompt, at)
  if (!request) {
    request = createRequest(draft, id, from, to, inferVerb(prompt), prompt, at)
    editRequest(draft, request, { prompt, promptSource: recovered ? SOURCE + '（開始時刻を推定）' : SOURCE })
  }
  request.observation = id
  delete request.awaitingSession
  cursor.current = id
  return request
}

function recoverPrompt(rows: ClaudeRow[]) {
  const index = rows.findIndex(row => !!claudePrompt(row))
  const prefix = index < 0 ? rows : rows.slice(0, index)
  const first = prefix.find(row => row.type === 'assistant' || blocksOf(recordOf(row.data.message).content)
    .some(block => block.type === 'tool_result'))
  const snapshot = prefix.findLast(row => row.type === 'last-prompt' && isClaudePrompt(textOf(row.data.lastPrompt)))
  if (!first || !snapshot) return undefined
  return { at: first.at, id: 'tail:' + first.id, prompt: textOf(snapshot.data.lastPrompt) }
}

function recordAssistant(draft: Draft, request: LiveRequest | undefined, row: ClaudeRow, stream: string) {
  if (!request) return
  const message = recordOf(row.data.message)
  const body = contentText(message.content)
  const reason = textOf(message.stop_reason)
  const complete = reason === 'end_turn' || reason === 'stop_sequence'
  const failed = row.data.isApiErrorMessage === true ||
    ['max_tokens', 'model_context_window_exceeded', 'refusal'].includes(reason)
  if (request.status === 'stopped' && (body || reason === 'tool_use')) editRequest(draft, request, { status: 'running' })
  if (!body && !complete && !failed) return
  const step = body ? stepOf(stream + ':' + row.id, row.at, {
    kind: failed ? 'error' : 'message', title: describeMessage(body, 'Claude のメッセージ'), full: body,
  }) : undefined
  editRequest(draft, request, {
    steps: step ? [step] : undefined, status: failed ? 'error' : complete ? 'done' : undefined,
    endedAt: failed || complete ? Math.max(row.at, request.at) : undefined,
  })
}

function recordSystem(draft: Draft, request: LiveRequest | undefined, row: ClaudeRow) {
  if (!request) return
  if (row.data.subtype === 'stop_hook_summary') {
    if (row.data.preventedContinuation === true) {
      if (request.status === 'done') editRequest(draft, request, { status: 'running' })
    } else if (request.status === 'running') editRequest(draft, request, { status: 'done', endedAt: row.at })
  } else if (row.data.subtype === 'turn_aborted') {
    editRequest(draft, request, { status: 'error', endedAt: row.at })
  }
}

export function observeClaude(
  draft: Draft, changes: Transcript[], sessions: readonly ClaudeSession[], aliases: ReadonlyMap<string, string>,
  stopped: ReadonlySet<string>, cursors: Map<string, ChatCursor>, now: number,
) {
  const sessionsById = new Map(sessions.map(session => [session.id, session]))
  for (const session of sessions) {
    const entry = claudeActor(session.id, session.label)
    if (session.label || !draft.value.actors.some(actor => actor.id === entry.id)) rememberActor(draft, entry)
  }
  const agents = new Map<string, AgentRecord>()
  for (const { agent, sessionId } of changes) {
    if (agent?.toolUseId) agents.set(toolRequestId(sessionId, agent.toolUseId), agent)
  }
  const ordered = [...changes].sort((a, b) => (a.agent?.depth ?? 0) - (b.agent?.depth ?? 0) ||
    (a.records[0]?.at ?? Infinity) - (b.records[0]?.at ?? Infinity))
  for (const log of ordered) {
    const { sessionId, agent } = log
    const session = knownActor(draft, claudeActor(sessionId))
    let own = agent ? agentActor(sessionId, agent.id, agent.name) : session
    const launched = agent && (observedRequest(draft, toolRequestId(sessionId, agent.toolUseId)) ??
      draft.value.requests.find(request => request.to === own.id && request.id !== request.observation))
    if (launched) {
      const previous = draft.value.actors.find(actor => actor.id === launched.to)
      if (previous && previous.label !== 'サブエージェント') own = { ...own, label: previous.label }
      attachAgent(draft, launched, own)
    }
    const from = agent ? draft.value.actors.find(actor => actor.id === launched?.from) ?? session : USER
    const current: ChatCursor = (log.reset ? undefined : cursors.get(log.path)) ??
      { parents: new Map<string, string>(), tools: new Map<string, ToolCall>() }
    cursors.set(log.path, current)
    const stream = 'claude-turn:' + sessionId + (agent ? ':agent:' + agent.id : '')
    const base = { session: sessionId, actor: own, agents, aliases, cwd: sessionsById.get(sessionId)?.cwd ?? '' }
    if (log.fromTail && !current.current) {
      const saved = recoverPrompt(log.records)
      if (saved) beginPrompt(draft, current, stream + ':' + saved.id, from, own, saved.prompt, saved.at, launched, true)
      else if (launched && !log.records.some(claudePrompt)) current.current = launched.observation ?? launched.id
    }
    for (const row of log.records) {
      let turn = (row.parent && current.parents.get(row.parent)) || current.current
      let request = observedRequest(draft, turn)
      const message = recordOf(row.data.message)
      current.cwd = textOf(row.data.cwd) || current.cwd
      const context = { ...base, cwd: current.cwd || base.cwd }
      if (row.type === 'user') {
        for (const result of blocksOf(message.content)) {
          if (result.type !== 'tool_result') continue
          const call = current.tools.get(textOf(result.tool_use_id))
          if (!call) continue
          turn = call.parent ?? turn
          finishTool(draft, context, call, result, recordOf(row.data.toolUseResult), row.at, now)
        }
        const body = contentText(message.content)
        const prompt = claudePrompt(row)
        if (prompt) {
          turn = stream + ':' + row.id
          beginPrompt(draft, current, turn, from, own, prompt, row.at, launched)
        } else if (isNotification(body)) {
          const step = stepOf(stream + ':' + row.id, row.at, notification(body))
          const latest = observedRequest(draft, current.current)
          if (latest) editRequest(draft, latest, { steps: [step] })
          const completion = taskCompletion(body)
          if (completion) {
            const target = observedRequest(draft, toolRequestId(sessionId, completion.toolUseId))
            if (target?.codexLaunch && !target.observation) {
              editRequest(draft, target, { steps: [step], status: completion.status, endedAt: row.at, result: completion.summary })
            }
          }
        } else if (/^\s*\[Request interrupted by user(?: for tool use)?\]/iu.test(body) && request) {
          editRequest(draft, request, { status: 'error', endedAt: row.at, steps: [
            stepOf(stream + ':' + row.id, row.at, { kind: 'error', title: '作業中断', full: body }),
          ] })
        }
      } else if (row.type === 'assistant') {
        recordAssistant(draft, request, row, stream)
        for (const block of blocksOf(message.content)) {
          if (block.type !== 'tool_use') continue
          const call = beginTool(draft, context, request, block, row.at)
          if (call) current.tools.set(textOf(block.id), call)
        }
      } else if (row.type === 'system') recordSystem(draft, request, row)
      else if (row.type === 'oversized' && request) {
        editRequest(draft, request, { steps: [stepOf(stream + ':' + row.id, row.at, {
          kind: 'tool', title: '長い記録を省略', full: '（長すぎるため省略: ' + Math.ceil(Number(row.data.bytes) / 1024) + ' KB）',
        })] })
      }
      if (turn) current.parents.set(row.id, turn)
      retain(current.parents, PARENT_LIMIT)
      retain(current.tools, TOOL_LIMIT)
    }
    const latest = observedRequest(draft, current.current) ?? launched
    if (latest && agent?.finished) stopTurn(draft, latest)
  }
  for (const request of draft.value.requests) {
    for (const id of stopped) {
      if (request.to === 'claude:' + id || request.to.startsWith('agent:' + id + ':')) stopTurn(draft, request)
    }
  }
}
