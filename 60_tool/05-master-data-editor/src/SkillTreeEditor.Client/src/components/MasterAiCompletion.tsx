import { useCallback, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { aiAssistApi } from '../api/aiAssistApi'
import { valueAtPointer } from '../data/masterDataNavigation'
import type { MasterPredictionSource, SearchLocalPredictions } from '../state/useMasterPredictions'
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
  predictWhileTyping?: boolean
  predictionSource?: MasterPredictionSource
  onApply: (pointer: string, value: string | string[], expected: AiEditorSnapshot) => void
  onUndoSuggestion?: () => void
  onConfigure: () => void
}

type TextControl = HTMLInputElement | HTMLTextAreaElement
type Prediction = { input: TextControl; pointer: string; text: string; clickCaret?: number; token: string | undefined; timer: ReturnType<typeof setTimeout> | null }
type Completion = { input: TextControl; pointer: string; snapshot: AiEditorSnapshot; array: boolean; prefix?: string; clickCaret?: number; search?: SearchLocalPredictions }
type AcceptedSuggestion = { input: TextControl; pointer: string; text: string; previousToken: string; token?: string }

const predictionDelay = 800
const proseFields = /^(?:name|displayName|title|label|description|lore|summary|text|message)$/i
const shortcuts = 'Tabで採用 / ↑↓で選択 / Escで却下'

function prosePointer(pointer: string) {
  return proseFields.test(pointer.split('/').filter(segment => !/^\d+$/.test(segment)).at(-1) ?? '')
}

function textControl(target: EventTarget): TextControl | null {
  const input = target as TextControl
  if (input.tagName !== 'TEXTAREA' && (input.tagName !== 'INPUT' || (input as HTMLInputElement).type !== 'text')) return null
  return input.matches(':disabled') || input.readOnly ? null : input
}

function canContinue(input: TextControl, text: string, caret = text.length) {
  return input.isConnected && input.ownerDocument.activeElement === input && !input.ownerDocument.hidden &&
    input.value === text && input.selectionStart === caret && input.selectionEnd === caret &&
    !input.matches(':disabled') && !input.readOnly
}

function fieldText(value: string | string[] | null) {
  return Array.isArray(value) ? value.join('\n') : value
}

export function MasterAiCompletion({ children, value, snapshot, actions, active, predictWhileTyping = false, predictionSource, onApply, onUndoSuggestion, onConfigure }: Props) {
  const [candidates, setCandidates] = useState<string[]>([])
  const [selected, setSelected] = useState(0)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [needsSettings, setNeedsSettings] = useState(false)
  const host = useRef<HTMLDivElement>(null)
  const popover = useRef<HTMLElement>(null)
  const composing = useRef<{ input: TextControl; original: string } | null>(null)
  const focusedText = useRef<{ input: TextControl; path: string; pointer: string; original: string } | null>(null)
  const pending = useRef<Prediction | null>(null)
  const accepted = useRef<AcceptedSuggestion | null>(null)
  const controller = useRef<AbortController | null>(null)
  const [target, setTarget] = useState<Completion | null>(null)
  const currentTarget = useRef<Completion | null>(null)
  const search = predictionSource?.search
  const latest = useRef({ value, actions, active, predictWhileTyping, search })
  latest.current = { value, actions, active, predictWhileTyping, search }
  const token = snapshot?.token

  const cancelPending = useCallback(() => {
    if (pending.current?.timer != null) clearTimeout(pending.current.timer)
    pending.current = null
  }, [])
  const dismiss = useCallback(() => {
    cancelPending()
    controller.current?.abort()
    controller.current = null
    currentTarget.current = null
    setTarget(null)
    setCandidates([])
    setBusy(false)
    setMessage('')
    setNeedsSettings(false)
  }, [cancelPending])
  useLayoutEffect(() => {
    const completion = currentTarget.current
    if (completion && (!active || completion.snapshot.token !== token ||
      completion.prefix !== undefined && (!predictWhileTyping || completion.search !== search))) dismiss()
    if (!active) { composing.current = null; focusedText.current = null }
    const suggestion = accepted.current
    if (suggestion) {
      if (!active || snapshot?.blocked || !suggestion.input.isConnected ||
        suggestion.input.ownerDocument.activeElement !== suggestion.input ||
        suggestion.token !== undefined && suggestion.token !== token) accepted.current = null
      else if (suggestion.previousToken !== token) {
        if (fieldText(aiCompletableValue(value, suggestion.pointer)) === suggestion.text) suggestion.token = token
        else accepted.current = null
      }
    }
  }, [active, predictWhileTyping, snapshot?.blocked, token, value, search, dismiss])
  useLayoutEffect(() => () => {
    cancelPending()
    controller.current?.abort()
  }, [cancelPending])

  const checkCaret = useCallback(() => {
    const prediction = pending.current
    if (prediction && !canContinue(prediction.input, prediction.text, prediction.clickCaret)) cancelPending()
    const completion = currentTarget.current
    if (completion?.prefix !== undefined && !canContinue(completion.input, completion.prefix, completion.clickCaret)) dismiss()
  }, [cancelPending, dismiss])
  useEffect(() => {
    const doc = host.current?.ownerDocument
    if (!doc || !active) return
    const blur = () => { accepted.current = null; dismiss() }
    const hide = () => { if (doc.hidden) blur() }
    doc.addEventListener('selectionchange', checkCaret)
    doc.addEventListener('visibilitychange', hide)
    doc.defaultView?.addEventListener('blur', blur)
    return () => {
      doc.removeEventListener('selectionchange', checkCaret)
      doc.removeEventListener('visibilitychange', hide)
      doc.defaultView?.removeEventListener('blur', blur)
    }
  }, [active, checkCaret, dismiss])

  useLayoutEffect(() => {
    if (!active || !target || !popover.current) return
    const panel = popover.current
    const anchor = target.input
    const viewport = anchor.ownerDocument.documentElement
    const view = anchor.ownerDocument.defaultView
    if (!view) return
    const position = () => {
      const rect = anchor.getBoundingClientRect()
      const width = viewport.clientWidth || view.innerWidth
      const height = viewport.clientHeight || view.innerHeight
      const margin = 8
      const gap = 5
      panel.style.maxHeight = `${Math.max(margin, Math.min(height - margin * 2, height * 0.45))}px`
      const below = height - rect.bottom - gap - margin
      const above = rect.top - gap - margin
      const flip = below < panel.offsetHeight && above > below
      const available = Math.max(margin, flip ? above : below)
      panel.style.maxHeight = `${Math.min(available, height * 0.45)}px`
      const top = flip ? rect.top - gap - panel.offsetHeight : rect.bottom + gap
      panel.style.top = `${Math.max(margin, Math.min(top, height - panel.offsetHeight - margin))}px`
      panel.style.left = `${Math.max(margin, Math.min(rect.left, width - panel.offsetWidth - margin))}px`
    }
    position()
    view.addEventListener('resize', position)
    view.addEventListener('scroll', position, true)
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(position)
    observer?.observe(anchor)
    const intersection = typeof IntersectionObserver === 'undefined' ? null : new IntersectionObserver(([entry]) => {
      panel.style.visibility = entry.isIntersecting ? 'visible' : 'hidden'
      if (entry.isIntersecting) position()
    })
    intersection?.observe(anchor)
    return () => {
      view.removeEventListener('resize', position)
      view.removeEventListener('scroll', position, true)
      observer?.disconnect()
      intersection?.disconnect()
    }
  }, [active, target, busy, message, candidates])

  const adopt = (index: number) => {
    const field = currentTarget.current
    const candidate = candidates[index]
    if (!field || candidate === undefined || actions()?.snapshot()?.token !== field.snapshot.token ||
      field.prefix !== undefined && !canContinue(field.input, field.prefix, field.clickCaret)) { dismiss(); return }
    try {
      const text = candidate.replace(/\r\n?/g, '\n')
      if (popover.current?.contains(field.input.ownerDocument.activeElement)) field.input.focus()
      if (onUndoSuggestion && field.input.ownerDocument.activeElement === field.input && field.input.value !== text) {
        accepted.current = { input: field.input, pointer: field.pointer, text, previousToken: field.snapshot.token }
      }
      onApply(field.pointer, field.array ? text === '' ? [] : text.split('\n') : text, field.snapshot)
      dismiss()
    } catch (reason) { accepted.current = null; setMessage(reason instanceof Error ? reason.message : String(reason)); setCandidates([]) }
  }
  const closePopover = () => {
    const input = currentTarget.current?.input
    if (input && popover.current?.contains(input.ownerDocument.activeElement)) input.focus()
    dismiss()
  }
  const generate = useCallback(async (input: TextControl, pointer: string, currentValue: string | string[], prefix?: string) => {
    const pane = latest.current.actions()
    const expected = pane?.snapshot()
    if (!pane || !expected || expected.blocked) return
    dismiss()
    const request = new AbortController()
    controller.current = request
    const field = { input, pointer, snapshot: expected, array: Array.isArray(currentValue), prefix, search: latest.current.search }
    currentTarget.current = field
    setTarget(field)
    const automatic = prefix !== undefined
    const isCurrent = () => !request.signal.aborted && input.isConnected && pane.snapshot()?.token === expected.token &&
      (!automatic || canContinue(input, prefix))
    setBusy(!automatic)
    try {
      const settings = await aiAssistApi.settings(request.signal)
      if (!isCurrent()) return
      if (!settings.configured) { setNeedsSettings(true); setMessage('入力補完を使うには、AIアシストの接続設定でAPIキーを設定してください。'); return }
      const raw = await pane.raw()
      if (!isCurrent()) return
      const result = await aiAssistApi.propose({
        mode: 'complete',
        completionStyle: automatic ? 'continue' : undefined,
        instruction: automatic ? '入力した文章の続きを提案してください。' : 'この文字列フィールドの自然な日本語の候補を提案してください。候補はフィールドの全文です。',
        sourcePath: expected.path, raw, fieldPointer: pointer,
      }, request.signal)
      if (!isCurrent()) return
      const suggestions = automatic ? result.suggestions.filter(candidate =>
        candidate.startsWith(prefix) && candidate.slice(prefix.length).trim() &&
        !candidate.includes('\r') && (input.tagName !== 'INPUT' || !candidate.includes('\n')) &&
        (input.maxLength < 0 || candidate.length <= input.maxLength)) : result.suggestions
      const warnings = result.warnings.join(' ')
      if (automatic && !suggestions.length && !warnings) { dismiss(); return }
      setCandidates(suggestions.slice(0, 3))
      setSelected(0)
      setMessage(suggestions.length ? (automatic && warnings ? `${warnings} ${shortcuts}` : shortcuts)
        : warnings || '候補がありません。入力を変えてCtrl+Spaceで再試行してください。')
    } catch (reason) { if (isCurrent()) setMessage(reason instanceof Error ? reason.message : String(reason)) }
    finally { if (controller.current === request) { controller.current = null; setBusy(false) } }
  }, [dismiss])

  const resolvePrediction = useCallback(() => {
    const prediction = pending.current
    if (!prediction) return
    const { input, pointer, text, clickCaret } = prediction
    const { value, actions, active, predictWhileTyping, search } = latest.current
    const expected = actions()?.snapshot()
    const field = aiCompletableValue(value, pointer)
    if (!active || !predictWhileTyping || !expected || expected.blocked ||
      prediction.token !== undefined && prediction.token !== expected.token ||
      field === null || fieldText(field) !== text || composing.current?.input === input || !canContinue(input, text, clickCaret)) {
      cancelPending()
      return
    }

    // The field change creates a new draft token before this prediction is shown.
    prediction.token = expected.token
    const local = search?.(expected.path, pointer, text, input.maxLength < 0 ? Infinity : input.maxLength, input.tagName === 'TEXTAREA')
    if (local?.suggestions.length) {
      dismiss()
      const next = { input, pointer, snapshot: expected, array: Array.isArray(field), prefix: text, clickCaret, search }
      currentTarget.current = next
      setTarget(next)
      setCandidates(local.suggestions)
      setSelected(0)
      setMessage(shortcuts)
    } else if (local?.indexReady && prediction.timer === null) {
      if (clickCaret === undefined && text.replace(/\s/g, '').length >= 2) void generate(input, pointer, field, text)
      else cancelPending()
    }
  }, [cancelPending, dismiss, generate])

  useLayoutEffect(() => { resolvePrediction() }, [active, predictWhileTyping, token, value, search, resolvePrediction])

  const schedule = (eventTarget: EventTarget, fromClick = false) => {
    const input = textControl(eventTarget)
    if (fromClick && !input) return
    if (!fromClick) accepted.current = null
    if (pending.current || currentTarget.current) dismiss()
    if (!predictWhileTyping || !active || !snapshot || snapshot.blocked || !input || composing.current?.input === input) return
    const pointer = input.getAttribute('aria-label') ?? ''
    const currentValue = aiCompletableValue(value, pointer)
    const text = input.value
    const clickCaret = fromClick ? input.selectionStart : undefined
    if (!prosePointer(pointer) || currentValue === null || (!text.trim() && (!fromClick || text !== '')) ||
      text.length > 8000 || clickCaret === null || Array.isArray(currentValue) && currentValue.some(line => /[\r\n]/.test(line)) ||
      !canContinue(input, text, clickCaret)) return
    const timer = fromClick || text.replace(/\s/g, '').length < 2 ? null : setTimeout(() => {
      const scheduled = pending.current
      if (!scheduled || scheduled.timer !== timer) return
      scheduled.timer = null
      resolvePrediction()
    }, predictionDelay)
    pending.current = { input, pointer, text, clickCaret, token: fieldText(currentValue) === text ? token : undefined, timer }
    // IME confirmation can arrive after the final change event has updated the form.
    if (fieldText(currentValue) === text) resolvePrediction()
  }

  const keyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!active || event.nativeEvent.isComposing || event.keyCode === 229 || event.repeat) return
    if (popover.current?.contains(event.target as Node)) {
      if (event.key === 'Escape') {
        event.preventDefault(); event.stopPropagation(); closePopover()
      } else if (event.key === 'Tab' && event.shiftKey && event.target === popover.current.querySelector('button')) {
        event.preventDefault()
        closePopover()
      }
      return
    }
    const input = textControl(event.target)
    if (snapshot?.blocked || !input || composing.current?.input === input) return
    const pointer = input.getAttribute('aria-label') ?? ''
    const fieldValue = aiCompletableValue(value, pointer)
    if (fieldValue === null) return
    const suggestion = accepted.current
    if (suggestion && onUndoSuggestion && event.key.toLowerCase() === 'z' &&
      (event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey &&
      suggestion.input === input && suggestion.pointer === pointer && suggestion.token === token &&
      token !== undefined && input.value === suggestion.text && input.ownerDocument.activeElement === input) {
      event.preventDefault(); event.stopPropagation()
      accepted.current = null
      onUndoSuggestion()
      return
    }
    if (event.key === ' ' && event.ctrlKey && !event.altKey && !event.metaKey && !event.shiftKey) {
      event.preventDefault(); event.stopPropagation(); void generate(input, pointer, fieldValue); return
    }
    const completion = currentTarget.current
    if (event.ctrlKey || event.altKey || event.metaKey || event.shiftKey) return
    if (event.key === 'Escape' && (pending.current?.input === input || completion?.input === input)) {
      event.preventDefault(); event.stopPropagation(); dismiss(); return
    }
    if (completion?.prefix !== undefined && !canContinue(input, completion.prefix, completion.clickCaret)) { dismiss(); return }
    if (completion?.input !== input) return
    if (event.key === 'Tab' && candidates.length) { event.preventDefault(); event.stopPropagation(); adopt(selected) }
    else if (event.key === 'Tab' && completion.prefix === undefined) {
      const button = popover.current?.querySelector('button')
      if (button) { event.preventDefault(); event.stopPropagation(); button.focus() }
    }
    else if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && candidates.length) {
      event.preventDefault(); event.stopPropagation(); setSelected(index => (index + (event.key === 'ArrowDown' ? 1 : candidates.length - 1)) % candidates.length)
    }
  }
  return <div ref={host} className="master-ai-completion-host"
    onKeyDownCapture={keyDown}
    onFocusCapture={event => {
      const input = textControl(event.target)
      const pointer = input?.getAttribute('aria-label') ?? ''
      if (predictWhileTyping && active && snapshot && !snapshot.blocked && input && prosePointer(pointer)) {
        focusedText.current = { input, path: snapshot.path, pointer, original: input.value }
      }
    }}
    onChange={event => schedule(event.target)}
    onClick={event => { if (event.button === 0) schedule(event.target, true) }}
    onSelect={checkCaret}
    onPointerDownCapture={event => { if (!popover.current?.contains(event.target as Node) && (pending.current || currentTarget.current)) dismiss() }}
    onBlurCapture={event => {
      const focused = focusedText.current
      if (focused?.input === event.target) {
        focusedText.current = null
        const text = focused.input.value
        if (predictWhileTyping && snapshot?.path === focused.path && !snapshot.blocked &&
          composing.current?.input !== focused.input && text !== focused.original &&
          fieldText(aiCompletableValue(value, focused.pointer)) === text) {
          predictionSource?.remember(focused.path, focused.pointer, text)
        }
      }
      if (accepted.current?.input === event.target) accepted.current = null
      if (composing.current?.input === event.target) composing.current = null
      if (!popover.current?.contains(event.relatedTarget) && (pending.current || currentTarget.current)) dismiss()
    }}
    onCompositionStartCapture={event => {
      accepted.current = null
      const input = textControl(event.target)
      composing.current = input ? { input, original: input.value } : null
      if (pending.current || currentTarget.current) dismiss()
    }}
    onCompositionEnd={event => {
      const composition = composing.current
      composing.current = null
      if (composition && event.data && composition.input.value !== composition.original) schedule(event.target)
    }}>
    {children}
    {active && target && (busy || message || candidates.length > 0) && createPortal(<aside ref={popover} className="master-ai-completion" aria-label="AI入力補完" onMouseDown={event => {
      if ((event.target as HTMLElement).closest('button')) event.preventDefault()
    }}>
      <div className="button-row"><strong>{target.prefix === undefined ? 'AI入力補完' : 'AI予測変換'}</strong><button type="button" className="button compact" onClick={closePopover}>{busy ? '生成をキャンセル' : '候補を閉じる'}</button></div>
      <p role="status">{busy ? '選択中の原稿をOpenAIへ送信して候補を生成しています…' : message}</p>
      {needsSettings && <button type="button" className="button" onClick={() => { dismiss(); onConfigure() }}>接続設定を開く</button>}
      {candidates.length > 0 && <div role="listbox" aria-label="AI補完候補">{candidates.map((candidate, index) => <button type="button" role="option" aria-selected={index === selected} className={`master-ai-completion-option ${index === selected ? 'selected' : ''}`} key={index} onClick={() => adopt(index)}><pre>{candidate.slice(target.prefix?.length ?? 0)}</pre><small>採用</small></button>)}</div>}
    </aside>, target.input.ownerDocument.body)}
  </div>
}
