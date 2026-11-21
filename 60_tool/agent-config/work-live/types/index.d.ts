export type ActorKind = 'user' | 'claude' | 'claude-remote' | 'codex' | 'subagent' | 'unknown'
export type Actor = { id: string; kind: ActorKind; label: string }
export type RequestStatus = 'running' | 'done' | 'error' | 'stopped'
export type DisplayScope = 'running' | 'hour' | 'all'
export type StepCount = 'read' | 'search' | 'edit' | 'command' | 'web' | 'agent' | 'delegate' | 'error'
export type RequestSummary = {
  id: string
  from: string
  to: string
  verb: string
  summary: string
  at: number
  endedAt?: number
  status: RequestStatus
  stepCount: number
  lastStepAt?: number
  lastStep?: { at: number; icon: IconName; title: string }
  counts: Partial<Record<StepCount, number>>
  result?: string
}

export type Kind = 'edit' | 'command' | 'read' | 'web' | 'agent' | 'message' | 'tool' | 'done' | 'error'
export type IconName =
  | 'file' | 'search' | 'folder' | 'edit' | 'terminal' | 'git' | 'run' | 'web' | 'message' | 'agent' | 'bell' | 'delegate' | 'tool'
  | 'done' | 'error' | 'user' | 'claude' | 'cloud' | 'codex' | 'subagent' | 'unknown' | 'running' | 'idle' | 'stopped' | 'waiting'
export type Step = {
  id: string
  at: number
  kind: Kind
  icon: IconName
  title: string
  full: string
  language?: 'powershell' | 'bash'
}
export type RequestDetail = { prompt: string; promptSource: string; steps: Step[] }

export type Monitor = { status: 'ok' | 'off' } | { status: 'unavailable'; reason: string }
export type Monitors = { codex: Monitor; sessions: Monitor }

export type CodexLaunch = { cwd: string; mode: 'exec' | 'review'; readOnly: boolean; shellEndedAt?: number }
export type LiveRequest = RequestSummary & {
  codexLaunch?: CodexLaunch
  codexOrigin?: { cwd: string; at: number }
  parentId?: string
  awaitingPrompt?: boolean
  estimatedObservation?: boolean
  observation?: string
  awaitingSession?: 'start' | 'message'
  detailVersion?: number
}
export type LiveBoard = {
  actors: Actor[]
  requests: LiveRequest[]
  monitors: Monitors
}

export type RolloutMeta = {
  id: string
  cwd: string
  originator: string
  source: string
  timestamp?: number
  parentThreadId: string
}
export type TurnRef = { id: string; at: number }
export type CodexTurnRef = TurnRef & {
  notified?: boolean
  pending?: { id: string; kind: 'command' | 'patch'; operationId: string; step: Step }[]
}
export type ClaudeSession = {
  id: string
  cwd: string
  label: string
  startedAt?: number
  lastActiveAt?: number
  isRunning: boolean
}
