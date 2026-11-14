import { useCallback, useEffect, useRef, useState } from 'react'
import { masterDataApi } from '../api/masterDataApi'
import type { JsonValue } from '../types/editor'
import type { MasterDocument } from '../types/masterData'

export interface DesignDraft { document: MasterDocument; content: JsonValue }
export interface DesignSaveCandidate { path: string; before: string; raw: string; revision: string; warnings: string[] }
type Drafts = Record<string, DesignDraft>
const changed = (entry: DesignDraft) => JSON.stringify(entry.document.content) !== JSON.stringify(entry.content)
const errorMessage = (reason: unknown) => reason instanceof Error ? reason.message : String(reason)

export function useDesignDocuments(workspaceRoot: string, expectedRevisions?: Record<string, string>) {
  const revisions = useRef(expectedRevisions)
  revisions.current = expectedRevisions
  const [drafts, setDrafts] = useState<Drafts>({})
  const latest = useRef<Drafts>({})
  const undo = useRef<Drafts[]>([])
  const redo = useRef<Drafts[]>([])
  const [historyVersion, setHistoryVersion] = useState(0)
  const [pending, setPending] = useState(0)
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)
  const pendingRef = useRef(0)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [recovery, setRecovery] = useState<Drafts | null>(null)
  const recoveryPending = useRef(false)
  const recoveredRoot = useRef('')
  const storageKey = workspaceRoot ? `astralrecord.progression-design.v1:${workspaceRoot}` : ''
  const install = useCallback((next: Drafts) => { latest.current = next; setDrafts(next) }, [])
  const dirtyEntries = Object.values(drafts).filter(changed)
  const dirty = dirtyEntries.length > 0

  useEffect(() => {
    if (!storageKey || recoveredRoot.current === storageKey) return
    recoveredRoot.current = storageKey
    try {
      const parsed: unknown = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null')
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        const entries = Object.values(parsed)
        if (entries.length && entries.every(entry => entry && typeof entry === 'object' && 'document' in entry && 'content' in entry && typeof entry.document?.path === 'string' && typeof entry.document?.raw === 'string' && typeof entry.document?.revision === 'string')) { recoveryPending.current = true; setRecovery(parsed as Drafts) }
      }
    } catch { /* A damaged or unavailable browser draft does not prevent editing. */ }
  }, [storageKey])
  useEffect(() => {
    if (!storageKey || recovery || recoveryPending.current) return
    try {
      if (dirty) sessionStorage.setItem(storageKey, JSON.stringify(Object.fromEntries(Object.entries(drafts).filter(([, entry]) => changed(entry)))))
      else sessionStorage.removeItem(storageKey)
    } catch { if (dirty) setNotice('ブラウザへの下書き退避ができません。画面を閉じる前に保存してください。') }
  }, [storageKey, drafts, dirty, recovery])
  useEffect(() => {
    if (!dirty && !recovery) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty, recovery])

  const loadRequests = useRef(new Map<string, Promise<MasterDocument>>())
  const ensure = useCallback(async (path: string) => {
    const cached = latest.current[path]
    if (cached) {
      if (!revisions.current || revisions.current[path] === cached.document.revision) return cached
      if (changed(cached)) throw new Error('編集中のファイルが外部で変更されています。下書きを保持して差分を確認してください。')
      const next = { ...latest.current }; delete next[path]; install(next)
    }
    let request = loadRequests.current.get(path)
    if (!request) {
      request = masterDataApi.file(path)
      loadRequests.current.set(path, request)
      void request.finally(() => loadRequests.current.delete(path)).catch(() => {})
    }
    const document = await request
    if (revisions.current && revisions.current[path] !== document.revision) throw new Error('表示後にファイルが変更されています。未保存の下書きを保持したまま図を再取得してください。')
    if (latest.current[path]) return latest.current[path]
    const entry = { document, content: document.content }
    install({ ...latest.current, [path]: entry })
    return entry
  }, [install])

  const edit = useCallback(async (path: string, transform: (content: JsonValue) => JsonValue) => {
    if (savingRef.current || recovery) return false
    pendingRef.current++; setPending(pendingRef.current); setError(''); setNotice('')
    try {
      await ensure(path)
      const current = latest.current
      const entry = current[path]
      const content = transform(entry.content)
      if (JSON.stringify(content) === JSON.stringify(entry.content)) return true
      undo.current = [...undo.current.slice(-79), current]; redo.current = []
      install({ ...current, [path]: { ...entry, content } })
      setHistoryVersion(version => version + 1)
      return true
    } catch (reason) { setError(errorMessage(reason)); return false }
    finally { pendingRef.current--; setPending(pendingRef.current) }
  }, [ensure, install, recovery])

  const move = (direction: 'undo' | 'redo') => {
    if (savingRef.current || pendingRef.current || recovery) return
    const from = direction === 'undo' ? undo : redo
    const to = direction === 'undo' ? redo : undo
    const value = from.current.pop()
    if (!value) return
    to.current.push(latest.current); install(value); setError(''); setNotice(''); setHistoryVersion(version => version + 1)
  }
  const prepare = async (): Promise<DesignSaveCandidate[] | null> => {
    if (savingRef.current || pendingRef.current || recovery) return null
    savingRef.current = true; setSaving(true); setError(''); setNotice('')
    try {
      const results: DesignSaveCandidate[] = []
      for (const entry of Object.values(latest.current).filter(changed)) {
        const { document, content } = entry
        const rendered = await masterDataApi.render(document.path, content, document.raw)
        const validation = await masterDataApi.validate(document.path, rendered.raw)
        if (!validation.isValid) throw new Error(`${document.path}\n${validation.issues.filter(issue => issue.severity === 'error').map(issue => `${issue.path || ''} ${issue.message}`).join('\n')}`)
        results.push({ path: document.path, before: document.raw, raw: rendered.raw, revision: document.revision,
          warnings: [...(rendered.warnings ?? []), ...(!rendered.commentsPreserved ? ['原稿変換でコメントや書式が変わる可能性があります。差分を確認してください。'] : []), ...validation.issues.filter(issue => issue.severity === 'warning').map(issue => issue.message)] })
      }
      return results
    } catch (reason) { setError(errorMessage(reason)); return null }
    finally { savingRef.current = false; setSaving(false) }
  }
  const save = async (candidates: DesignSaveCandidate[]): Promise<boolean> => {
    if (savingRef.current || pendingRef.current || recovery) return false
    savingRef.current = true; setSaving(true); setError(''); setNotice('')
    let savedCount = 0
    try {
      for (const candidate of candidates) {
        const entry = latest.current[candidate.path]
        if (!entry || entry.document.revision !== candidate.revision) throw new Error('保存確認後に原稿が変わりました。変更内容をもう一度確認してください。')
        const current = await masterDataApi.render(candidate.path, entry.content, entry.document.raw)
        if (current.raw !== candidate.raw) throw new Error('保存確認後に編集内容が変わりました。もう一度確認してください。')
      }
      for (const candidate of candidates) {
        const document = await masterDataApi.save(candidate.path, candidate.raw, candidate.revision)
        install({ ...latest.current, [candidate.path]: { document, content: document.content } })
        savedCount++
      }
      setNotice(`${savedCount}件のファイルを保存しました。`)
      return true
    } catch (reason) {
      setError(`${savedCount ? `${savedCount}件は保存済みです。残りの編集は保持しています。\n` : ''}${errorMessage(reason)}\n競合した場合は下書きを書き出し、最新のファイルを再読込して差分を確認してください。`)
      return false
    } finally {
      if (savedCount) { undo.current = []; redo.current = []; setHistoryVersion(version => version + 1) }
      savingRef.current = false; setSaving(false)
    }
  }
  const reset = () => {
    if (savingRef.current || pendingRef.current) return
    install({}); undo.current = []; redo.current = []; recoveryPending.current = false; setRecovery(null); setError(''); setNotice(''); setHistoryVersion(version => version + 1)
    if (storageKey) { try { sessionStorage.removeItem(storageKey) } catch { /* optional browser recovery */ } }
  }
  void historyVersion
  return { drafts, dirtyEntries, dirty, pending, saving, error, notice, setError, setNotice, edit, ensure, prepare, save, reset,
    canUndo: undo.current.length > 0, canRedo: redo.current.length > 0, undo: () => move('undo'), redo: () => move('redo'), recovery,
    restore: () => { if (recovery) { install(recovery); recoveryPending.current = false; setRecovery(null); setNotice('下書きを復元しました。保存時に元ファイルの競合を確認します。') } },
    discardRecovery: () => { recoveryPending.current = false; setRecovery(null); if (storageKey) { try { sessionStorage.removeItem(storageKey) } catch { /* optional browser recovery */ } } },
  }
}
