import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { toolJobApi, type ToolCapabilities, type ToolPlan, type ToolRun } from '../api/toolJobApi'
import { DesktopToolWorkspace } from './DesktopToolWorkspace'

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

it('requires a fresh runnable plan and confirmation after settings change', async () => {
  vi.spyOn(toolJobApi, 'runs').mockResolvedValue([])
  const makePlan = vi.spyOn(toolJobApi, 'plan')
    .mockResolvedValueOnce(availablePlan())
    .mockResolvedValueOnce(availablePlan({ canRun: false, warnings: ['出力先が設定されていません。'] }))
  const start = vi.spyOn(toolJobApi, 'start')
  render(<DesktopToolWorkspace {...props} page="12" />)

  const action = screen.getByRole('button', { name: 'ビルドを開始' })
  const checkPlan = screen.getByRole('button', { name: '実行計画を確認' })
  await waitFor(() => expect(checkPlan).toBeEnabled())
  expect(action).toBeDisabled()
  fireEvent.click(checkPlan)
  const confirmation = await screen.findByRole('checkbox', { name: '出力先を確認しました' })
  fireEvent.click(confirmation)
  expect(action).toBeEnabled()

  fireEvent.change(screen.getByLabelText('ビルド対象'), { target: { value: 'Lobby' } })
  expect(action).toBeDisabled()
  expect(confirmation).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '実行計画を確認' }))
  expect(await screen.findByText('出力先が設定されていません。')).toBeVisible()
  expect(makePlan).toHaveBeenLastCalledWith({ toolId: '12', networkTarget: 'Lobby', skipTests: false })
  expect(action).toBeDisabled()
  expect(start).not.toHaveBeenCalled()
})

it('sends the confirmed plan and typed response, and opens the completed run in history', async () => {
  const runPlan = availablePlan()
  const waiting: ToolRun = {
    id: 'run', toolId: '12', plan: runPlan, status: 'waitingForInput',
    startedAtUtc: new Date().toISOString(), finishedAtUtc: null, exitCode: null,
    prompt: { id: 'check', message: '処理を続行しますか？', choices: [{ value: 'continue', label: '続行' }] },
  }
  let currentRuns: ToolRun[] = []
  vi.spyOn(toolJobApi, 'runs').mockImplementation(async () => currentRuns)
  vi.spyOn(toolJobApi, 'plan').mockResolvedValue(runPlan)
  const start = vi.spyOn(toolJobApi, 'start').mockImplementation(async () => {
    currentRuns = [waiting]
    return waiting
  })
  const input = vi.spyOn(toolJobApi, 'input').mockImplementation(async () => {
    currentRuns = [{ ...waiting, status: 'succeeded', finishedAtUtc: new Date().toISOString(), exitCode: 0, prompt: null }]
  })
  vi.spyOn(toolJobApi, 'logs').mockImplementation(async (_id, after) => after === 0
    ? { lines: [{ sequence: 1, stream: 'output', text: 'Lobby.jar を出力' }], next: 1 }
    : { lines: [], next: after })
  const view = render(<DesktopToolWorkspace {...props} page="12" />)
  const checkPlan = screen.getByRole('button', { name: '実行計画を確認' })
  await waitFor(() => expect(checkPlan).toBeEnabled())
  fireEvent.click(checkPlan)
  fireEvent.click(await screen.findByRole('checkbox', { name: '出力先を確認しました' }))
  fireEvent.click(screen.getByRole('button', { name: 'ビルドを開始' }))
  expect(await screen.findByText('処理を続行しますか？')).toBeVisible()
  expect(start).toHaveBeenCalledExactlyOnceWith('plan', ['artifacts'])
  fireEvent.click(screen.getByRole('button', { name: '続行' }))
  expect(await screen.findByRole('heading', { name: '実行結果' })).toBeVisible()
  expect(input).toHaveBeenCalledExactlyOnceWith('run', 'check', 'continue')

  fireEvent.click(screen.getByRole('button', { name: '実行履歴を見る' }))
  view.rerender(<DesktopToolWorkspace {...props} page="history" />)
  expect(screen.getByText('12 ネットワークビルド')).toBeVisible()
  expect(await screen.findByText('Lobby.jar を出力')).toBeVisible()
  expect(screen.getByRole('heading', { name: '実行時の計画' })).toBeVisible()
})
