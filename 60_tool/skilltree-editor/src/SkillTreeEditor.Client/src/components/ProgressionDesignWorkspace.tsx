import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { masterDataApi } from '../api/masterDataApi'
import { progressionDesignApi } from '../api/progressionDesignApi'
import { appendDesignEntry, changeEquipment, equipmentRestriction, equipmentSlots, numericDesignValue, setDesignValue, sourceLabels } from '../data/progressionDesign'
import { setAccountClassLevel } from '../data/progressionAccount'
import { addLevelStage, changeBuildIdentity, changeLevelStage, removeLevelStage } from '../data/progressionBuildManagement'
import { parentPointer, valueAtPointer } from '../data/masterDataNavigation'
import { useDesignDocuments, type DesignSaveCandidate } from '../state/useDesignDocuments'
import type { DesignField, DesignIngredient, DesignItem, DesignSource, ProgressionDesignCatalog } from '../types/progressionDesign'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { AcquisitionGraph } from './AcquisitionGraph'
import { AccountProgressionPanel } from './AccountProgressionPanel'
import { BuildManagementDialog, type BuildAction } from './BuildManagementDialog'
import { MasterEntityPicker, type MasterEntityPickerChoice } from './MasterEntityPicker'
import { MasterDataDiff } from './MasterDataDiff'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { Modal } from './Modal'
import { ResizeHandle, usePanelSize } from './ResizeHandle'
import './progressionDesign.css'

type Picker = { kind: 'equipment'; slot: string } | { kind: 'browse' } | { kind: 'material'; source: DesignSource; ingredient?: DesignIngredient } | { kind: 'source'; itemId: string }
interface Props { active?: boolean; onDirtyChange?: (dirty: boolean) => void; onOpenMaster: (path: string, pointer?: string) => void }
const label = (value: string) => stripMinecraftFormatting(value)
const message = (reason: unknown) => reason instanceof Error ? reason.message : String(reason)
const emptyCatalog: ProgressionDesignCatalog = { classes: [], presets: [], items: [], sources: [], usages: [], addTargets: [] }
const INSPECTOR_DEFAULT = 350
const INSPECTOR_MIN = 290
const INSPECTOR_MAX = 680
type LevelAction = { kind: 'add' | 'change' | 'delete'; pointer: string; sourceLevel: number }

export function ProgressionDesignWorkspace({ active = true, onDirtyChange, onOpenMaster }: Props) {
  const [workspaceRoot, setWorkspaceRoot] = useState('')
  const [catalog, setCatalog] = useState<ProgressionDesignCatalog | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [previewError, setPreviewError] = useState('')
  const [previewing, setPreviewing] = useState(false)
  const [mode, setMode] = useState<'build' | 'item'>('build')
  const [classId, setClassId] = useState('')
  const [presetId, setPresetId] = useState('')
  const [level, setLevel] = useState(1)
  const [slot, setSlot] = useState('MAIN_HAND')
  const [browsedItemId, setBrowsedItemId] = useState('')
  const [focusedItemId, setFocusedItemId] = useState('')
  const [sourceId, setSourceId] = useState('')
  const [picker, setPicker] = useState<Picker | null>(null)
  const [invalid, setInvalid] = useState<Set<string>>(new Set())
  const [review, setReview] = useState<DesignSaveCandidate[] | null>(null)
  const [diff, setDiff] = useState<DesignSaveCandidate | null>(null)
  const [reviewedPaths, setReviewedPaths] = useState<Set<string>>(new Set())
  const [levelAction, setLevelAction] = useState<LevelAction | null>(null)
  const [levelInput, setLevelInput] = useState('')
  const [directLevel, setDirectLevel] = useState('')
  const [buildAction, setBuildAction] = useState<BuildAction | null>(null)
  const [operationNotice, setOperationNotice] = useState('')
  const [operationRefreshNeeded, setOperationRefreshNeeded] = useState(false)
  const [additionalOpen, setAdditionalOpen] = useState(false)
  const [usagePage, setUsagePage] = useState(0)
  const [previewRetry, setPreviewRetry] = useState(0)
  const [structuralPending, setStructuralPending] = useState(false)
  const [inspectorWidth, setInspectorWidth] = usePanelSize('progression-inspector', INSPECTOR_DEFAULT, INSPECTOR_MIN, INSPECTOR_MAX)
  const documents = useDesignDocuments(workspaceRoot, catalog?.revisions)
  const reloadSequence = useRef(0)
  const previewSequence = useRef(0)
  const previewAbort = useRef<AbortController | null>(null)
  const available = catalog ?? emptyCatalog
  const itemsById = useMemo(() => new Map(available.items.map(item => [item.id, item])), [available.items])
  const classes = available.classes.filter(entry => entry.id !== 'administrator')
  const currentClass = classes.some(entry => entry.id === classId) ? classId : classes[0]?.id ?? ''
  const presets = available.presets.filter(entry => entry.classId === currentClass)
  const preset = presets.find(entry => entry.id === presetId) ?? presets[0]
  const checkpoint = preset?.checkpoints.find(entry => entry.level === level) ?? preset?.checkpoints[0]
  const equipment = checkpoint?.equipment.find(entry => entry.slot === slot)
  const rootItemId = mode === 'item' ? browsedItemId : equipment?.itemId ?? ''
  const itemId = focusedItemId || rootItemId
  const item = itemsById.get(itemId)
  const sources = available.sources.filter(source => source.itemId === itemId)
  const selectedSource = available.sources.find(source => source.id === sourceId)
  const usages = available.usages.filter(usage => usage.itemId === itemId)
  const baseLock = loading || refreshing || documents.saving || Boolean(documents.recovery) || Boolean(review) || operationRefreshNeeded
  const lock = baseLock || previewing || structuralPending || !!documents.pending || !!previewError
  const inputBlocked = invalid.size > 0
  const fileOperationBlocked = lock || inputBlocked || documents.dirty
  const editStructure: typeof documents.edit = async (path, transform) => {
    if (lock) return false
    setStructuralPending(true)
    const success = await documents.edit(path, transform)
    if (!success) setStructuralPending(false)
    else setPreviewRetry(value => value + 1)
    return success
  }

  const refresh = useCallback(async (reload = false) => {
    const sequence = ++reloadSequence.current
    previewAbort.current?.abort(); previewSequence.current++
    setRefreshing(true); setLoadError(''); setPreviewError('')
    try {
      const [next, metadata] = await Promise.all([progressionDesignApi.catalog(reload), masterDataApi.catalog()])
      if (sequence !== reloadSequence.current) return false
      setWorkspaceRoot(metadata.root); setCatalog(next)
      setOperationRefreshNeeded(false)
      return true
    } catch (reason) { if (sequence === reloadSequence.current) setLoadError(message(reason)); return false }
    finally { if (sequence === reloadSequence.current) { setLoading(false); setRefreshing(false) } }
  }, [])
  useEffect(() => { void refresh(); return () => { reloadSequence.current++; previewAbort.current?.abort() } }, [refresh])
  const serializedDrafts = JSON.stringify(documents.dirtyEntries.map(entry => ({ path: entry.document.path, content: entry.content, revision: entry.document.revision })))
  useEffect(() => {
    if (loading || refreshing || !catalog) return
    const sequence = ++previewSequence.current
    const controller = new AbortController()
    previewAbort.current?.abort(); previewAbort.current = controller
    setPreviewing(true); setPreviewError('')
    const timer = setTimeout(() => {
      const entries = JSON.parse(serializedDrafts) as { path: string; content: import('../types/editor').JsonValue }[]
      const request = entries.length ? progressionDesignApi.preview(entries, controller.signal) : progressionDesignApi.catalog(false, controller.signal)
      void request.then(next => {
        if (sequence === previewSequence.current) setCatalog(next)
      }).catch(reason => { if (!controller.signal.aborted && sequence === previewSequence.current) setPreviewError(message(reason)) })
        .finally(() => { if (sequence === previewSequence.current) { setPreviewing(false); setStructuralPending(false) } })
    }, 180)
    return () => { clearTimeout(timer); controller.abort() }
    // Preview only follows document edits, never its own catalog result.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serializedDrafts, loading, refreshing, previewRetry])
  const previouslyActive = useRef(active)
  useEffect(() => {
    if (active && !previouslyActive.current && !documents.dirty && !documents.recovery) { documents.reset(); void refresh() }
    previouslyActive.current = active
  }, [active, documents, refresh])
  useEffect(() => { onDirtyChange?.(documents.dirty || inputBlocked || Boolean(documents.recovery)) }, [documents.dirty, inputBlocked, documents.recovery, onDirtyChange])
  const chooseTarget = (action: () => void) => {
    if (inputBlocked) { documents.setError('入力中の数値を修正してから対象を切り替えてください。'); return }
    if (lock || documents.pending) return
    setFocusedItemId(''); setSourceId(''); setUsagePage(0); action()
  }
  const openMaster = (path: string, pointer = '') => {
    if (inputBlocked || documents.pending) return
    if (documents.dirty) { documents.setError('詳細編集へ移る前に、この画面の変更を保存してください。'); return }
    onOpenMaster(path, pointer)
  }
  const prepareSave = async () => {
    if (lock || inputBlocked || previewing || previewError || documents.pending || !documents.dirty) return
    const candidates = await documents.prepare()
    if (candidates?.length) { setReviewedPaths(new Set()); setReview(candidates) }
  }
  const moveHistory = (direction: 'undo' | 'redo') => {
    if (lock || inputBlocked || (direction === 'undo' ? !documents.canUndo : !documents.canRedo)) return
    setStructuralPending(true)
    documents[direction]()
    setPreviewRetry(value => value + 1)
  }
  const shortcuts = useRef({ prepareSave, undo: () => moveHistory('undo'), redo: () => moveHistory('redo'), blocked: lock || inputBlocked || Boolean(picker) || levelAction !== null || buildAction !== null })
  shortcuts.current = { prepareSave, undo: () => moveHistory('undo'), redo: () => moveHistory('redo'), blocked: lock || inputBlocked || Boolean(picker) || levelAction !== null || buildAction !== null }
  useEffect(() => {
    if (!active) return
    const keydown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.isComposing || event.target instanceof HTMLElement && event.target.closest('[role="dialog"]')) return
      const key = event.key.toLowerCase()
      if (key === 's') { event.preventDefault(); if (!shortcuts.current.blocked) void shortcuts.current.prepareSave() }
      if (event.target instanceof HTMLElement && event.target.matches('input,textarea,select,[contenteditable="true"]')) return
      if (shortcuts.current.blocked) return
      if (key === 'z') { event.preventDefault(); (event.shiftKey ? shortcuts.current.redo : shortcuts.current.undo)() }
      if (key === 'y') { event.preventDefault(); shortcuts.current.redo() }
    }
    window.addEventListener('keydown', keydown)
    return () => window.removeEventListener('keydown', keydown)
  }, [active])
  const fieldInvalid = useCallback((key: string, bad: boolean) => setInvalid(previous => {
    if (previous.has(key) === bad) return previous
    const next = new Set(previous); if (bad) next.add(key); else next.delete(key); return next
  }), [])
  const field = (entry: DesignField, title: string, max?: number) => <DesignNumber key={`${entry.path}:${entry.pointer}`} field={entry} title={title} max={max} disabled={baseLock || structuralPending || !!previewError}
    onInvalid={fieldInvalid} onChange={value => documents.edit(entry.path, content => checkpoint && entry.pointer === `${checkpoint.pointer}/classLevel` && typeof value === 'number' ? setAccountClassLevel(content, checkpoint, value) : setDesignValue(content, entry.pointer, value))} />
  const sourceChoices: MasterEntityPickerChoice[] = available.addTargets.map(target => ({ ...target, id: `${target.path}#${target.pointer}`, name: `${target.name}${target.kind === 'mob' ? target.pointer.startsWith('/levels/') ? ` · Lv.${target.level}` : ' · 基本ドロップ' : ''}`, category: sourceLabels[target.kind], description: target.path }))
  const categoryNames: Record<string, string> = { material: '素材', equipment: '装備・アクセサリー', consumable: '消耗品', currency: '通貨', orb: '強化素材', sigil: 'シジル', rune: 'ルーン', pet: 'ペット', pet_egg: 'ペットの卵', bundle: '詰め合わせ' }
  const itemChoices: MasterEntityPickerChoice[] = available.items.filter(entry => picker?.kind !== 'equipment' || entry.category === 'equipment' || entry.compatibleSlots.length > 0).map(entry => ({ ...entry, category: entry.slot ? equipmentSlots.find(([value]) => entry.compatibleSlots.includes(value))?.[1] ?? categoryNames[entry.category ?? ''] ?? 'アイテム' : categoryNames[entry.category ?? ''] ?? 'アイテム',
    description: [entry.requiredLevel ? `必要Lv.${entry.requiredLevel}` : 'レベル制限なし', entry.requiredClasses.length ? '職業条件あり' : '', entry.rarity ?? ''].filter(Boolean).join(' · '),
    disabledReason: picker?.kind === 'equipment' && preset && checkpoint ? equipmentRestriction(entry, picker.slot, preset, checkpoint, available)
      : picker?.kind === 'material' && (picker.ingredient?.path ?? picker.source.ingredientContainer?.path)?.startsWith('85.shared.recipe/') && entry.category !== 'material' ? 'レシピの素材には素材カテゴリを選択してください' : undefined }))
  const pick = async (id: string) => {
    if (!picker || lock) return
    const target = picker
    if (target.kind === 'browse') { chooseTarget(() => { setMode('item'); setBrowsedItemId(id) }); setPicker(null); return }
    let success = false
    if (target.kind === 'equipment' && preset && checkpoint) {
      const selected = itemsById.get(id)
      if (!selected || equipmentRestriction(selected, target.slot, preset, checkpoint, available)) return
      success = await editStructure(preset.path, content => changeEquipment(content, checkpoint, target.slot, id, selected.maxEnhanceLevel))
      if (success) { setSlot(target.slot); setFocusedItemId(''); setSourceId('') }
    } else if (target.kind === 'material') {
      const selected = itemsById.get(id)
      const location = target.ingredient ?? target.source.ingredientContainer
      if (!location || !selected) return
      success = await editStructure(location.path, content => {
        if (!target.ingredient) return appendDesignEntry(content, location.pointer, { itemId: { ref: `item:${id}` }, amount: 1, ...(location.pointer.endsWith('/requiredItems') ? { category: selected.category ?? 'material' } : {}) })
        const previous = valueAtPointer(content, target.ingredient.itemPointer)
        if (String(previous).replace(/^item:/, '') !== target.ingredient.itemId) throw new Error('素材一覧が変更されています。図の更新を待って選び直してください。')
        const replaced = setDesignValue(content, target.ingredient.itemPointer, `item:${id}`)
        return /\/requiredItems\/\d+$/.test(target.ingredient.pointer) ? setDesignValue(replaced, `${target.ingredient.pointer}/category`, selected.category ?? 'material') : replaced
      })
    } else if (target.kind === 'source') {
      const location = available.addTargets.find(entry => `${entry.path}#${entry.pointer}` === id)
      if (!location) return
      success = await editStructure(location.path, content => {
        let next = content
        const profile = parentPointer(location.pointer)
        if (/^\/levels\/\d+\/drops\/items$/.test(location.pointer) && valueAtPointer(content, location.pointer) === undefined && valueAtPointer(content, profile) !== null) {
          const inherited = valueAtPointer(content, '/drops/items')
          if (Array.isArray(inherited)) next = setDesignValue(content, location.pointer, structuredClone(inherited))
        }
        return appendDesignEntry(next, location.pointer, { itemId: `item:${target.itemId}`, rate: 100, amount: 1, ...(location.kind !== 'dungeon' ? { luckAffected: true, hidden: false } : {}) })
      })
    }
    if (success) setPicker(null)
  }
  const removeIngredient = async (source: DesignSource, ingredient: DesignIngredient) => {
    const name = itemsById.get(ingredient.itemId)?.name ?? ingredient.itemId
    if (!window.confirm(`${label(name)}を「${label(source.name)}」の必要素材から外しますか？ 保存前は元に戻せます。`)) return
    await editStructure(ingredient.path, content => {
      const parent = parentPointer(ingredient.pointer)
      const entries = valueAtPointer(content, parent)
      if (!Array.isArray(entries)) throw new Error('素材一覧を特定できません。詳細編集で確認してください。')
      const index = Number(ingredient.pointer.split('/').at(-1))
      if (String(valueAtPointer(content, ingredient.itemPointer)).replace(/^item:/, '') !== ingredient.itemId) throw new Error('素材一覧が変更されています。図を更新してから選び直してください。')
      return setDesignValue(content, parent, entries.filter((_, i) => i !== index))
    })
  }
  const exportDrafts = () => {
    const blob = new Blob([JSON.stringify(documents.dirtyEntries, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'equipment-design-drafts.json'; anchor.click(); URL.revokeObjectURL(url)
  }
  const removeSource = async (source: DesignSource) => {
    if (lock || inputBlocked || source.path !== source.itemPath || !/\/items\/\d+$/.test(source.pointer)) return
    if (!window.confirm(`${label(source.name)}から、このアイテムの報酬設定を外しますか？ 同じ基本ドロップを継承するレベルにも反映されます。保存前は元に戻せます。`)) return
    await editStructure(source.path, content => {
      if (String(valueAtPointer(content, source.itemPointer)).replace(/^item:/, '') !== source.itemId) throw new Error('報酬一覧が変わっています。再取得してください。')
      const parent = parentPointer(source.pointer)
      const array = valueAtPointer(content, parent)
      if (!Array.isArray(array)) throw new Error('報酬一覧を取得できません。')
      return setDesignValue(content, parent, array.filter((_, index) => index !== Number(source.pointer.split('/').at(-1))))
    })
    setSourceId('')
  }
  const inspectItem = (next: string) => chooseTarget(() => setFocusedItemId(next))
  const selectSource = (id: string) => {
    const source = available.sources.find(entry => entry.id === id)
    if (!source) return
    chooseTarget(() => { setFocusedItemId(source.itemId); setSourceId(id) })
  }
  const startLevelAction = (kind: LevelAction['kind'], initial = checkpoint?.level ?? 1) => {
    if (!preset || !checkpoint || lock || inputBlocked) return
    setLevelAction({ kind, pointer: checkpoint.pointer, sourceLevel: checkpoint.level })
    setLevelInput(kind === 'add' ? String(initial) : String(checkpoint.level))
  }
  const applyLevelAction = async () => {
    if (!preset || !levelAction) return
    const next = Number(levelInput)
    const result = await editStructure(preset.path, content => levelAction.kind === 'add'
      ? addLevelStage(content, levelAction.pointer, levelAction.sourceLevel, next)
      : levelAction.kind === 'change'
        ? changeLevelStage(content, levelAction.pointer, levelAction.sourceLevel, next)
        : removeLevelStage(content, levelAction.pointer, levelAction.sourceLevel))
    if (result) {
      setLevel(levelAction.kind === 'delete' ? 1 : next)
      setDirectLevel('')
      setLevelAction(null)
    }
  }
  const selectDirectLevel = () => {
    if (!/^\d+$/.test(directLevel)) return
    const next = Number(directLevel)
    if (next < 1 || next > 50) return
    if (preset?.checkpoints.some(entry => entry.level === next)) chooseTarget(() => { setLevel(next); setDirectLevel('') })
    else startLevelAction('add', next)
  }
  const committedBuild = (kind: 'created' | 'deleted', targetClass: string, targetId?: string) => {
    setBuildAction(null)
    documents.reset()
    setClassId(targetClass); setPresetId(targetId ?? ''); setLevel(1)
    setOperationNotice(kind === 'created' ? 'ビルドを登録しました。' : 'ビルドを削除しました。')
    setOperationRefreshNeeded(true)
    void refresh(true)
  }
  const gearCard = ([id, name]: readonly [string, string]) => {
    const assigned = checkpoint?.equipment.find(entry => entry.slot === id)
    const gear = assigned && itemsById.get(assigned.itemId)
    return <article className={`design-equipment-card ${slot === id ? 'selected' : ''}`} key={id}>
      <button className="design-equipment-view" aria-pressed={slot === id} onClick={() => chooseTarget(() => setSlot(id))} disabled={lock}>
        <span className="design-slot-label">{name}{assigned && assigned.enhanceLevel > 0 && <b>+{assigned.enhanceLevel}</b>}</span>
        {gear ? <MinecraftIcon icon={gear.icon} customModelData={gear.customModelData} iconTexture={gear.iconTexture} /> : <span className="design-slot-placeholder">＋</span>}
        <strong>{gear ? <MinecraftText value={gear.name} /> : assigned ? assigned.itemId : '未設定'}</strong>
        <small>{gear ? `必要Lv.${gear.requiredLevel}` : '一覧から装備を選択'}</small>
      </button>
      <button className="design-equipment-change" disabled={lock || inputBlocked || !checkpoint} onClick={() => setPicker({ kind: 'equipment', slot: id })}>{assigned ? '装備を変更' : '装備を設定'} <span>▦</span></button>
    </article>
  }

  return <section className="progression-design" aria-label="装備・入手経路の設計">
    <header className="design-header">
      <div><span className="design-eyebrow">BUILD & REWARDS</span><h1>装備・入手経路</h1><p>ビルドの装備とアカウント進行、素材と入手先をまとめて設計</p></div>
      <div className="design-toolbar">
        <span className={`design-save-state ${documents.dirty ? 'dirty' : ''}`}>{documents.dirty ? `${documents.dirtyEntries.length}ファイル 未保存` : '保存済み'}</span>
        <button className="button" disabled={lock || inputBlocked || !documents.canUndo || !!documents.pending} onClick={() => moveHistory('undo')} title="Ctrl+Z">元に戻す</button>
        <button className="button" disabled={lock || inputBlocked || !documents.canRedo || !!documents.pending} onClick={() => moveHistory('redo')} title="Ctrl+Y">やり直す</button>
        <button className="button" disabled={baseLock || structuralPending || !!documents.pending} onClick={() => {
          if ((documents.dirty || inputBlocked) && !window.confirm('この画面の未保存の変更を破棄して、ファイルを再読込しますか？')) return
          setInvalid(new Set()); setSourceId(''); setFocusedItemId('')
          documents.reset(); void refresh(true)
        }}>{refreshing ? '再読込中…' : '再読込'}</button>
        <button className="button primary" disabled={lock || inputBlocked || !!documents.pending || previewing || !!previewError || !documents.dirty} onClick={() => void prepareSave()}>{documents.saving ? '検証中…' : '変更を確認して保存'}</button>
      </div>
    </header>
    {(loadError || documents.error || previewError) && <div className="design-message error" role="alert"><span>{loadError || documents.error || `図を更新できませんでした。${previewError}`}</span>{loadError ? <button className="button" onClick={() => void refresh()}>再試行</button> : <button className="button" disabled={baseLock} onClick={() => { documents.setError(''); setPreviewRetry(value => value + 1) }}>下書きを保持して図を再取得</button>}{documents.dirty && <button className="button" onClick={exportDrafts}>下書きを書き出す</button>}</div>}
    {operationNotice && <div className="design-message" role="status"><span>{operationNotice}{operationRefreshNeeded && ' 一覧を更新してから編集を再開できます。'}</span>{operationRefreshNeeded && <button className="button" disabled={refreshing} onClick={() => void refresh(true)}>一覧を再取得</button>}</div>}
    {documents.notice && <div className="design-message" role="status">{documents.notice}</div>}
    {documents.recovery && <div className="design-message"><span>前回の未保存の下書きがあります。</span><button className="button primary" onClick={() => { setStructuralPending(true); documents.restore(); setPreviewRetry(value => value + 1) }}>下書きを復元</button><button className="button" onClick={documents.discardRecovery}>下書きを破棄</button></div>}
    {loading ? <div className="design-empty">装備・ビルド・入手経路を読み込んでいます…</div> : <>
      <section className="design-setup">
        <div className="design-mode" role="group" aria-label="設計の入口"><button className={mode === 'build' ? 'active' : ''} aria-pressed={mode === 'build'} onClick={() => chooseTarget(() => setMode('build'))}>ビルドの装備</button><button className={mode === 'item' ? 'active' : ''} aria-pressed={mode === 'item'} onClick={() => chooseTarget(() => setMode('item'))}>アイテムから探す</button></div>
        {mode === 'build' ? <>
          <div className="design-selectors">
            <label><span>01 職業</span><select aria-label="職業" disabled={lock || inputBlocked} value={currentClass} onChange={event => chooseTarget(() => { setClassId(event.target.value); setPresetId('') })}>{classes.map(entry => <option value={entry.id} key={entry.id}>{label(entry.name)}</option>)}</select></label>
            <label className="design-build-select"><span>02 ビルド</span><select aria-label="ビルド" disabled={lock || inputBlocked || !preset} value={preset?.id ?? ''} onChange={event => chooseTarget(() => setPresetId(event.target.value))}>{presets.length ? presets.map(entry => <option value={entry.id} key={entry.id}>{label(entry.name)}</option>) : <option value="">ビルド未登録</option>}</select></label>
            <button className="button" disabled={fileOperationBlocked || !currentClass} title={fileOperationBlocked ? '未保存の変更を保存または破棄してから操作してください' : ''} onClick={() => setBuildAction('create')}>＋ 新規ビルド</button>
            <button className="button" disabled={fileOperationBlocked || !preset} title={fileOperationBlocked ? '未保存の変更を保存または破棄してから操作してください' : ''} onClick={() => setBuildAction('duplicate')}>複製</button>
            <button className="button" disabled={lock || inputBlocked || !preset} onClick={() => setBuildAction('edit')}>名前・IDを編集</button>
            <button className="button" disabled={fileOperationBlocked || !preset} title={fileOperationBlocked ? '未保存の変更を保存または破棄してから操作してください' : ''} onClick={() => setBuildAction('delete')}>削除</button>
            <label><span>03 適用開始レベル</span><select aria-label="適用開始レベル" disabled={lock || inputBlocked || !checkpoint} value={checkpoint?.level ?? ''} onChange={event => chooseTarget(() => { setLevel(Number(event.target.value)); setDirectLevel('') })}>{preset?.checkpoints.map(entry => <option value={entry.level} key={entry.pointer}>Lv.{entry.level}</option>)}</select></label>
            <label className="design-direct-level"><span>Lvを直接入力</span><input type="number" min="1" max="50" aria-label="レベルを直接入力" value={directLevel} disabled={lock || inputBlocked || !checkpoint} onChange={event => setDirectLevel(event.target.value)} onKeyDown={event => { if (event.key === 'Enter') selectDirectLevel() }} /></label>
            <button className="button" disabled={lock || inputBlocked || !/^\d+$/.test(directLevel) || Number(directLevel) < 1 || Number(directLevel) > 50} onClick={selectDirectLevel}>移動・追加</button>
            <button className="button" disabled={lock || inputBlocked || !preset || !checkpoint || preset.checkpoints.length >= 50} onClick={() => startLevelAction('add', Math.min(50, (checkpoint?.level ?? 1) + 1))}>＋ 段階を複製</button>
            <button className="button" disabled={lock || inputBlocked || !checkpoint || checkpoint.level === 1} onClick={() => startLevelAction('change')}>Lvを変更</button>
            <button className="button" disabled={lock || inputBlocked || !checkpoint || checkpoint.level === 1} onClick={() => startLevelAction('delete')}>段階を削除</button>
            {preset && <button className="text-button" disabled={documents.dirty || lock} onClick={() => openMaster(preset.path, checkpoint?.pointer)}>ビルドの詳細 ↗</button>}
          </div>
          <p className="design-caption">Lv.1は必須です。段階はLv.1〜50の間で重複なく登録します。新規登録・複製・削除はファイルへすぐ反映されます。</p>
          {documents.dirty && <p className="design-caption">ビルドの登録・複製・削除は、未保存の変更を保存または破棄してから行えます。</p>}
          <div className="design-equipment" role="group" aria-label="部位別の装備">{equipmentSlots.slice(0, 6).map(gearCard)}</div>
          {equipmentSlots.length > 6 && <details className="design-additional-equipment" open={additionalOpen} onToggle={event => setAdditionalOpen(event.currentTarget.open)}><summary>スキルブック・アクセサリ {checkpoint?.equipment.filter(entry => equipmentSlots.slice(6).some(([id]) => id === entry.slot)).length ?? 0}/{equipmentSlots.length - 6}件設定済み</summary><div className="design-equipment" role="group" aria-label="スキルブック・アクセサリ">{equipmentSlots.slice(6).map(gearCard)}</div></details>}
          <p className="design-caption">進行プリセットの装備を編集します。必要レベルは装備条件、適用開始レベルは構成の切替時点です。</p>
        </> : <div className="design-item-entry"><div><strong>{itemsById.get(browsedItemId)?.name ? label(itemsById.get(browsedItemId)!.name) : '調べたいアイテムを選択'}</strong><p>装備・素材・ダンジョン報酬の入手先と使い道を確認できます。</p></div><button className="button primary" disabled={lock || inputBlocked} onClick={() => setPicker({ kind: 'browse' })}>▦ アイコン付き一覧から選択</button></div>}
      </section>
      {mode === 'build' && preset && checkpoint && <AccountProgressionPanel catalog={available} preset={preset} checkpoint={checkpoint} disabled={lock || inputBlocked} renderNumber={field} edit={editStructure} onExploreItem={id => chooseTarget(() => setFocusedItemId(id))} />}
      <div className="design-body">
        <section className="design-canvas-panel" aria-label="入手経路の図">
          <div className="design-preview-status" role="status">{previewing || documents.pending ? '下書きを図に反映中…' : 'カードを選んで、右の詳細で編集できます'}</div>
          <AcquisitionGraph catalog={catalog} itemId={rootItemId} selectedSourceId={sourceId} loading={loading} onSelectItem={inspectItem} onSelectSource={selectSource} />
          <p className="design-graph-note">線はマスター上の関係です。実ワールドへの配置状況は「ワールド配置・編集」で確認できます。</p>
        </section>
        <ResizeHandle label="入手経路の詳細の幅を変更" size={inspectorWidth} min={INSPECTOR_MIN} max={INSPECTOR_MAX} defaultSize={INSPECTOR_DEFAULT} minRemaining={320} direction={-1} className="design-body-resizer" onResize={setInspectorWidth} />
        <aside className="design-inspector" aria-label="選択した項目の詳細" style={{ flexBasis: inspectorWidth }}>
          {item ? <>
            <header className="design-item-heading"><MinecraftIcon icon={item.icon} customModelData={item.customModelData} iconTexture={item.iconTexture} /><div><small>{focusedItemId && focusedItemId !== rootItemId ? '選択した素材・アイテム' : '設計対象のアイテム'}</small><h2><MinecraftText value={item.name} /></h2><code>{item.id}</code></div></header>
            {focusedItemId && focusedItemId !== rootItemId && <div className="design-item-actions"><button className="text-button" onClick={() => chooseTarget(() => {})}>← 装備の詳細に戻る</button><button className="button" disabled={lock || inputBlocked} onClick={() => chooseTarget(() => { setMode('item'); setBrowsedItemId(itemId) })}>この素材を起点に図を見る</button></div>}
            <div className="design-item-actions"><button className="button" disabled={lock || documents.dirty || inputBlocked} onClick={() => openMaster(item.path)}>性能・定義を編集 ↗</button><button className="button" disabled={lock || inputBlocked} onClick={() => setPicker({ kind: 'source', itemId })}>＋ 入手先を設定</button></div>
            {mode === 'build' && itemId === rootItemId && equipment && checkpoint && preset && <div className="design-equipment-settings">
              {field({ path: preset.path, pointer: `${equipment.pointer}/enhanceLevel`, value: equipment.enhanceLevel }, '強化値')}
              {item.requiredLevel > checkpoint.level && <p className="design-warning">装備の必要Lv.{item.requiredLevel}が、この構成のLv.{checkpoint.level}を上回っています。</p>}
              {slot !== 'MAIN_HAND' && <button className="text-button" disabled={lock || inputBlocked} onClick={() => { void editStructure(preset.path, content => changeEquipment(content, checkpoint, slot, null)) }}>この枠を空にする</button>}
            </div>}
            <div className="design-section-heading"><h3>入手先</h3><span>{sources.length}経路</span></div>
            {!sources.length && <div className="design-warning">入手先が登録されていません。「入手先を設定」からモブ・採集・ダンジョンへ配置できます。</div>}
            <div className="design-source-list">{sources.map(source => <button key={source.id} className={`design-source-option ${sourceId === source.id ? 'selected' : ''}`} onClick={() => selectSource(source.id)} disabled={lock || inputBlocked}>
              <span>{sourceLabels[source.kind] ?? source.kind}</span><strong>{label(source.name)}</strong><small>{source.availability === 'definitionOnly' ? '定義のみ・入手可否は未確認' : source.level ? `設定Lv.${source.level}` : source.mode ?? '設定済みの経路'}</small>
            </button>)}</div>
            {selectedSource && <section className="design-source-editor" aria-label="入手先の設定">
              <div className="design-section-heading"><h3>{label(selectedSource.name)}</h3><button className="text-button" disabled={lock || documents.dirty} onClick={() => openMaster(selectedSource.path, selectedSource.pointer)}>詳細 ↗</button></div>
              {selectedSource.warnings.map((warning, index) => <p className="design-warning" key={index}>{warning}</p>)}
              <div className="design-number-grid">{selectedSource.quantity.map(entry => field(entry, entry.pointer.endsWith('/max') ? '個数の上限' : entry.pointer.endsWith('/min') ? '個数の下限' : '獲得数'))}{selectedSource.rates.map(entry => field(entry, entry.pointer.endsWith('/rolls') ? '抽選回数' : entry.pointer.endsWith('/pick') ? '抽選する件数' : '設定確率（%）'))}{selectedSource.costs.map(entry => field(entry, '通貨コスト'))}</div>
              {selectedSource.rates.length > 0 && <p className="design-caption">抽選・幸運補正前の設定値です。共有報酬の変更は、同じ定義を使う他の入手先にも反映されます。</p>}
              {(selectedSource.ingredients.length > 0 || selectedSource.ingredientContainer) && <>
                <div className="design-section-heading"><h3>必要素材</h3>{selectedSource.ingredientContainer && <button className="button compact" disabled={lock || inputBlocked} onClick={() => setPicker({ kind: 'material', source: selectedSource })}>＋ 素材</button>}</div>
                {selectedSource.ingredients.map((ingredient, index) => <article className="design-material" key={`${ingredient.path}:${ingredient.pointer}`}>
                  <button className="design-material-identity" disabled={lock || inputBlocked} onClick={() => inspectItem(ingredient.itemId)}><ItemIdentity item={itemsById.get(ingredient.itemId)} fallback={ingredient.itemId} /></button>
                  <div className="design-number-grid">{ingredient.quantity.map(entry => field(entry, '必要数'))}</div>
                  <div className="design-material-actions"><button className="text-button" disabled={lock || inputBlocked} onClick={() => setPicker({ kind: 'material', source: selectedSource, ingredient })}>素材を変更</button><button className="text-button" aria-label={`素材${index + 1}を外す`} disabled={lock || inputBlocked} onClick={() => void removeIngredient(selectedSource, ingredient)}>外す</button></div>
                </article>)}
              </>}
              {selectedSource.context.length > 0 && <details className="design-related"><summary>関連する定義・配置先 {selectedSource.context.length}件</summary>{selectedSource.context.map((entry, index) => <button key={index} className="text-button" disabled={lock || documents.dirty} onClick={() => openMaster(entry.path, entry.pointer)}>{label(entry.name)} ↗</button>)}</details>}
              {['mob', 'gathering', 'dungeon'].includes(selectedSource.kind) && selectedSource.path === selectedSource.itemPath && /\/items\/\d+$/.test(selectedSource.pointer) && <button className="text-button design-remove-source" disabled={lock || inputBlocked} onClick={() => void removeSource(selectedSource)}>この入手先から報酬を外す</button>}
            </section>}
            <div className="design-section-heading"><h3>このアイテムの使い道</h3><span>{usages.length}件</span></div>
            {!usages.length && <p className="design-caption">関連する素材消費・装備設定が見つかりません。</p>}
            <ul className="design-usages">{usages.slice(usagePage * 8, usagePage * 8 + 8).map((usage, index) => <li key={index}><small>{sourceLabels[usage.kind] ?? usage.kind}</small><button className="text-button" disabled={lock || inputBlocked || (!usage.outputItemId && documents.dirty)} onClick={() => usage.outputItemId ? chooseTarget(() => { setMode('item'); setBrowsedItemId(usage.outputItemId!) }) : openMaster(usage.path, usage.pointer)}>{label(usage.name)} {usage.outputItemId ? '→' : '↗'}</button></li>)}</ul>
            {usages.length > 8 && <div className="design-item-actions"><button className="button" disabled={usagePage === 0} onClick={() => setUsagePage(page => page - 1)}>前へ</button><span className="design-caption">{usagePage + 1} / {Math.ceil(usages.length / 8)}</span><button className="button" disabled={(usagePage + 1) * 8 >= usages.length} onClick={() => setUsagePage(page => page + 1)}>次へ</button></div>}
          </> : <div className="design-empty"><strong>設計対象を選んでください</strong><p>装備枠かアイテムを選択すると、入手経路と使い道が表示されます。</p></div>}
        </aside>
      </div>
    </>}
    {picker && <MasterEntityPicker title={picker.kind === 'equipment' ? `${equipmentSlots.find(([id]) => id === picker.slot)?.[1]}を選択` : picker.kind === 'source' ? '入手先を選択' : picker.kind === 'material' ? '必要素材を選択' : 'アイテムを選択'}
      choices={picker.kind === 'source' ? sourceChoices : itemChoices} value={picker.kind === 'equipment' ? checkpoint?.equipment.find(entry => entry.slot === picker.slot)?.itemId : picker.kind === 'material' ? picker.ingredient?.itemId : browsedItemId}
      loading={!!documents.pending} error={documents.error} onSelect={id => void pick(id)} onClose={() => { if (!documents.pending) setPicker(null) }} />}
    {levelAction && preset && <Modal ariaLabel={levelAction.kind === 'add' ? 'レベル段階を追加' : levelAction.kind === 'change' ? 'レベル段階を変更' : 'レベル段階を削除'} onClose={() => setLevelAction(null)} closeDisabled={!!documents.pending || structuralPending}>
      <header className="modal-header"><h2>{levelAction.kind === 'add' ? 'レベル段階を追加' : levelAction.kind === 'change' ? 'レベル段階の数値を変更' : 'レベル段階を削除'}</h2></header>
      <p>{levelAction.kind === 'add' ? `Lv.${levelAction.sourceLevel}の装備・スキル・ノードを複製します。` : levelAction.kind === 'change' ? `Lv.${levelAction.sourceLevel}の構成を別の適用開始レベルへ移します。` : `「${label(preset.name)}」のLv.${levelAction.sourceLevel}の段階を削除します。保存前なら元に戻せます。`}</p>
      {levelAction.kind !== 'delete' && <label className="design-dialog-label">適用開始レベル<input type="number" min="1" max="50" value={levelInput} onChange={event => setLevelInput(event.target.value)} /></label>}
      {levelAction.kind !== 'delete' && preset.checkpoints.some(entry => entry.level === Number(levelInput) && entry.level !== levelAction.sourceLevel) && <p className="design-warning">そのレベルは登録済みです。</p>}
      <footer className="modal-footer"><button className="button" disabled={!!documents.pending || structuralPending} onClick={() => setLevelAction(null)}>キャンセル</button><button className="button primary" disabled={!!documents.pending || structuralPending || levelAction.kind !== 'delete' && (!/^\d+$/.test(levelInput) || Number(levelInput) < 1 || Number(levelInput) > 50 || preset.checkpoints.some(entry => entry.level === Number(levelInput)))} onClick={() => void applyLevelAction()}>{levelAction.kind === 'delete' ? 'この段階を削除' : levelAction.kind === 'change' ? 'レベルを変更' : '段階を追加'}</button></footer>
    </Modal>}
    {buildAction && classes.find(entry => entry.id === currentClass) && <BuildManagementDialog action={buildAction} catalog={available} selectedClass={classes.find(entry => entry.id === currentClass)!} preset={preset} onClose={() => setBuildAction(null)} onEdit={(name, nextBuildId) => preset ? editStructure(preset.path, content => changeBuildIdentity(content, name, nextBuildId)) : Promise.resolve(false)} onCommitted={committedBuild} />}
    {review && <Modal ariaLabel="装備・進行設計の変更を確認" className="design-save-dialog" onClose={() => setReview(null)} closeDisabled={documents.saving}>
      <header className="modal-header"><div><h2>変更を確認して保存</h2><p>{review.length}件のファイルを検証しました。ファイルごとに保存します。</p></div></header>
      <div className="design-save-list">{review.map(candidate => <article key={candidate.path}><strong>{candidate.path}</strong>{candidate.warnings.map((warning, index) => <p className="design-warning" key={index}>{warning}</p>)}<button className="button" disabled={documents.saving} onClick={() => setDiff(candidate)}>差分を確認</button></article>)}</div>
      {documents.error && <p className="design-warning" role="alert">{documents.error}</p>}
      {review.some(candidate => candidate.warnings.length && !reviewedPaths.has(candidate.path)) && <p className="design-warning">警告のあるファイルは、差分を開いて確認してから保存できます。</p>}
      <footer className="modal-footer"><button className="button" disabled={documents.saving} onClick={() => setReview(null)}>編集に戻る</button><button className="button primary" disabled={documents.saving || review.some(candidate => candidate.warnings.length && !reviewedPaths.has(candidate.path))} onClick={async () => { const success = await documents.save(review); if (success) { setReview(null); void refresh() } else setReview(null) }}>{documents.saving ? '保存中…' : `${review.length}件を保存`}</button></footer>
    </Modal>}
    {diff && <MasterDataDiff before={diff.before} after={diff.raw} onClose={() => { setReviewedPaths(paths => new Set([...paths, diff.path])); setDiff(null) }} />}
  </section>
}

function ItemIdentity({ item, fallback }: { item?: DesignItem; fallback: string }) {
  return <>{item && <MinecraftIcon icon={item.icon} customModelData={item.customModelData} iconTexture={item.iconTexture} />}<span><strong>{item ? <MinecraftText value={item.name} /> : fallback}</strong><small>{item?.id ?? fallback}</small></span></>
}

function DesignNumber({ field, title, max, disabled, onChange, onInvalid }: { field: DesignField; title: string; max?: number; disabled: boolean; onChange: (value: number | string) => Promise<boolean>; onInvalid: (key: string, bad: boolean) => void }) {
  const [text, setText] = useState(String(field.value))
  const [failed, setFailed] = useState(false)
  const editSequence = useRef(0)
  const focused = useRef(false)
  const name = field.pointer.split('/').at(-1) ?? ''
  const key = `${field.path}:${field.pointer}`
  const parsed = numericDesignValue(text, name, field)
  const value = max && typeof parsed === 'number' && parsed > max ? undefined : parsed
  useEffect(() => { if (!focused.current) setText(String(field.value)) }, [field.value])
  useEffect(() => { onInvalid(key, value === undefined || failed); return () => onInvalid(key, false) }, [key, value, failed, onInvalid])
  return <label className="design-number"><span>{title}</span><input inputMode="decimal" value={text} disabled={disabled} aria-label={`${title} ${field.pointer}`} aria-invalid={value === undefined || failed} title={field.path} max={max}
    onFocus={() => { focused.current = true }} onBlur={() => { focused.current = false }} onKeyDown={event => { if (event.key === 'Escape') { editSequence.current++; setText(String(field.value)); setFailed(false) } }}
    onChange={event => { const next = event.target.value; setText(next); setFailed(false); const sequence = ++editSequence.current; const candidate = numericDesignValue(next, name, field); if (candidate !== undefined && (!max || typeof candidate !== 'number' || candidate <= max)) void onChange(candidate).then(success => { if (sequence === editSequence.current) setFailed(!success) }) }} />{value === undefined && <small>{max ? `1〜${max}の整数を入力してください（Escで戻す）` : '有効な数値を入力してください（Escで戻す）'}</small>}{failed && <small>反映できませんでした。再入力するかEscで戻してください。</small>}</label>
}
