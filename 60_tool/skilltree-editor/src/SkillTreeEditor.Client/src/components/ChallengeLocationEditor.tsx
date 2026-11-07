import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import type { JsonObject, JsonValue, ValidationIssue, ValidationReport } from '../types/editor'
import type { MasterDocument, MasterFileSummary } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MasterDataDiff } from './MasterDataDiff'
import './ChallengeLocationEditor.css'

const bossCategory = '40.features.mob/boss'
const dungeonCategory = '65.features.dungeon'
const worldCategory = '60.features.world'
const coordinates = ['x', 'y', 'z', 'yaw', 'pitch'] as const
const required = ['x', 'y', 'z'] as const
type Coordinate = typeof coordinates[number]
type Draft = Record<Coordinate | 'world', string>
type Point = { key: number; x: number; y: number | null; z: number }
type Props = {
  worldId: string
  point: Point | null
  onPickingChange: (value: boolean) => void
  onDirtyChange: (value: boolean) => void
  onBusyChange?: (value: boolean) => void
  onPositionChange?: (value: { x: number; y: number; z: number } | null) => void
  active?: boolean
  canPick?: boolean
}

type Opened = {
  document: MasterDocument
  root: JsonObject
  challenge?: JsonObject
  location: JsonObject
  initial: Draft
}
type PickRequest = { worldId: string; afterKey: number | undefined }
type Review = { raw: string; warnings: string[] }

function asObject(value: JsonValue | undefined): JsonObject | undefined {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : undefined
}

function openLocation(document: MasterDocument, boss: boolean): Opened {
  const parseError = document.issues.find(issue => issue.severity === 'error')
  if (parseError) throw new Error(parseError.message)
  const root = asObject(document.content)
  const challenge = boss ? asObject(root?.challenge) : undefined
  const location = asObject(boss ? challenge?.entryLocation : root?.entry)
  if (!root || !location) throw new Error('編集できる受付地点がマスターにありません。')
  const world = location[boss ? 'worldId' : 'worldRef']
  const numberText = (key: Coordinate) => {
    const value = location[key]
    return typeof value === 'number' || typeof value === 'string' ? String(value) : ''
  }
  const initial = {
    world: typeof world === 'string' ? world : '',
    x: numberText('x'), y: numberText('y'), z: numberText('z'),
    yaw: numberText('yaw'), pitch: numberText('pitch'),
  }
  return { document, root, challenge, location, initial }
}

function finiteDecimal(value: string): boolean {
  return /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim()) && Number.isFinite(Number(value))
}

function applyLocation(opened: Opened, draft: Draft): JsonObject {
  const location = { ...opened.location }
  if (draft.world !== opened.initial.world) location[opened.challenge ? 'worldId' : 'worldRef'] = draft.world
  for (const key of coordinates) {
    if (draft[key] === opened.initial[key]) continue
    if (draft[key].trim()) location[key] = Number(draft[key])
    else delete location[key]
  }
  return opened.challenge
    ? { ...opened.root, challenge: { ...opened.challenge, entryLocation: location } }
    : { ...opened.root, entry: location }
}

function errorMessage(reason: unknown): string {
  return reason instanceof Error ? reason.message : '操作に失敗しました。'
}

function optionLabel(file: MasterFileSummary): string {
  const id = file.id ?? file.path
  return file.name ? `${stripMinecraftFormatting(file.name)} (${id})` : id
}

function isValidationReport(value: unknown): value is ValidationReport {
  return typeof value === 'object' && value !== null && 'isValid' in value && 'issues' in value && Array.isArray(value.issues)
}

export function ChallengeLocationEditor({
  worldId, point, onPickingChange, onDirtyChange, onBusyChange, onPositionChange, active = true, canPick = true,
}: Props) {
  const [catalog, setCatalog] = useState<{ bosses: MasterFileSummary[]; dungeons: MasterFileSummary[]; worlds: MasterFileSummary[] } | null>(null)
  const [catalogError, setCatalogError] = useState('')
  const [catalogAttempt, setCatalogAttempt] = useState(0)
  const [selection, setSelection] = useState('')
  const [opened, setOpened] = useState<Opened | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [pick, setPick] = useState<PickRequest | null>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [issues, setIssues] = useState<ValidationIssue[]>([])
  const [review, setReview] = useState<Review | null>(null)
  const [reviewedRaw, setReviewedRaw] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const operation = useRef(0)
  const savingNow = useRef(false)
  const dirty = !!(opened && draft && (draft.world !== opened.initial.world || coordinates.some(key => draft[key] !== opened.initial[key])))
  const picking = active && canPick && pick !== null && pick.worldId === worldId
  const positionValid = !!(draft && required.every(key => finiteDecimal(draft[key])))
  const valid = !!(draft?.world && positionValid && coordinates.slice(3).every(key => !draft[key].trim() || finiteDecimal(draft[key])))
  const catalogLoading = active && !catalog && !catalogError
  const worlds = (catalog?.worlds ?? []).filter(file => file.id)
  const worldOptions = worlds.map(file => ({ value: opened?.challenge ? file.id! : `world:${file.id}`, label: optionLabel(file) }))
  const currentMapValue = opened?.challenge ? worldId : `world:${worldId}`
  const currentMapKnown = worldOptions.some(option => option.value === currentMapValue)
  const showPosition = active && !loading && positionValid && draft?.world === currentMapValue
  const positionX = showPosition ? Number(draft.x) : null
  const positionY = showPosition ? Number(draft.y) : null
  const positionZ = showPosition ? Number(draft.z) : null

  useEffect(() => {
    if (!active || catalog || catalogError) return
    let cancelled = false
    void Promise.all([masterDataApi.files(bossCategory, 'entryLocation'), masterDataApi.files(dungeonCategory), masterDataApi.files(worldCategory)])
      .then(([bosses, dungeons, worlds]) => {
        if (cancelled) return
        const editableYaml = (file: MasterFileSummary) => file.format === 'yaml' && !file.readOnly
        setCatalog({ bosses: bosses.filter(editableYaml), dungeons: dungeons.filter(editableYaml), worlds })
      })
      .catch(reason => { if (!cancelled) setCatalogError(errorMessage(reason)) })
    return () => { cancelled = true }
  }, [active, catalog, catalogError, catalogAttempt])

  useEffect(() => () => { operation.current++ }, [])
  useEffect(() => { onDirtyChange(dirty) }, [dirty, onDirtyChange])
  useEffect(() => { onPickingChange(picking) }, [picking, onPickingChange])
  useEffect(() => { onBusyChange?.(catalogLoading || loading || saving) }, [catalogLoading, loading, saving, onBusyChange])
  useEffect(() => {
    onPositionChange?.(positionX === null || positionY === null || positionZ === null ? null : { x: positionX, y: positionY, z: positionZ })
  }, [onPositionChange, positionX, positionY, positionZ])
  useEffect(() => {
    if (!picking) {
      if (pick) setPick(null)
      return
    }
    if (!point || point.key === pick.afterKey || !currentMapKnown) return
    setPick(null)
    setDraft(previous => previous && {
      ...previous, world: currentMapValue, x: String(point.x), y: point.y === null ? '' : String(point.y), z: String(point.z),
    })
    setNotice(point.y === null ? '地図に高さがありません。Y 座標を入力してください。' : '地図上の地点を入力欄に反映しました。')
    setReviewedRaw(null)
    setError('')
    setIssues([])
  }, [picking, pick, point, currentMapKnown, currentMapValue])

  const load = async (path: string) => {
    if (savingNow.current || (dirty && !window.confirm('未保存の受付地点の変更を破棄しますか？'))) return
    const request = ++operation.current
    setSelection(path)
    setPick(null)
    setError('')
    setNotice('')
    setIssues([])
    setReview(null)
    if (!path) {
      setOpened(null)
      setDraft(null)
      setConflict(false)
      setReviewedRaw(null)
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      const document = await masterDataApi.file(path)
      if (request !== operation.current) return
      const next = openLocation(document, catalog?.bosses.some(file => file.path === path) ?? false)
      setOpened(next)
      setDraft(next.initial)
      setConflict(false)
      setReviewedRaw(null)
    } catch (reason) {
      if (request !== operation.current) return
      setError(errorMessage(reason))
      // Keep the current draft available if reloading or switching masters fails.
      setSelection(opened?.document.path ?? path)
    } finally {
      if (request === operation.current) setLoading(false)
    }
  }

  const edit = (key: keyof Draft, value: string) => {
    setDraft(previous => previous && { ...previous, [key]: value })
    setReviewedRaw(null)
    setNotice('')
    setIssues([])
    if (!conflict) setError('')
  }

  const save = async () => {
    if (!opened || !draft || !valid || !dirty || loading || conflict || savingNow.current) return
    savingNow.current = true
    const request = ++operation.current
    const { document } = opened
    setSaving(true)
    setPick(null)
    setError('')
    setNotice('')
    setIssues([])
    try {
      const rendered = await masterDataApi.render(document.path, applyLocation(opened, draft), document.raw)
      if (request !== operation.current) return
      if ((!rendered.commentsPreserved || rendered.warnings.length) && reviewedRaw !== rendered.raw) {
        setReview({ raw: rendered.raw, warnings: rendered.warnings })
        return
      }
      const validation = await masterDataApi.validate(document.path, rendered.raw)
      if (request !== operation.current) return
      setIssues(validation.issues)
      if (!validation.isValid) {
        setError('検証エラーがあるため保存できませんでした。')
        return
      }
      const saved = opened.challenge && draft.world !== opened.initial.world
        ? await masterDataApi.save(document.path, rendered.raw, document.revision, true)
        : await masterDataApi.save(document.path, rendered.raw, document.revision)
      if (request !== operation.current) return
      const next = openLocation(saved, Boolean(opened.challenge))
      setOpened(next)
      setDraft(next.initial)
      setReviewedRaw(null)
      setNotice('受付地点を Filebase に保存しました。')
    } catch (reason) {
      if (request !== operation.current) return
      if (reason instanceof ApiError && reason.status === 409) {
        setConflict(true)
        setError('同じマスターが別の操作で変更されました。入力した座標を控え、最新のマスターを再読込してください。')
      } else {
        if (reason instanceof ApiError && isValidationReport(reason.payload)) setIssues(reason.payload.issues)
        setError(errorMessage(reason))
      }
    } finally {
      savingNow.current = false
      if (request === operation.current) setSaving(false)
    }
  }

  return <section className="challenge-location-editor" aria-label="チャレンジ受付地点" hidden={!active}>
    <h2>チャレンジ受付地点</h2>
    <p className="sp-muted">ボスとダンジョンの受付地点を Filebase に保存します。</p>
    {catalogLoading && <p role="status">マスター一覧を読み込んでいます…</p>}
    {catalogError && <div role="alert"><p>{catalogError}</p><button type="button" className="button" onClick={() => { setCatalogError(''); setCatalogAttempt(count => count + 1) }}>一覧を再読込</button></div>}
    {catalog && <div className="challenge-location-actions">
      <label>受付地点のマスター
        <select value={selection} disabled={saving} onChange={event => void load(event.target.value)}>
          <option value="">選択してください</option>
          <optgroup label="ボス">{catalog.bosses.map(file => <option key={file.path} value={file.path}>{optionLabel(file)}</option>)}</optgroup>
          <optgroup label="ダンジョン">{catalog.dungeons.map(file => <option key={file.path} value={file.path}>{optionLabel(file)}</option>)}</optgroup>
        </select>
      </label>
      <button type="button" className="button" disabled={!selection || loading || saving} onClick={() => void load(selection)}>マスターを再読込</button>
    </div>}
    {catalog && !catalog.bosses.length && !catalog.dungeons.length && <p role="status">編集できる受付地点のマスターがありません。</p>}
    {loading && <p role="status">受付地点を読み込んでいます…</p>}
    {opened && draft && <fieldset disabled={loading || saving}>
      <legend>受付地点の座標{dirty ? ' · 未保存' : ''}</legend>
      <label>受付地点のワールド
        <select value={draft.world} onChange={event => edit('world', event.target.value)}>
          <option value="">選択してください</option>
          {draft.world && !worldOptions.some(option => option.value === draft.world) && <option value={draft.world}>{draft.world}（既存の指定）</option>}
          {worldOptions.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      </label>
      <div className="challenge-location-coordinates">
        {coordinates.map(key => <label key={key}>{key.toUpperCase()}{(key === 'yaw' || key === 'pitch') && '（任意）'}
          <input type="text" inputMode="decimal" aria-invalid={Boolean(draft[key].trim() && !finiteDecimal(draft[key]))}
            value={draft[key]} onChange={event => edit(key, event.target.value)} />
        </label>)}
      </div>
      <div className="challenge-location-actions">
        <button type="button" className="button" aria-pressed={picking} disabled={!canPick || !currentMapKnown || conflict} onClick={() => setPick(picking ? null : { worldId, afterKey: point?.key })}>{picking ? '地点の選択をキャンセル' : '地図で受付地点を選択'}</button>
        <button type="button" className="button primary" disabled={!valid || !dirty || conflict || picking} onClick={() => void save()}>受付地点を保存</button>
      </div>
      {picking && <p role="status">地図をクリックすると、表示中のワールドと地点を入力欄に反映します。</p>}
      {!valid && <p className="sp-muted">ワールドと有限の X・Y・Z 座標を指定してください。向きを指定する場合も数値を入力してください。</p>}
    </fieldset>}
    {saving && <p role="status">受付地点を保存しています…</p>}
    {notice && <p role="status">{notice}</p>}
    {error && <p role="alert" className="error-message">{error}</p>}
    {issues.length > 0 && <ul aria-label="受付地点の検証結果">{issues.map((issue, index) => <li key={index}>{issue.severity === 'error' ? 'エラー' : '警告'} {issue.path} {issue.message}</li>)}</ul>}
    {review && opened && active && <MasterDataDiff before={opened.document.raw} after={review.raw} onClose={() => {
      setReviewedRaw(review.raw)
      setNotice(`${review.warnings.join(' ') || '変換後の YAML を確認しました。'} 反映するにはもう一度「受付地点を保存」を押してください。`)
      setReview(null)
    }} />}
  </section>
}
