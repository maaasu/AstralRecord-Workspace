import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { toolJobApi, type ToolCapabilities, type ToolPlan, type ToolRun } from '../api/toolJobApi'
import { DesktopToolRunHistoryProvider, DesktopToolWorkspace } from './DesktopToolWorkspace'

const capabilities: ToolCapabilities = {
  enabled: true, reason: null, workspaceRoot: 'C:/AstralRecord', activeRunId: null,
}
const props = { visible: true, capabilities, onRetryCapabilities: vi.fn(), hasUnsavedSource: false, onOpenHistory: vi.fn() }
const plan: ToolPlan = {
  id: 'plan', toolId: '12', workspaceRoot: capabilities.workspaceRoot,
  configurationPath: null, filebaseSource: null, workspaceMismatch: false, canRun: true,
  warnings: [], summary: ['Lobby の JAR をビルド'], paths: [],
  confirmations: [{ id: 'artifacts', label: '出力先を確認しました' }],
  expiresAtUtc: '',
}
const availablePlan = (fields?: Partial<ToolPlan>): ToolPlan => ({ ...plan, ...fields, expiresAtUtc: new Date(Date.now() + 60000).toISOString() })

afterEach(() => vi.restoreAllMocks())

it.each([
  { toolId: '03', serverAlreadyRunning: false, recovery: 'Auto' },
  { toolId: '01', pluginTestMode: 'All', recovery: 'Auto' },
  { toolId: '12', networkTarget: 'All', skipTests: false },
] as const)('starts $toolId with its current settings before requesting a preview', async settings => {
  vi.spyOn(toolJobApi, 'runs').mockResolvedValue([])
  vi.spyOn(toolJobApi, 'logs').mockResolvedValue({ lines: [], next: 0 })
  const makePlan = vi.spyOn(toolJobApi, 'plan')
  const start = vi.spyOn(toolJobApi, 'start')
  const startDirect = vi.spyOn(toolJobApi, 'startDirect').mockResolvedValue({
    id: 'run', toolId: settings.toolId, plan: availablePlan({ toolId: settings.toolId }),
    status: 'succeeded', startedAtUtc: new Date().toISOString(), finishedAtUtc: new Date().toISOString(),
    exitCode: 0, prompt: null,
  })
  render(<DesktopToolWorkspace {...props} page={settings.toolId} />)

  const action = screen.getByRole('button', { name: '実行' })
  await waitFor(() => expect(action).toBeEnabled())
  fireEvent.click(action)
  await waitFor(() => expect(startDirect).toHaveBeenCalledExactlyOnceWith(expect.any(String), settings))
  expect(makePlan).not.toHaveBeenCalled()
  expect(start).not.toHaveBeenCalled()
  expect(screen.queryByRole('group', { name: '実行前の確認' })).not.toBeInTheDocument()
})

it('displays optional previews and sends updated settings, reusing the ID if a response is lost', async () => {
  vi.spyOn(toolJobApi, 'runs').mockResolvedValue([])
  const makePlan = vi.spyOn(toolJobApi, 'plan')
    .mockResolvedValueOnce(availablePlan({ workspaceMismatch: true }))
  let rejectStart: (reason: Error) => void = () => {}
  const startDirect = vi.spyOn(toolJobApi, 'startDirect')
    .mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectStart = reject }))
    .mockRejectedValueOnce(new Error('現在の設定が実行条件を満たしていません。'))

  render(<DesktopToolWorkspace {...props} page="12" />)
  const action = screen.getByRole('button', { name: '実行' })
  const viewPlan = screen.getByRole('button', { name: '実行計画を表示' })
  await waitFor(() => expect(action).toBeEnabled())
  fireEvent.click(viewPlan)
  expect(await screen.findByText('編集ワークスペースと同期元が異なります。')).toBeVisible()
  expect(screen.queryByRole('group', { name: '実行前の確認' })).not.toBeInTheDocument()
  expect(makePlan).toHaveBeenCalledExactlyOnceWith({ toolId: '12', networkTarget: 'All', skipTests: false })
  expect(action).toBeEnabled()

  fireEvent.change(screen.getByLabelText('ビルド対象'), { target: { value: 'Lobby' } })
  expect(screen.queryByRole('heading', { name: '計画の詳細' })).not.toBeInTheDocument()
  fireEvent.click(action)
  fireEvent.click(action)
  expect(startDirect).toHaveBeenCalledExactlyOnceWith(expect.any(String), { toolId: '12', networkTarget: 'Lobby', skipTests: false })
  expect(action).toBeDisabled()

  await act(async () => rejectStart(new Error('応答を受信できませんでした。')))
  expect(await screen.findByRole('alert')).toHaveTextContent('応答を受信できませんでした。')
  fireEvent.click(action)
  expect(startDirect).toHaveBeenCalledTimes(2)
  expect(startDirect.mock.calls[1]).toEqual(startDirect.mock.calls[0])
  expect(await screen.findByRole('alert')).toHaveTextContent('現在の設定が実行条件を満たしていません。')
})

it('passes a direct run between separately mounted tool and history panes', async () => {
  const run: ToolRun = {
    id: 'recent', toolId: '12', plan: availablePlan({ warnings: ['実行時の警告'] }), status: 'running',
    startedAtUtc: new Date().toISOString(), finishedAtUtc: null, exitCode: null, prompt: null,
  }
  let currentRuns: ToolRun[] = []
  vi.spyOn(toolJobApi, 'runs').mockImplementation(async () => currentRuns)
  vi.spyOn(toolJobApi, 'logs').mockImplementation(async (_id, after) => ({ lines: [], next: after }))
  vi.spyOn(toolJobApi, 'startDirect').mockImplementation(async () => { currentRuns = [run]; return run })
  const openHistory = vi.fn()
  const panes = (historyRunId?: string) => <DesktopToolRunHistoryProvider enabled>
    <section aria-label="ビルド画面"><DesktopToolWorkspace {...props} page="12" onOpenHistory={openHistory} /></section>
    <section aria-label="Dev 更新画面"><DesktopToolWorkspace {...props} page="01" onOpenHistory={openHistory} /></section>
    <section aria-label="履歴画面"><DesktopToolWorkspace {...props} page="history" historyRunId={historyRunId} /></section>
  </DesktopToolRunHistoryProvider>

  const view = render(panes())
  const build = within(screen.getByRole('region', { name: 'ビルド画面' }))
  const dev = within(screen.getByRole('region', { name: 'Dev 更新画面' }))
  const history = within(screen.getByRole('region', { name: '履歴画面' }))
  const action = build.getByRole('button', { name: '実行' })
  await waitFor(() => expect(action).toBeEnabled())
  fireEvent.click(action)
  expect(await build.findByText('実行時の警告')).toBeVisible()
  expect(dev.getByRole('button', { name: '実行' })).toBeDisabled()
  fireEvent.click(dev.getByRole('button', { name: '実行中の処理を表示' }))
  expect(openHistory).toHaveBeenCalledExactlyOnceWith('recent')

  view.rerender(panes('recent'))
  expect(await history.findByRole('heading', { name: '実行時の計画' })).toBeVisible()
})

it('sends the confirmed plan and typed response, and opens the completed run in history', async () => {
  const runPlan = availablePlan({
    toolId: '16', summary: ['配布と起動確認'], confirmations: [{ id: 'serversStopped', label: 'サーバーを停止しました' }],
  })
  const waiting: ToolRun = {
    id: 'run', toolId: '16', plan: runPlan, status: 'waitingForInput',
    startedAtUtc: new Date().toISOString(), finishedAtUtc: null, exitCode: null,
    prompt: { id: 'check', message: '処理を続行しますか？', choices: [{ value: 'continue', label: '続行' }] },
  }
  let currentRuns: ToolRun[] = []
  vi.spyOn(toolJobApi, 'runs').mockImplementation(async () => currentRuns)
  vi.spyOn(toolJobApi, 'plan').mockResolvedValue(runPlan)
  const direct = vi.spyOn(toolJobApi, 'startDirect')
  const start = vi.spyOn(toolJobApi, 'start').mockImplementation(async () => {
    currentRuns = [waiting]
    return waiting
  })
  const input = vi.spyOn(toolJobApi, 'input').mockImplementation(async () => {
    currentRuns = [{ ...waiting, status: 'succeeded', finishedAtUtc: new Date().toISOString(), exitCode: 0, prompt: null }]
  })
  vi.spyOn(toolJobApi, 'logs').mockImplementation(async (_id, after) => after === 0
    ? { lines: [{ sequence: 1, stream: 'output', text: '配布完了' }], next: 1 }
    : { lines: [], next: after })
  const view = render(<DesktopToolWorkspace {...props} page="16" />)
  const action = screen.getByRole('button', { name: '配布を開始' })
  const checkPlan = screen.getByRole('button', { name: '実行計画を確認' })
  await waitFor(() => expect(checkPlan).toBeEnabled())
  expect(action).toBeDisabled()
  fireEvent.click(checkPlan)
  const checkbox = await screen.findByRole('checkbox', { name: 'サーバーを停止しました' })
  expect(action).toBeDisabled()
  fireEvent.click(checkbox)
  fireEvent.change(screen.getByLabelText('ワールドのコピー'), { target: { value: 'Include' } })
  expect(action).toBeDisabled()
  expect(checkbox).not.toBeInTheDocument()
  fireEvent.click(checkPlan)
  fireEvent.click(await screen.findByRole('checkbox', { name: 'サーバーを停止しました' }))
  fireEvent.click(action)
  expect(await screen.findByText('処理を続行しますか？')).toBeVisible()
  expect(start).toHaveBeenCalledExactlyOnceWith('plan', ['serversStopped'])
  expect(direct).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '続行' }))
  expect(await screen.findByRole('heading', { name: '実行結果' })).toBeVisible()
  expect(input).toHaveBeenCalledExactlyOnceWith('run', 'check', 'continue')

  fireEvent.click(screen.getByRole('button', { name: '実行履歴を見る' }))
  view.rerender(<DesktopToolWorkspace {...props} page="history" />)
  expect(screen.getByText('16 メンテナンス配布')).toBeVisible()
  expect(await screen.findByText('配布完了')).toBeVisible()
  expect(screen.getByRole('heading', { name: '実行時の計画' })).toBeVisible()
})
