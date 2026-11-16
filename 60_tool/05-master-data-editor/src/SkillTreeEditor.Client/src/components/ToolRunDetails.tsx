import { useEffect, useRef, useState } from 'react'
import { isRunning, toolJobApi, type ToolLogLine, type ToolPlan, type ToolRun, type ToolRunStatus } from '../api/toolJobApi'
import { toolSpecs } from '../data/desktopTools'

const statusLabels: Record<ToolRunStatus, string> = {
  starting: '起動中',
  running: '実行中',
  waitingForInput: '操作待ち',
  succeeded: '完了',
  failed: '失敗',
  interrupted: '中断',
}
const dateFormat = new Intl.DateTimeFormat('ja-JP', { dateStyle: 'short', timeStyle: 'medium' })
export const displayToolDate = (instant: string) => dateFormat.format(new Date(instant))
export const toolError = (reason: unknown) => reason instanceof Error ? reason.message : String(reason)

export function RunStatus({ status }: { status: ToolRunStatus }) {
  return <span className={`tool-badge tool-status-${status}`}>{statusLabels[status]}</span>
}

export function ToolPlanDetails({ plan, heading = '計画の詳細' }: { plan: ToolPlan; heading?: string }) {
  return <section className="tool-panel">
    <h2>{heading}</h2>
    <dl className="tool-plan-settings">
      <dt>作業ルート</dt><dd><code>{plan.workspaceRoot}</code></dd>
      {plan.configurationPath && <><dt>使用する設定</dt><dd><code>{plan.configurationPath}</code></dd></>}
      {plan.filebaseSource && <><dt>Filebase 同期元</dt><dd><code>{plan.filebaseSource}</code></dd></>}
    </dl>
    {plan.paths.length > 0 && <div className="tool-table-wrap"><table className="tool-table">
      <thead><tr><th scope="col">対象</th><th scope="col">入力元</th><th scope="col">出力先・配布先</th></tr></thead>
      <tbody>{plan.paths.map((path, index) => <tr key={index}>
        <th scope="row">{path.label}</th><td><code>{path.source}</code></td><td><code>{path.destination}</code></td>
      </tr>)}</tbody>
    </table></div>}
    {plan.summary.length > 0 && <ul className="tool-plan-summary">{plan.summary.map((line, index) => <li key={index}>{line}</li>)}</ul>}
    {plan.workspaceMismatch && <p className="tool-note warning">編集ワークスペースと同期元が異なります。</p>}
    {plan.warnings.length > 0 && <ul className="tool-note warning">{plan.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul>}
  </section>
}

function groupLogLines(lines: ToolLogLine[]) {
  const groups: ToolLogLine[] = []
  for (const line of lines) {
    const last = groups.at(-1)
    const text = (line.text || ' ') + '\n'
    if (last?.stream === line.stream) last.text += text
    else groups.push({ ...line, text })
  }
  return groups
}

function RunLog({ run }: { run: ToolRun }) {
  const [groups, setGroups] = useState<ToolLogLine[]>([])
  const [error, setError] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [retry, setRetry] = useState(0)
  const cursor = useRef(0)
  const scrollBox = useRef<HTMLPreElement>(null)
  const follow = useRef(true)
  const running = isRunning(run)

  useEffect(() => {
    const controller = new AbortController()
    let timeout: ReturnType<typeof setTimeout> | undefined
    const load = async () => {
      try {
        const before = cursor.current
        const batch = await toolJobApi.logs(run.id, before, controller.signal)
        if (controller.signal.aborted) return
        cursor.current = batch.next
        setLoaded(true)
        setError('')
        if (batch.lines.length) {
          const next = groupLogLines(batch.lines)
          setGroups(current => [...current, ...next])
        }
        if (batch.next > before || running) timeout = setTimeout(() => void load(), batch.next > before ? 10 : 900)
      } catch (reason) {
        if (controller.signal.aborted) return
        setError(toolError(reason))
        if (running) timeout = setTimeout(() => void load(), 1500)
      }
    }
    void load()
    return () => { controller.abort(); clearTimeout(timeout) }
  }, [run.id, running, retry])

  useEffect(() => {
    if (follow.current && scrollBox.current) scrollBox.current.scrollTop = scrollBox.current.scrollHeight
  }, [groups])

  return <>
    {error && <div className="tool-note error" role="alert"><p>{error}</p><button type="button" className="button compact" onClick={() => setRetry(value => value + 1)}>ログを再取得</button></div>}
    {groups.length > 0
      ? <pre className="tool-log" aria-label="処理の出力" tabIndex={0} ref={scrollBox} onScroll={event => {
        const log = event.currentTarget
        follow.current = log.scrollHeight - log.scrollTop - log.clientHeight < 24
      }}>{groups.map(group => <span key={group.sequence} className={group.stream === 'error' ? 'tool-log-stderr' : undefined}>{group.text}</span>)}</pre>
      : <p className="tool-muted">{loaded ? '出力されたログはありません。' : 'ログを読み込んでいます…'}</p>}
  </>
}

export function ToolRunDetails({ run, onRefresh, onOpenHistory }: {
  run: ToolRun; onRefresh: () => void; onOpenHistory?: () => void
}) {
  const [promptSending, setPromptSending] = useState(false)
  const [answeredPrompt, setAnsweredPrompt] = useState('')
  const [error, setError] = useState('')
  const answer = async (promptId: string, value: string) => {
    setPromptSending(true)
    setError('')
    try {
      await toolJobApi.input(run.id, promptId, value)
      setAnsweredPrompt(promptId)
    } catch (reason) {
      setError(toolError(reason))
    } finally {
      setPromptSending(false)
      onRefresh()
    }
  }
  const running = isRunning(run)
  return <section className="tool-panel tool-run-details">
    <div className="tool-heading">
      <div><h2>{running ? '実行ログ' : '実行結果'}</h2><p className="tool-muted">{toolSpecs[run.toolId].label} · {displayToolDate(run.startedAtUtc)}</p></div>
      <div role="status"><RunStatus status={run.status} /></div>
    </div>
    {onOpenHistory && run.toolId !== '16' && run.plan.warnings.length > 0 && <ul className="tool-note warning">
      {run.plan.warnings.map((warning, index) => <li key={index}>{warning}</li>)}
    </ul>}
    {run.prompt && run.status === 'waitingForInput' && <section className="tool-note warning">
      <h3>操作が必要です</h3><p>{run.prompt.message}</p>
      <div className="tool-button-row">{run.prompt.choices.map(choice => <button key={choice.value} type="button" className="button"
        disabled={promptSending || answeredPrompt === run.prompt?.id} onClick={() => { if (run.prompt) void answer(run.prompt.id, choice.value) }}>{choice.label}</button>)}</div>
    </section>}
    {error && <p className="tool-note error" role="alert">{error}</p>}
    {run.status === 'running' && <progress className="tool-progress" aria-label="現在実行中" />}
    <RunLog run={run} />
    {run.logPath && <p className="tool-muted">ログ保存先: <code>{run.logPath}</code></p>}
    <div className="tool-actions">
      <span className="tool-muted">{run.finishedAtUtc && `終了 ${displayToolDate(run.finishedAtUtc)}`}{run.exitCode !== null && ` · 終了コード ${run.exitCode}`}</span>
      {onOpenHistory && <button className="text-button" type="button" onClick={onOpenHistory}>実行履歴を見る</button>}
    </div>
  </section>
}
