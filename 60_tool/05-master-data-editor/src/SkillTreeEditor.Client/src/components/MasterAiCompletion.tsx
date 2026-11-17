import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { aiAssistApi } from '../api/aiAssistApi'
import { valueAtPointer } from '../data/masterDataNavigation'
import type { AiEditorSnapshot, AiPaneActions } from '../types/aiAssist'
import type { JsonValue } from '../types/editor'

// Identifiers, references and schema metadata must continue to use normal form controls.
export function aiCompletableValue(content: JsonValue, pointer: string): string | string[] | null {
  if (!pointer.startsWith('/') || /~(?![01])/.test(pointer)) return null
  const segments = pointer.split('/').slice(1).map(segment => segment.replaceAll('~1', '/').replaceAll('~0', '~'))
  if (segments.some(key => /^(?:\$.*|.*ids?|schemaVersion|version|.*path|.*url|.*uri|.*uuid|.*key|.*type|.*material|icon|iconTexture|iconModel|tags?|.*refs?|.*references?|from|to|parent|source|target)$/i.test(key))) return null
  const value = valueAtPointer(content, pointer)
  if (typeof value === 'string') return value
  return Array.isArray(value) && value.every(item => typeof item === 'string') ? value as string[] : null
}

interface Props {
  children: ReactNode
  value: JsonValue
  snapshot: AiEditorSnapshot | null
  actions: () => AiPaneActions | undefined
  active: boolean
  onApply: (pointer: string, value: string | string[], expected: AiEditorSnapshot) => void
  onConfigure: () => void
}

export function MasterAiCompletion({ children, value, snapshot, actions, active, onApply, onConfigure }: Props) {
  const [candidates, setCandidates] = useState<string[]>([])
  const [selected, setSelected] = useState(0)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [needsSettings, setNeedsSettings] = useState(false)
  const controller = useRef<AbortController | null>(null)
  const target = useRef<{ pointer: string; snapshot: AiEditorSnapshot; array: boolean } | null>(null)
  const token = snapshot?.token
  useEffect(() => {
    controller.current?.abort(); controller.current = null; target.current = null
    setCandidates([]); setBusy(false); setMessage(''); setNeedsSettings(false)
    return () => controller.current?.abort()
  }, [token, active])
  const dismiss = () => { controller.current?.abort(); controller.current = null; target.current = null; setCandidates([]); setBusy(false); setMessage('') }
  const adopt = (index: number) => {
    const field = target.current
    const candidate = candidates[index]
    if (!field || candidate === undefined || actions()?.snapshot()?.token !== field.snapshot.token) { dismiss(); return }
    try {
      const text = candidate.replace(/\r\n?/g, '\n')
      onApply(field.pointer, field.array ? text === '' ? [] : text.split('\n') : text, field.snapshot)
      dismiss()
    } catch (reason) { setMessage(reason instanceof Error ? reason.message : String(reason)); setCandidates([]) }
  }
  const generate = async (pointer: string, currentValue: string | string[]) => {
    const pane = actions()
    const expected = pane?.snapshot()
    if (!pane || !expected || expected.blocked) return
    dismiss()
    const request = new AbortController(); controller.current = request
    target.current = { pointer, snapshot: expected, array: Array.isArray(currentValue) }
    setBusy(true); setMessage(''); setNeedsSettings(false)
    try {
      const settings = await aiAssistApi.settings(request.signal)
      if (request.signal.aborted) return
      if (!settings.configured) { setNeedsSettings(true); setMessage('入力補完を使うには、AIアシストの接続設定でAPIキーを設定してください。'); return }
      const raw = await pane.raw()
      if (request.signal.aborted || pane.snapshot()?.token !== expected.token) return
      const result = await aiAssistApi.propose({ mode: 'complete', instruction: 'この文字列フィールドの自然な日本語の候補を提案してください。候補はフィールドの全文です。', sourcePath: expected.path, raw, fieldPointer: pointer }, request.signal)
      if (request.signal.aborted || pane.snapshot()?.token !== expected.token) return
      setCandidates(result.suggestions.slice(0, 3)); setSelected(0)
      setMessage(result.suggestions.length ? `${pointer} の候補 · Tabで採用 / ↑↓で選択 / Escで却下` : result.warnings.join(' ') || '候補がありません。入力を変えてCtrl+Spaceで再試行してください。')
    } catch (reason) { if (!request.signal.aborted) setMessage(reason instanceof Error ? reason.message : String(reason)) }
    finally { if (controller.current === request) { controller.current = null; setBusy(false) } }
  }
  const keyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!active || snapshot?.blocked || event.nativeEvent.isComposing || event.keyCode === 229 || event.repeat) return
    const element = event.target as HTMLElement
    if (element.tagName !== 'INPUT' && element.tagName !== 'TEXTAREA') return
    const input = element as HTMLInputElement | HTMLTextAreaElement
    if (input.disabled || input.readOnly || element.tagName === 'INPUT' && !['text', ''].includes((input as HTMLInputElement).type)) return
    const pointer = element.getAttribute('aria-label') ?? ''
    const fieldValue = aiCompletableValue(value, pointer)
    if (fieldValue === null) return
    if (event.key === ' ' && event.ctrlKey && !event.altKey && !event.metaKey && !event.shiftKey) {
      event.preventDefault(); event.stopPropagation(); void generate(pointer, fieldValue); return
    }
    if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey || target.current?.pointer !== pointer) return
    if (event.key === 'Escape' && (busy || candidates.length || message)) { event.preventDefault(); event.stopPropagation(); dismiss() }
    else if (event.key === 'Tab' && candidates.length) { event.preventDefault(); event.stopPropagation(); adopt(selected) }
    else if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && candidates.length) {
      event.preventDefault(); event.stopPropagation(); setSelected(index => (index + (event.key === 'ArrowDown' ? 1 : candidates.length - 1)) % candidates.length)
    }
  }
  return <div className="master-ai-completion-host" onKeyDownCapture={keyDown}>
    {children}
    {active && (busy || message || candidates.length > 0) && <aside className="master-ai-completion" aria-label="AI入力補完">
      <div className="button-row"><strong>AI入力補完</strong><button type="button" className="button compact" onClick={dismiss}>{busy ? '生成をキャンセル' : '候補を閉じる'}</button></div>
      <p role="status">{busy ? '選択中の原稿をOpenAIへ送信して候補を生成しています…' : message}</p>
      {needsSettings && <button type="button" className="button" onClick={() => { dismiss(); onConfigure() }}>接続設定を開く</button>}
      {candidates.length > 0 && <div role="listbox" aria-label="AI補完候補">{candidates.map((candidate, index) => <button type="button" role="option" aria-selected={index === selected} className={`master-ai-completion-option ${index === selected ? 'selected' : ''}`} key={index} onMouseDown={event => event.preventDefault()} onClick={() => adopt(index)}><span>{index + 1}</span><pre>{candidate}</pre><small>採用</small></button>)}</div>}
    </aside>}
  </div>
}
