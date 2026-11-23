import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { toolJobApi, type MaintenanceDistributionOptions, type ToolCapabilities, type ToolLogLine, type ToolPlan, type ToolRun } from '../api/toolJobApi'
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
const maintenanceCatalog: MaintenanceDistributionOptions = {
  configurationDigest: 'A'.repeat(64),
  options: [
    { id: 'd1-t1', kind: 'Jar', label: 'AstralRecord.jar', source: 'Dev', destination: 'Channel1' },
    { id: 'd1-t2', kind: 'Jar', label: 'AstralRecord.jar', source: 'Dev', destination: 'Channel2' },
    { id: 'd2-t1', kind: 'World', label: 'worlds/skill_tree', source: 'Build', destination: 'Channel1' },
    { id: 'd3-t1', kind: 'Jar', label: 'Custom.jar', source: 'Dev', destination: 'Channel1' },
    { id: 'd4-t1', kind: 'Placement', label: 'npc_locations.yml', source: 'Build', destination: 'Channel1' },
    { id: 'd5-t1', kind: 'Filebase', label: 'filebase', source: 'Build', destination: 'Channel1' },
    { id: 'n1', kind: 'Network', label: 'AstralRecordLobby.jar', source: 'Build', destination: 'Lobby' },
    { id: 'n2', kind: 'Network', label: 'AstralRecordProxy.jar', source: 'Build', destination: 'Proxy' },
    { id: 'n3', kind: 'Network', label: 'AstralRecordGeyserExtension.jar', source: 'Build', destination: 'Geyser' },
  ],
  savedSelection: ['d1-t2', 'n1'],
}

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

it('allows different tools to run together and reopen the correct run in a separate history pane', async () => {
  const run: ToolRun = {
    id: 'recent', toolId: '12', plan: availablePlan({ warnings: ['実行時の警告'] }), status: 'running',
    startedAtUtc: new Date().toISOString(), finishedAtUtc: null, exitCode: null, prompt: null,
  }
  const deploy: ToolRun = {
    ...run, id: 'deploy', toolId: '01', plan: availablePlan({ toolId: '01' }), status: 'waitingForInput',
    prompt: { id: 'dev-prompt', message: 'Dev の確認', choices: [{ value: 'continue', label: '続行' }] },
  }
  let currentRuns: ToolRun[] = []
  vi.spyOn(toolJobApi, 'runs').mockImplementation(async () => currentRuns)
  vi.spyOn(toolJobApi, 'logs').mockImplementation(async (_id, after) => ({ lines: [], next: after }))
  vi.spyOn(toolJobApi, 'startDirect').mockImplementation(async (_requestId, settings) => {
    const started = settings.toolId === '12' ? run : deploy
    currentRuns = [started, ...currentRuns]
    return started
  })
  const openHistory = vi.fn()
  const panes = <DesktopToolRunHistoryProvider enabled>
    <section aria-label="ビルド画面"><DesktopToolWorkspace {...props} page="12" onOpenHistory={openHistory} /></section>
    <section aria-label="Dev 更新画面"><DesktopToolWorkspace {...props} page="01" onOpenHistory={openHistory} /></section>
    <section aria-label="履歴画面"><DesktopToolWorkspace {...props} page="history" /></section>
  </DesktopToolRunHistoryProvider>

  render(panes)
  const build = within(screen.getByRole('region', { name: 'ビルド画面' }))
  const dev = within(screen.getByRole('region', { name: 'Dev 更新画面' }))
  const history = within(screen.getByRole('region', { name: '履歴画面' }))
  const action = build.getByRole('button', { name: '実行' })
  await waitFor(() => expect(action).toBeEnabled())
  fireEvent.click(action)
  expect(await build.findByText('実行時の警告')).toBeVisible()
  expect(action).toBeDisabled()
  const deployAction = dev.getByRole('button', { name: '実行' })
  expect(deployAction).toBeEnabled()
  fireEvent.click(deployAction)
  expect(await dev.findByText('Dev の確認')).toBeVisible()
  expect(deployAction).toBeDisabled()
  expect(build.queryByText('Dev の確認')).not.toBeInTheDocument()

  const buildHistoryLink = dev.getByRole('button', { name: /ネットワーク.*の実行履歴を表示/ })
  fireEvent.click(buildHistoryLink)
  expect(openHistory).toHaveBeenLastCalledWith('recent')
  expect(await history.findByRole('heading', { name: '実行時の計画' })).toBeVisible()
  expect(history.queryByText('Dev の確認')).not.toBeInTheDocument()
  fireEvent.click(history.getByRole('button', { name: '詳細を閉じる' }))
  expect(history.queryByRole('heading', { name: '実行時の計画' })).not.toBeInTheDocument()
  fireEvent.click(buildHistoryLink)
  expect(await history.findByRole('heading', { name: '実行時の計画' })).toBeVisible()
  fireEvent.click(build.getByRole('button', { name: /Dev.*の実行履歴を表示/ }))
  expect(openHistory).toHaveBeenLastCalledWith('deploy')
  expect(await history.findByText('Dev の確認')).toBeVisible()
})

it('aborts the previous log read when switching to another run and ignores its late response', async () => {
  const build: ToolRun = {
    id: 'build', toolId: '12', plan: availablePlan(), status: 'running',
    startedAtUtc: new Date().toISOString(), finishedAtUtc: null, exitCode: null, prompt: null,
  }
  const deploy: ToolRun = { ...build, id: 'deploy', toolId: '01', plan: availablePlan({ toolId: '01' }) }
  vi.spyOn(toolJobApi, 'runs').mockResolvedValue([build, deploy])
  let completeBuildLog: (result: { lines: ToolLogLine[]; next: number }) => void = () => {}
  let buildSignal: AbortSignal | undefined
  vi.spyOn(toolJobApi, 'logs').mockImplementation(async (id, after, signal) => {
    if (id === 'build') {
      buildSignal = signal
      return new Promise(resolve => { completeBuildLog = resolve })
    }
    return { lines: after === 0 ? [{ sequence: 1, stream: 'output', text: '配布ログ' }] : [], next: 1 }
  })
  render(<DesktopToolWorkspace {...props} page="history" />)
  const [buildButton, deployButton] = await screen.findAllByRole('button', { name: '詳細を表示' })
  fireEvent.click(buildButton)
  await waitFor(() => expect(buildSignal?.aborted).toBe(false))
  fireEvent.click(deployButton)
  expect(await screen.findByText('配布ログ')).toBeVisible()
  expect(buildSignal?.aborted).toBe(true)
  await act(async () => completeBuildLog({ lines: [{ sequence: 1, stream: 'output', text: 'ビルドログ' }], next: 1 }))
  expect(screen.queryByText('ビルドログ')).not.toBeInTheDocument()
  expect(screen.getByText('配布ログ')).toBeVisible()
})

it('defaults AstralArchitect to building only and starts deployment through a confirmed plan', async () => {
  let runs: ToolRun[] = []
  vi.spyOn(toolJobApi, 'runs').mockImplementation(async () => runs)
  vi.spyOn(toolJobApi, 'logs').mockResolvedValue({ lines: [], next: 0 })
  const buildPlan = availablePlan({ id: 'build', toolId: '09', confirmations: [] })
  const deployPlan = availablePlan({
    id: 'deploy', toolId: '09', confirmations: [{ id: 'deploy', label: '配置先を確認しました' }],
    paths: [{ label: 'JAR 配置', source: 'C:/fixture/dist/AstralArchitect-*.jar', destination: 'C:/fixture/plugins/AstralArchitect.jar' }],
  })
  const makePlan = vi.spyOn(toolJobApi, 'plan').mockResolvedValueOnce(buildPlan).mockResolvedValueOnce(deployPlan)
  const direct = vi.spyOn(toolJobApi, 'startDirect')
  const start = vi.spyOn(toolJobApi, 'start').mockImplementation(async () => {
    const run: ToolRun = {
      id: 'run', toolId: '09', plan: deployPlan, status: 'succeeded', startedAtUtc: new Date().toISOString(),
      finishedAtUtc: new Date().toISOString(), exitCode: 0, prompt: null,
    }
    runs = [run]
    return run
  })
  render(<DesktopToolWorkspace {...props} page="09" />)

  const action = screen.getByRole('button', { name: '実行' })
  const checkPlan = screen.getByRole('button', { name: '実行計画を確認' })
  const mode = screen.getByLabelText('実行方法')
  await waitFor(() => expect(checkPlan).toBeEnabled())
  expect(mode).toHaveValue('BuildOnly')
  expect(action).toBeDisabled()
  fireEvent.click(checkPlan)
  fireEvent.click(await screen.findByRole('checkbox', { name: '実行計画を確認しました' }))
  expect(action).toBeEnabled()

  fireEvent.change(mode, { target: { value: 'Deploy' } })
  expect(action).toBeDisabled()
  fireEvent.click(checkPlan)
  expect(await screen.findByText('C:/fixture/plugins/AstralArchitect.jar')).toBeVisible()
  fireEvent.click(screen.getByRole('checkbox', { name: '配置先を確認しました' }))
  fireEvent.click(action)
  expect(await screen.findByRole('heading', { name: '実行結果' })).toBeVisible()
  expect(makePlan).toHaveBeenNthCalledWith(1, { toolId: '09', architectMode: 'BuildOnly' })
  expect(makePlan).toHaveBeenNthCalledWith(2, { toolId: '09', architectMode: 'Deploy' })
  expect(start).toHaveBeenCalledExactlyOnceWith('deploy', ['deploy'])
  expect(direct).not.toHaveBeenCalled()
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

it('plans maintenance for selected destinations and clears approvals when the choices change', async () => {
  vi.spyOn(toolJobApi, 'runs').mockResolvedValue([])
  const readOptions = vi.spyOn(toolJobApi, 'maintenanceOptions').mockResolvedValue(maintenanceCatalog)
  const makePlan = vi.spyOn(toolJobApi, 'plan').mockResolvedValue(availablePlan({
    toolId: '16', confirmations: [{ id: 'serversStopped', label: 'サーバーを停止しました' }],
  }))
  render(<DesktopToolWorkspace {...props} page="16" />)

  const detailed = screen.getByRole('radio', { name: '配布先ごとに選ぶ' })
  const normal = screen.getByRole('radio', { name: '通常配布' })
  await waitFor(() => expect(detailed).toBeEnabled())
  expect(normal).toBeChecked()
  expect(readOptions).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '実行計画を確認' }))
  const normalConfirm = await screen.findByRole('checkbox', { name: 'サーバーを停止しました' })
  expect(makePlan).toHaveBeenLastCalledWith({ toolId: '16', recovery: 'Auto', worldCopy: 'Skip', networkPlugins: 'Skip' })
  fireEvent.change(screen.getByLabelText('ワールドのコピー'), { target: { value: 'Include' } })
  expect(normalConfirm).not.toBeInTheDocument()
  fireEvent.click(detailed)
  const first = await screen.findByRole('checkbox', { name: /AstralRecord\.jar.*Channel1/ })
  const second = screen.getByRole('checkbox', { name: /AstralRecord\.jar.*Channel2/ })
  const world = screen.getByRole('checkbox', { name: /skill_tree/ })
  const network = screen.getByRole('checkbox', { name: /AstralRecordLobby\.jar/ })
  expect(first).toBeChecked()
  expect(second).toBeChecked()
  expect(world).toBeChecked()
  expect(network).not.toBeChecked()

  fireEvent.click(first)
  fireEvent.click(world)
  fireEvent.click(screen.getByRole('checkbox', { name: /Custom\.jar/ }))
  fireEvent.click(screen.getByRole('checkbox', { name: /npc_locations\.yml/ }))
  fireEvent.click(screen.getByRole('checkbox', { name: /filebase/ }))
  fireEvent.click(screen.getByRole('button', { name: '実行計画を確認' }))
  const confirm = await screen.findByRole('checkbox', { name: 'サーバーを停止しました' })
  expect(makePlan).toHaveBeenLastCalledWith({
    toolId: '16', recovery: 'Auto', distributionSelection: ['d1-t2'],
    distributionConfigurationDigest: maintenanceCatalog.configurationDigest,
  })
  fireEvent.click(confirm)
  expect(screen.getByRole('button', { name: '配布を開始' })).toBeEnabled()

  fireEvent.click(screen.getByRole('button', { name: '全解除' }))
  expect(confirm).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '実行計画を確認' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '配布を開始' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '全選択' }))
  expect(network).toBeChecked()
  expect(first).toBeChecked()

  fireEvent.click(screen.getByRole('button', { name: '前回の選択を適用' }))
  expect(first).not.toBeChecked()
  expect(second).toBeChecked()
  expect(world).not.toBeChecked()
  expect(network).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '実行計画を確認' }))
  await waitFor(() => expect(makePlan).toHaveBeenLastCalledWith({
    toolId: '16', recovery: 'Auto', distributionSelection: ['d1-t2', 'n1'],
    distributionConfigurationDigest: maintenanceCatalog.configurationDigest,
  }))
  await waitFor(() => expect(normal).toBeEnabled())
  fireEvent.click(normal)
  expect(screen.getByLabelText('ワールドのコピー')).toHaveValue('Include')
  expect(screen.queryByRole('checkbox', { name: /AstralRecord\.jar.*Channel1/ })).not.toBeInTheDocument()
})

it('retries distribution discovery and clears choices when the configuration changes', async () => {
  vi.spyOn(toolJobApi, 'runs').mockResolvedValue([])
  vi.spyOn(toolJobApi, 'plan').mockResolvedValue(availablePlan({ toolId: '16' }))
  vi.spyOn(toolJobApi, 'maintenanceOptions')
    .mockRejectedValueOnce(new Error('配布設定を確認してください。'))
    .mockResolvedValueOnce(maintenanceCatalog)
    .mockResolvedValueOnce({ ...maintenanceCatalog, configurationDigest: 'B'.repeat(64), savedSelection: null })
  render(<DesktopToolWorkspace {...props} page="16" />)

  const detailed = screen.getByRole('radio', { name: '配布先ごとに選ぶ' })
  await waitFor(() => expect(detailed).toBeEnabled())
  fireEvent.click(detailed)
  expect(await screen.findByRole('alert')).toHaveTextContent('配布設定を確認してください。')
  expect(screen.getByRole('button', { name: '実行計画を確認' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '再取得' }))
  expect(await screen.findByRole('checkbox', { name: /AstralRecord\.jar.*Channel1/ })).toBeChecked()

  fireEvent.click(screen.getByRole('button', { name: '実行計画を確認' }))
  const confirmation = await screen.findByRole('checkbox', { name: '出力先を確認しました' })
  fireEvent.click(confirmation)
  expect(screen.getByRole('button', { name: '配布を開始' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: '候補を再取得' }))
  expect(confirmation).not.toBeInTheDocument()
  expect(await screen.findByRole('checkbox', { name: /AstralRecord\.jar.*Channel1/ })).not.toBeChecked()
  expect(screen.getByText('配布設定が変わりました。配布対象を選び直してください。')).toBeVisible()
  expect(screen.getByRole('button', { name: '実行計画を確認' })).toBeDisabled()
  expect(screen.queryByRole('button', { name: '前回の選択を適用' })).not.toBeInTheDocument()
})

it('plans network destinations only, including after select all, and clears approval on a scope switch', async () => {
  vi.spyOn(toolJobApi, 'runs').mockResolvedValue([])
  vi.spyOn(toolJobApi, 'maintenanceOptions').mockResolvedValue(maintenanceCatalog)
  const makePlan = vi.spyOn(toolJobApi, 'plan').mockResolvedValue(availablePlan({ toolId: '16' }))
  render(<DesktopToolWorkspace {...props} page="16" />)

  const networkOnly = screen.getByRole('radio', { name: 'ネットワークのみ' })
  await waitFor(() => expect(networkOnly).toBeEnabled())
  fireEvent.click(networkOnly)
  const candidates = await screen.findByRole('region', { name: '配布候補' })
  const lobby = within(candidates).getByRole('checkbox', { name: /AstralRecordLobby\.jar/ })
  const proxy = within(candidates).getByRole('checkbox', { name: /AstralRecordProxy\.jar/ })
  const extension = within(candidates).getByRole('checkbox', { name: /AstralRecordGeyserExtension\.jar/ })
  expect(within(candidates).getAllByRole('checkbox')).toEqual([lobby, proxy, extension])
  for (const candidate of [lobby, proxy, extension]) expect(candidate).toBeChecked()
  expect(screen.getByText('前回の選択は「配布先ごとに選ぶ」で適用できます。')).toBeVisible()
  expect(screen.queryByRole('button', { name: '前回の選択を適用' })).not.toBeInTheDocument()

  fireEvent.click(proxy)
  fireEvent.click(screen.getByRole('button', { name: '実行計画を確認' }))
  const confirmation = await screen.findByRole('checkbox', { name: '出力先を確認しました' })
  expect(makePlan).toHaveBeenLastCalledWith({
    toolId: '16', recovery: 'Auto', distributionSelection: ['n1', 'n3'],
    distributionConfigurationDigest: maintenanceCatalog.configurationDigest,
  })
  fireEvent.click(confirmation)
  expect(screen.getByRole('button', { name: '配布を開始' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: '全選択' }))
  expect(confirmation).not.toBeInTheDocument()
  expect(proxy).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '実行計画を確認' }))
  const nextConfirmation = await screen.findByRole('checkbox', { name: '出力先を確認しました' })
  expect(nextConfirmation).not.toBeChecked()
  expect(makePlan).toHaveBeenLastCalledWith({
    toolId: '16', recovery: 'Auto', distributionSelection: ['n1', 'n2', 'n3'],
    distributionConfigurationDigest: maintenanceCatalog.configurationDigest,
  })
  fireEvent.click(nextConfirmation)
  fireEvent.click(screen.getByRole('radio', { name: '配布先ごとに選ぶ' }))
  expect(nextConfirmation).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '配布を開始' })).toBeDisabled()
  fireEvent.click(await screen.findByRole('button', { name: '前回の選択を適用' }))
  expect(screen.getByRole('checkbox', { name: /AstralRecord\.jar.*Channel2/ })).toBeChecked()
})

it('applies a saved network selection within the network scope', async () => {
  vi.spyOn(toolJobApi, 'runs').mockResolvedValue([])
  vi.spyOn(toolJobApi, 'maintenanceOptions').mockResolvedValue({ ...maintenanceCatalog, savedSelection: ['n2'] })
  const makePlan = vi.spyOn(toolJobApi, 'plan').mockResolvedValue(availablePlan({ toolId: '16' }))
  render(<DesktopToolWorkspace {...props} page="16" />)

  const networkOnly = screen.getByRole('radio', { name: 'ネットワークのみ' })
  await waitFor(() => expect(networkOnly).toBeEnabled())
  fireEvent.click(networkOnly)
  fireEvent.click(await screen.findByRole('button', { name: '前回の選択を適用' }))
  expect(screen.getByRole('checkbox', { name: /AstralRecordLobby\.jar/ })).not.toBeChecked()
  expect(screen.getByRole('checkbox', { name: /AstralRecordProxy\.jar/ })).toBeChecked()
  fireEvent.click(screen.getByRole('button', { name: '実行計画を確認' }))
  expect(makePlan).toHaveBeenCalledExactlyOnceWith({
    toolId: '16', recovery: 'Auto', distributionSelection: ['n2'],
    distributionConfigurationDigest: maintenanceCatalog.configurationDigest,
  })
  await screen.findByRole('checkbox', { name: '出力先を確認しました' })
})

it('requires a configured network destination even if ordinary candidates exist', async () => {
  vi.spyOn(toolJobApi, 'runs').mockResolvedValue([])
  vi.spyOn(toolJobApi, 'maintenanceOptions').mockResolvedValue({
    ...maintenanceCatalog, options: maintenanceCatalog.options.filter(option => option.kind !== 'Network'), savedSelection: null,
  })
  const makePlan = vi.spyOn(toolJobApi, 'plan')
  render(<DesktopToolWorkspace {...props} page="16" />)

  const networkOnly = screen.getByRole('radio', { name: 'ネットワークのみ' })
  await waitFor(() => expect(networkOnly).toBeEnabled())
  fireEvent.click(networkOnly)
  expect(await screen.findByText('ネットワークの配布先が設定されていません。配布設定を確認してください。')).toBeVisible()
  expect(screen.queryByRole('region', { name: '配布候補' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '全選択' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '実行計画を確認' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '配布を開始' })).toBeDisabled()
  expect(makePlan).not.toHaveBeenCalled()
})
