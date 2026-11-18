import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { usePanelDocument } from '../docking/dockPanel'
import { aiAssistApi } from '../api/aiAssistApi'
import { masterLineDiff } from '../data/masterLineDiff'
import type { AiBatchGuard, AiEditorSnapshot, AiMode, AiPaneActions, AiProposal, AiProposalResponse, AiSettings } from '../types/aiAssist'
import type { MasterCategory, MasterFileSummary } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { Modal } from './Modal'
import { MasterAiSettings } from './MasterAiSettings'

interface Props {
  categories: MasterCategory[]
  files: MasterFileSummary[]
  categoryId: string
  current: () => AiPaneActions | undefined
  source: (path: string) => AiPaneActions | undefined
  captureBatch: (paths: string[]) => AiBatchGuard
  applyBatch: (proposal: AiProposal, guard: AiBatchGuard) => Promise<void>
  predictWhileTyping: boolean
  onPredictionChange: (enabled: boolean) => void
  onClose: () => void
}

function ProposalDiff({ proposal }: { proposal: AiProposal }) {
  const comparison = useMemo(() => masterLineDiff(proposal.beforeRaw ?? '', proposal.raw), [proposal])
  return <div className="master-ai-diff" aria-label="AI提案の差分">
    <p className="master-muted">{proposal.isNew ? '空の原稿 → 新規候補' : '生成時の保存済み原稿 → AI候補'}</p>
    {comparison.simplified && <p>原稿が大きいため差分を一部まとめています。</p>}
    {comparison.lines.map((line, index) => <div key={index} className={`master-ai-diff-line ${line.kind}`}><span aria-hidden="true">{line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' '}</span><pre>{line.text || ' '}</pre></div>)}
  </div>
}

export function MasterAiAssist({ categories, files, categoryId: initialCategory, current, source, captureBatch, applyBatch, predictWhileTyping, onPredictionChange, onClose }: Props) {
  const ownerDocument = usePanelDocument()
  const currentPath = current()?.snapshot()?.path ?? ''
  const initialSource = files.find(file => file.path === currentPath && !file.readOnly && !file.path.includes('/nodes/'))?.path ?? ''
  const categoryForPath = (path: string) => categories.filter(category => path.startsWith(`${category.directory}/`)).sort((a, b) => b.directory.length - a.directory.length)[0]?.id
  const [mode, setMode] = useState<AiMode>('complete')
  const [categoryId, setCategoryId] = useState(categoryForPath(initialSource) || initialCategory || categories[0]?.id || '')
  const [sourcePath, setSourcePath] = useState(initialSource)
  const [instruction, setInstruction] = useState('')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [paths, setPaths] = useState<string[]>([])
  const [settings, setSettings] = useState<AiSettings | null>(null)
  const [response, setResponse] = useState<AiProposalResponse | null>(null)
  const [reviewed, setReviewed] = useState<number[]>([])
  const [selected, setSelected] = useState<number[]>([])
  const [adopted, setAdopted] = useState<number[]>([])
  const [busy, setBusy] = useState(false)
  const [applying, setApplying] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const controller = useRef<AbortController | null>(null)
  const guards = useRef<AiBatchGuard>({})
  const sourceGuard = useRef<AiEditorSnapshot | null>(null)
  const sourcePane = useRef<AiPaneActions | undefined>(undefined)
  useEffect(() => () => controller.current?.abort(), [])
  const eligibleFiles = useMemo(() => files.filter(file => !file.readOnly && !file.path.includes('/nodes/')), [files])
  const matches = useMemo(() => {
    const terms = query.toLocaleLowerCase().trim().split(/\s+/).filter(Boolean)
    return eligibleFiles.filter(file => terms.every(term => `${file.path} ${stripMinecraftFormatting(file.name ?? '')} ${file.id ?? ''}`.toLocaleLowerCase().includes(term)))
  }, [eligibleFiles, query])
  const pageCount = Math.max(1, Math.ceil(matches.length / 50))
  const visiblePage = Math.min(page, pageCount - 1)
  const cancel = () => { controller.current?.abort(); controller.current = null; setBusy(false); setNotice('生成をキャンセルしました。') }
  const switchMode = (next: AiMode) => { cancel(); setMode(next); setResponse(null); setReviewed([]); setSelected([]); setAdopted([]); setError(''); setNotice('') }
  const generate = async () => {
    if (busy || applying || !settings?.configured || !instruction.trim()) return
    const request = new AbortController(); controller.current = request
    setBusy(true); setResponse(null); setReviewed([]); setSelected([]); setAdopted([]); setError(''); setNotice('')
    try {
      let raw: string | undefined
      if (mode === 'batch') {
        if (!paths.length || paths.length > 10) throw new Error('一括編集する保存済みファイルを1〜10件選択してください。')
        guards.current = captureBatch(paths)
      } else if (sourcePath) {
        const pane = source(sourcePath)
        const snapshot = pane?.snapshot() ?? null
        sourcePane.current = snapshot?.path === sourcePath ? pane : undefined
        sourceGuard.current = snapshot?.path === sourcePath ? snapshot : null
        if (sourceGuard.current?.blocked) throw new Error('参考ファイルの読込・入力・復元を完了してから生成してください。')
        if (sourcePane.current) raw = await sourcePane.current.raw()
        if (sourceGuard.current && sourcePane.current?.snapshot()?.token !== sourceGuard.current.token) throw new Error('参考原稿が変更されました。再生成してください。')
      } else { sourcePane.current = undefined; sourceGuard.current = null }
      if (request.signal.aborted) return
      const result = await aiAssistApi.propose({ mode, instruction: instruction.trim(), ...(mode === 'batch' ? { paths } : { categoryId, ...(sourcePath ? { sourcePath } : {}), ...(raw === undefined ? {} : { raw }) }) }, request.signal)
      if (request.signal.aborted) return
      const proposals = mode === 'create' ? result.proposals.slice(0, 3) : result.proposals
      setResponse({ ...result, proposals })
      setNotice(proposals.length ? '差分を開いて確認し、採用する候補を選んでください。保存は各編集画面で行います。' : '候補がありません。指示を変えて再試行してください。')
    } catch (reason) { if (!request.signal.aborted) setError(reason instanceof Error ? reason.message : String(reason)) }
    finally { if (controller.current === request) { controller.current = null; setBusy(false) } }
  }
  const adopt = async (indices: number[]) => {
    if (!response || applying || busy) return
    setApplying(true); setError(''); setNotice('')
    let completed = 0
    try {
      for (const index of indices) {
        const proposal = response.proposals[index]
        if (!proposal || adopted.includes(index) || !reviewed.includes(index) || proposal.issues.some(issue => issue.severity === 'error')) continue
        if (mode === 'create') {
          if (sourceGuard.current && sourcePane.current?.snapshot()?.token !== sourceGuard.current.token) throw new Error('参考原稿が生成中に変更されました。再生成してください。')
          const pane = current()
          if (!pane) throw new Error('編集画面を開き直してください。')
          const targetCategory = categories.find(category => category.id === categoryForPath(proposal.path))
          const selectedCategory = categories.find(category => category.id === categoryId)
          if (!targetCategory || !selectedCategory || (targetCategory.id !== selectedCategory.id
            && !targetCategory.directory.startsWith(`${selectedCategory.directory}/`)))
            throw new Error('提案の作成先が選択カテゴリ内にありません。再生成してください。')
          pane.create(proposal.raw, targetCategory.id)
          onClose()
          return
        }
        if (!paths.includes(proposal.path) || proposal.isNew) throw new Error('選択したファイルと一致しない候補は適用できません。再生成してください。')
        await applyBatch(proposal, guards.current)
        setAdopted(value => [...value, index]); completed++
      }
      setNotice(`${completed}件を編集タブの下書きへ適用しました。各タブで検証・保存してください。Undoで元に戻せます。`)
    } catch (reason) { setError(`${completed ? `${completed}件は下書きへ適用済みです。` : ''}${reason instanceof Error ? reason.message : String(reason)}`) }
    finally { setApplying(false) }
  }
  const close = () => { if (applying) return; controller.current?.abort(); onClose() }
  return createPortal(<Modal ownerDocument={ownerDocument} className="master-ai-dialog" ariaLabel="AIアシスト" onClose={close} closeDisabled={applying}>
    <header className="modal-header"><div><h2>AIアシスト</h2><p className="master-muted">選択したマスターデータと指示をOpenAIへ送信します。候補の採用後も保存操作が必要です。</p></div><button type="button" className="button" disabled={applying} onClick={close}>閉じる</button></header>
    <div className="modal-body master-ai-body">
      <MasterAiSettings onSettings={setSettings} />
      <div className="button-row" role="group" aria-label="AIモード">{([['complete', '入力補完'], ['create', '作成'], ['batch', '一括編集']] as const).map(([id, label]) => <button type="button" className={`button ${mode === id ? 'active' : ''}`} aria-pressed={mode === id} disabled={busy || applying} key={id} onClick={() => switchMode(id)}>{label}</button>)}</div>
      {mode === 'complete' ? <div className="master-ai-help">
        <label className="master-checkbox"><input type="checkbox" checked={predictWhileTyping} onChange={event => onPredictionChange(event.target.checked)} />入力中にAI予測変換を表示</label>
        <p>日本語フォームの名前や説明文の末尾で入力を止めると、その欄のテキストをOpenAIへ送り、続きを提案します。変換中は待機し、候補はTabで採用、Escで閉じます。設定は起動中だけ有効です。</p>
        <p>欄全体の言い換え候補を見たいときは Ctrl+Space を押します。</p>
      </div> : <>
        <fieldset className="master-form-lock master-ai-inputs" disabled={busy || applying}>
          {mode === 'create' ? <>
            <label>作成カテゴリ<select aria-label="AI作成カテゴリ" value={categoryId} onChange={event => { setCategoryId(event.target.value); setSourcePath(''); setResponse(null) }}>{categories.map(category => <option key={category.id} value={category.id}>{category.label}</option>)}</select></label>
            <label>参考にする既存マスター<select aria-label="AI参考ファイル" value={sourcePath} onChange={event => { const path = event.target.value; setSourcePath(path); if (path) setCategoryId(categoryForPath(path) ?? categoryId); setResponse(null) }}><option value="">参考なし</option>{eligibleFiles.filter(file => file.path === sourcePath || file.category === categoryId).map(file => <option key={file.path} value={file.path}>{stripMinecraftFormatting(file.name ?? '')} · {file.path}</option>)}</select></label>
            <p className="master-muted">1〜3案を生成します。参考ファイルを現在編集している場合、未保存の有効な原稿も使用します。採用後に通常の新規作成でslug・自動採番を確認します。</p>
            {categories.find(category => category.id === categoryId)?.directory === '10.features.item' && <p className="master-muted">「アイテム共通」では、指示に合う素材・装備などの子カテゴリを作成先として提案します。</p>}
          </> : <>
            <label>一括編集するファイルを検索<input aria-label="AI対象検索" value={query} onChange={event => { setQuery(event.target.value); setPage(0) }} placeholder="名前 / ID / パス" /></label>
            <p>{paths.length} / 10件を選択 · 検索結果 {matches.length}件。未保存・読込中・復元待ちのファイルは対象にできません。</p>
            {paths.length > 0 && <div className="master-ai-selected">{paths.map(path => <button key={path} type="button" className="button compact" aria-label={`${path} を対象から除外`} onClick={() => { setPaths(value => value.filter(entry => entry !== path)); setResponse(null) }}>{path} ×</button>)}</div>}
            <div className="master-ai-files" role="group" aria-label="AI一括編集対象">{matches.slice(visiblePage * 50, (visiblePage + 1) * 50).map(file => <label key={file.path} className="master-checkbox"><input type="checkbox" checked={paths.includes(file.path)} disabled={Boolean(file.parseError) || paths.length >= 10 && !paths.includes(file.path)} onChange={event => { setPaths(value => event.target.checked ? [...value, file.path] : value.filter(path => path !== file.path)); setResponse(null) }} /><span>{stripMinecraftFormatting(file.name ?? file.id ?? '')}<small>{file.path}{file.parseError ? ' · 解析エラー' : ''}</small></span></label>)}</div>
            <div className="master-pagination"><button type="button" className="button compact" disabled={visiblePage === 0} onClick={() => setPage(visiblePage - 1)}>前の対象</button><span>{visiblePage + 1} / {pageCount}</span><button type="button" className="button compact" disabled={visiblePage + 1 >= pageCount} onClick={() => setPage(visiblePage + 1)}>次の対象</button></div>
          </>}
          <label>AIへの指示<textarea aria-label="AIへの指示" rows={4} value={instruction} onChange={event => { setInstruction(event.target.value); setResponse(null) }} placeholder={mode === 'create' ? '例: 低レベル向けの星をテーマにした剣を3案。説明文は2行。' : '例: 選択した装備の説明文を、性能を変えずに自然な日本語へ整える。'} /></label>
        </fieldset>
        <div className="button-row"><button type="button" className="button primary" disabled={busy || applying || !settings?.configured || !instruction.trim() || mode === 'batch' && !paths.length || mode === 'create' && !categoryId} onClick={() => void generate()}>{busy ? 'AI候補を生成中…' : response ? '再生成' : '候補を生成'}</button>{busy && <button type="button" className="button" onClick={cancel}>生成をキャンセル</button>}</div>
      </>}
      {busy && <p role="status">AI候補を生成しています。原稿はまだ変更していません。</p>}
      {error && <p role="alert" className="error-message">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      {response && <section aria-label="AI生成候補" className="master-ai-proposals"><p className="master-muted">使用モデル: {response.model}</p>{response.warnings.map((warning, index) => <p className="master-message warning" key={index}>{warning}</p>)}{response.proposals.map((proposal, index) => <article className="master-ai-proposal" key={`${index}:${proposal.path}`}>
        <h3>{mode === 'create' ? `作成案 ${index + 1}` : proposal.path}</h3><p>{proposal.summary}</p>
        {mode === 'create' && <p className="master-muted">作成先: {categories.find(category => category.id === categoryForPath(proposal.path))?.label ?? proposal.path}</p>}
        {proposal.issues.length > 0 && <ul>{proposal.issues.map((issue, issueIndex) => <li key={issueIndex} className={issue.severity === 'error' ? 'error-message' : 'master-muted'}>{issue.path} · {issue.message}</li>)}</ul>}
        <details onToggle={event => { if (event.currentTarget.open) setReviewed(value => value.includes(index) ? value : [...value, index]) }}><summary>原稿と差分を確認</summary><ProposalDiff proposal={proposal} /></details>
        {adopted.includes(index) ? <p role="status">下書きへ適用済み</p> : mode === 'create' ? <button type="button" className="button" disabled={!reviewed.includes(index) || applying || proposal.issues.some(issue => issue.severity === 'error')} onClick={() => void adopt([index])}>この案を原稿から新規作成へ</button> : <label className="master-checkbox"><input type="checkbox" checked={selected.includes(index)} disabled={!reviewed.includes(index) || applying || proposal.issues.some(issue => issue.severity === 'error')} onChange={event => setSelected(value => event.target.checked ? [...value, index] : value.filter(entry => entry !== index))} />この候補を採用する</label>}
      </article>)}</section>}
    </div>
    {mode === 'batch' && response && <footer className="modal-footer"><button type="button" className="button primary" disabled={applying || !selected.some(index => !adopted.includes(index))} onClick={() => void adopt(selected)}>{applying ? '採用中…' : '選択した候補を下書きへ適用'}</button></footer>}
  </Modal>, ownerDocument.body)
}
