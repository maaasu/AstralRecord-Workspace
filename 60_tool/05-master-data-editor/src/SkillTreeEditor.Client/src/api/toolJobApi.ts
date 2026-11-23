import { request } from './editorApi'

export type ToolId = '03' | '01' | '09' | '12' | '16'
export type ToolPage = ToolId | 'history'
export type PluginTestMode = 'All' | 'Selected' | 'Skip'
export type NetworkTarget = 'All' | 'Lobby' | 'Proxy' | 'Extension'
export type IncludeMode = 'Include' | 'Skip'
export type RecoveryMode = 'Auto' | 'Restart' | 'Restore'
export type ToolSettings =
  | { toolId: '03'; serverAlreadyRunning: boolean; recovery: Exclude<RecoveryMode, 'Restore'> }
  | { toolId: '01'; pluginTestMode: PluginTestMode; pluginTests?: string; recovery: Exclude<RecoveryMode, 'Restore'> }
  | { toolId: '09'; architectMode: 'BuildOnly' | 'Deploy' }
  | { toolId: '12'; networkTarget: NetworkTarget; skipTests: boolean }
  | { toolId: '16'; worldCopy: IncludeMode; networkPlugins: IncludeMode; recovery: RecoveryMode }

export type DirectToolSettings = Exclude<ToolSettings, { toolId: '09' | '16' }>

export interface MaintenanceDistributionSelection {
  distributionSelection: string[]
  distributionConfigurationDigest: string
}

export type ToolPlanSettings = ToolSettings | ({ toolId: '16'; recovery: RecoveryMode } & MaintenanceDistributionSelection)

export interface MaintenanceDistributionOption {
  id: string
  kind: 'Jar' | 'Placement' | 'Filebase' | 'World' | 'Network'
  label: string
  source: string
  destination: string
}

export interface MaintenanceDistributionOptions {
  configurationDigest: string
  options: MaintenanceDistributionOption[]
  savedSelection?: string[] | null
}

export interface ToolCapabilities {
  enabled: boolean
  reason: string | null
  workspaceRoot: string
  workspaceBranch?: string | null
  activeRunId: string | null
}

export interface ToolPlan {
  id: string
  toolId: ToolId
  workspaceRoot: string
  configurationPath: string | null
  filebaseSource: string | null
  workspaceMismatch: boolean
  canRun: boolean
  warnings: string[]
  summary: string[]
  paths: { label: string; source: string; destination: string }[]
  confirmations: { id: string; label: string }[]
  expiresAtUtc: string
}

export type ToolRunStatus = 'starting' | 'running' | 'waitingForInput' | 'succeeded' | 'failed' | 'interrupted'

export interface ToolRun {
  id: string
  toolId: ToolId
  plan: ToolPlan
  status: ToolRunStatus
  startedAtUtc: string
  finishedAtUtc: string | null
  exitCode: number | null
  logPath?: string | null
  prompt: { id: string; message: string; choices: { value: string; label: string }[] } | null
}

export interface ToolLogLine {
  sequence: number
  stream: 'output' | 'error'
  text: string
}

export const isRunning = (run: ToolRun) => run.status === 'starting' || run.status === 'running' || run.status === 'waitingForInput'

const base = '/api/tool-jobs'
const runUrl = (id: string) => `${base}/runs/${encodeURIComponent(id)}`
const post = (body: unknown): RequestInit => ({
  method: 'POST',
  body: JSON.stringify(body),
})

export const toolJobApi = {
  capabilities: () => request<ToolCapabilities>(`${base}/capabilities`),
  maintenanceOptions: (signal: AbortSignal) => request<MaintenanceDistributionOptions>(`${base}/maintenance-options`, { signal, cache: 'no-store' }),
  plan: (settings: ToolPlanSettings) => request<ToolPlan>(`${base}/plans`, post(settings)),
  start: (planId: string, confirmations: string[]) => request<ToolRun>(`${base}/runs`, post({ planId, confirmations })),
  startDirect: (requestId: string, settings: DirectToolSettings) =>
    request<ToolRun>(`${base}/runs/direct`, post({ requestId, settings })),
  runs: (signal?: AbortSignal) => request<ToolRun[]>(`${base}/runs`, { signal }),
  logs: (id: string, after: number, signal?: AbortSignal) =>
    request<{ lines: ToolLogLine[]; next: number }>(`${runUrl(id)}/logs?after=${after}`, { signal }),
  input: (id: string, promptId: string, value: string) => request<void>(`${runUrl(id)}/input`, post({ promptId, value })),
}
