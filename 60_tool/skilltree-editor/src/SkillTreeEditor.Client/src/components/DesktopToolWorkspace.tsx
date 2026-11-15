import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { isRunning, toolJobApi, type ToolCapabilities, type ToolId, type ToolPage, type ToolPlan, type ToolRun, type ToolSettings } from '../api/toolJobApi'
import { toolIds, toolSpecs } from '../data/desktopTools'
import { displayToolDate, RunStatus, ToolPlanDetails, ToolRunDetails, toolError } from './ToolRunDetails'
import { defaultToolSettings, ToolSettingsFields } from './ToolSettingsFields'
import './desktopTools.css'

function useRunHistory(enabled: boolean, visible: boolean) {
  const [runs, setRuns] = useState<ToolRun[]>()
  const [error, setError] = useState('')
  const [refreshKey, setRefreshKey] = useState(0)
  const refresh = () => setRefreshKey(value => value + 1)

  useEffect(() => {
    if (!enabled) return
    let live = true
    let retry: ReturnType<typeof setTimeout> | undefined
    const update = async () => {
      try {
        const result = await toolJobApi.runs()
        if (!live) return
        setRuns(result)
        setError('')
        if (result.some(isRunning)) retry = setTimeout(() => void update(), 1100)
      } catch (reason) {
        if (!live) return
        setError(toolError(reason))
        retry = setTimeout(() => void update(), 2500)
      }
    }
    void update()
    return () => { live = false; clearTimeout(retry) }
  }, [enabled, refreshKey, visible])

  return { runs, error, refresh, onStarted: (run: ToolRun) => {
    setRuns(current => [run, ...(current ?? []).filter(entry => entry.id !== run.id)])
    refresh()
  } }
}

interface DesktopToolWorkspaceProps {
  page: ToolPage
  visible: boolean
  capabilities?: ToolCapabilities
  capabilitiesError?: string
  onRetryCapabilities: () => void
  hasUnsavedSource: boolean
  onOpenHistory: () => void
}

export function DesktopToolWorkspace({ page, visible, capabilities, capabilitiesError, onRetryCapabilities, hasUnsavedSource, onOpenHistory }: DesktopToolWorkspaceProps) {
  const history = useRunHistory(capabilities?.enabled === true, visible)
  const [selectedHistory, setSelectedHistory] = useState('')
  const workspace = useRef<HTMLElement>(null)
  const historyDetails = useRef<HTMLDivElement>(null)
  const [visitedPages, setVisitedPages] = useState<ToolId[]>(page === 'history' ? [] : [page])
  useEffect(() => {
    if (page !== 'history') setVisitedPages(current => current.includes(page) ? current : [...current, page])
  }, [page])
  useLayoutEffect(() => {
    if (visible && workspace.current) workspace.current.scrollTop = 0
  }, [page, visible])
  useEffect(() => {
    if (visible && page === 'history' && selectedHistory) historyDetails.current?.scrollIntoView?.({ block: 'start' })
  }, [page, selectedHistory, visible])
  const showHistory = (id: string) => { setSelectedHistory(id); onOpenHistory() }

  if (!capabilities?.enabled) return <main ref={workspace} className="tool-workspace">
    <h1>{page === 'history' ? '実行履歴' : toolSpecs[page].label}</h1>
    {capabilitiesError ? <div className="tool-note error" role="alert"><p>{capabilitiesError}</p><button type="button" className="button" onClick={onRetryCapabilities}>再取得</button></div>
      : <p className="tool-note">{capabilities ? capabilities.reason || '実行ツールはデスクトップアプリで利用できます。' : '実行ツールの利用可否を確認しています…'}</p>}
  </main>

  const runs = history.runs ?? []
  const activeRun = runs.find(isRunning)
  const selected = runs.find(run => run.id === selectedHistory)
  return <main ref={workspace} className="tool-workspace">
    {history.error && <div className="tool-note error" role="alert"><p>実行状況を取得できませんでした。{history.error}</p><button type="button" className="button" onClick={history.refresh}>再取得</button></div>}
    {toolIds.filter(id => id === page || visitedPages.includes(id)).map(id => <div key={id} hidden={page !== id}>
      <ToolOperation id={id} ready={Boolean(history.runs) && !history.error} hasUnsavedSource={hasUnsavedSource}
        latestRun={runs.find(run => run.toolId === id)} activeRun={activeRun} visible={visible && page === id}
        onStarted={history.onStarted} onRefresh={history.refresh} onOpenHistory={showHistory} />
    </div>)}
    {page === 'history' && <>
      <div className="tool-heading"><div><h1>実行履歴</h1><p className="tool-muted">このワークスペースで実行した処理とログを確認します。</p></div><button type="button" className="button" onClick={history.refresh}>更新</button></div>
      <section className="tool-panel">
        {runs.length ? <div className="tool-table-wrap"><table className="tool-table">
          <thead><tr><th scope="col">開始日時</th><th scope="col">処理</th><th scope="col">結果</th><th scope="col">詳細</th></tr></thead>
          <tbody>{runs.map(run => <tr key={run.id}>
            <td><time dateTime={run.startedAtUtc}>{displayToolDate(run.startedAtUtc)}</time></td>
            <td>{run.toolId} {toolSpecs[run.toolId].label}</td>
            <td><RunStatus status={run.status} /></td>
            <td><button type="button" className="text-button" aria-expanded={selected?.id === run.id}
              onClick={() => setSelectedHistory(current => current === run.id ? '' : run.id)}>{selected?.id === run.id ? '詳細を閉じる' : '詳細を表示'}</button></td>
          </tr>)}</tbody>
        </table></div> : <p className="tool-empty">{history.runs ? 'まだ実行履歴がありません。' : '実行履歴を読み込んでいます…'}</p>}
      </section>
      {selected && visible && <div ref={historyDetails} className="tool-history-details">
        <ToolRunDetails key={selected.id} run={selected} onRefresh={history.refresh} />
        <ToolPlanDetails heading="実行時の計画" plan={selected.plan} />
      </div>}
    </>}
  </main>
}

function ToolOperation({ id, ready, hasUnsavedSource, latestRun, activeRun, visible, onStarted, onRefresh, onOpenHistory }: {
  id: ToolId; ready: boolean; hasUnsavedSource: boolean; latestRun?: ToolRun; activeRun?: ToolRun; visible: boolean
  onStarted: (run: ToolRun) => void; onRefresh: () => void; onOpenHistory: (id: string) => void
}) {
  const [settings, setSettings] = useState<ToolSettings>(defaultToolSettings[id])
  const [plan, setPlan] = useState<ToolPlan>()
  const [checked, setChecked] = useState<string[]>([])
  const [reviewed, setReviewed] = useState(false)
  const [pending, setPending] = useState<'plan' | 'start'>()
  const [error, setError] = useState('')
  const [expiredId, setExpiredId] = useState('')
  const spec = toolSpecs[id]
  const expired = plan && (plan.id === expiredId || Date.parse(plan.expiresAtUtc) <= Date.now())
  const runActive = activeRun?.toolId === id
  const disabled = !ready || Boolean(activeRun) || Boolean(pending)
  const missingTests = settings.toolId === '01' && settings.pluginTestMode === 'Selected' && !settings.pluginTests?.trim()

  useEffect(() => {
    if (!plan) return
    const timeout = setTimeout(() => setExpiredId(plan.id), Math.max(0, Date.parse(plan.expiresAtUtc) - Date.now()))
    return () => clearTimeout(timeout)
  }, [plan])

  const clearPlan = () => {
    setPlan(undefined)
    setChecked([])
    setReviewed(false)
  }
  const updateSettings = (next: ToolSettings) => {
    setSettings(next)
    clearPlan()
    setError('')
  }
  const requestPlan = async () => {
    clearPlan()
    setPending('plan')
    setError('')
    try {
      const request = settings.toolId === '01'
        ? { ...settings, pluginTests: settings.pluginTestMode === 'Selected' ? settings.pluginTests?.trim() : undefined }
        : settings
      setPlan(await toolJobApi.plan(request))
    } catch (reason) {
      setError(toolError(reason))
    } finally {
      setPending(undefined)
    }
  }

  const confirmed = plan && plan.canRun !== false && (plan.confirmations.length ? plan.confirmations.every(item => checked.includes(item.id)) : reviewed)
  const start = async () => {
    if (!plan || expired || !confirmed || disabled) return
    setPending('start')
    setError('')
    try {
      onStarted(await toolJobApi.start(plan.id, checked))
    } catch (reason) {
      setError(toolError(reason))
      onRefresh()
    } finally {
      clearPlan()
      setPending(undefined)
    }
  }

  return <>
    <div className="tool-heading">
      <div><h1>{spec.label}</h1><p className="tool-muted">{spec.description}</p></div>
      {runActive ? <RunStatus status={activeRun.status} /> : plan && !expired && <span className="tool-badge">{plan.canRun === false ? '実行条件を確認' : '計画を確認'}</span>}
    </div>
    <div className="tool-settings-grid">
      <section className="tool-panel">
        <h2>実行設定</h2>
        <fieldset className="tool-settings" disabled={disabled} aria-label="実行設定"><ToolSettingsFields value={settings} onChange={updateSettings} /></fieldset>
      </section>
      <section className="tool-panel">
        <h2>処理の流れ</h2>
        <ol className="tool-steps">{spec.steps.map(step => <li key={step}>{step}</li>)}</ol>
      </section>
    </div>
    {plan && <ToolPlanDetails plan={plan} />}
    {hasUnsavedSource && (id === '03' || id === '01') && <p className="tool-note warning">未保存のマスタ編集があります。実行には保存済みのファイルを使用します。</p>}
    {activeRun && !runActive && <div className="tool-note"><p>{toolSpecs[activeRun.toolId].label}が実行中です。</p><button type="button" className="button" onClick={() => onOpenHistory(activeRun.id)}>実行中の処理を表示</button></div>}
    {plan && plan.canRun !== false && !expired && <fieldset className="tool-confirmations" disabled={disabled}>
      <legend>実行前の確認</legend>
      {plan.confirmations.length ? plan.confirmations.map(confirmation => <label key={confirmation.id}>
        <input type="checkbox" checked={checked.includes(confirmation.id)} onChange={event => {
          const selected = event.target.checked
          setChecked(current => selected ? [...current, confirmation.id] : current.filter(item => item !== confirmation.id))
        }} /><span>{confirmation.label}</span>
      </label>) : <label><input type="checkbox" checked={reviewed} onChange={event => setReviewed(event.target.checked)} /><span>実行計画を確認しました</span></label>}
    </fieldset>}
    {plan?.canRun === false && <p className="tool-note warning" role="status">この計画は実行条件を満たしていません。</p>}
    {expired && <p className="tool-note warning" role="status">計画の有効期限が切れました。設定を確認して計画を更新してください。</p>}
    {error && <p className="tool-note error" role="alert">{error}</p>}
    <div className="tool-actions">
      <span className="tool-muted">{plan && !expired && `有効期限 ${displayToolDate(plan.expiresAtUtc)}`}</span>
      <div className="tool-button-row">
        <button type="button" className="button" disabled={disabled || missingTests} onClick={() => void requestPlan()}>
          {pending === 'plan' ? '計画を取得中…' : plan ? '計画を更新' : '実行計画を確認'}
        </button>
        <button type="button" className="button primary" disabled={disabled || !confirmed || expired} onClick={() => void start()}>
          {pending === 'start' ? '実行を開始中…' : spec.action}
        </button>
      </div>
    </div>
    {visible && latestRun && <ToolRunDetails key={latestRun.id} run={latestRun} onRefresh={onRefresh} onOpenHistory={() => onOpenHistory(latestRun.id)} />}
  </>
}
