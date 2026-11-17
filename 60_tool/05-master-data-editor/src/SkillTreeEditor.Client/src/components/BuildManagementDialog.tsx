import { useEffect, useMemo, useRef, useState } from 'react'
import { masterDataApi } from '../api/masterDataApi'
import { accountPermittedSkillIds } from '../data/progressionAccount'
import { equipmentRestriction } from '../data/progressionDesign'
import { clonePreset, createBlankPreset, presetPath, validPresetId } from '../data/progressionBuildManagement'
import type { JsonValue } from '../types/editor'
import type { DesignClass, DesignPreset, ProgressionDesignCatalog } from '../types/progressionDesign'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MasterEntityPicker, type MasterEntityPickerChoice } from './MasterEntityPicker'
import { MinecraftIcon } from './MinecraftIcon'
import { Modal } from './Modal'
import { ProgressionSkillTreePicker } from './ProgressionSkillTreePicker'

export type BuildAction = 'create' | 'duplicate' | 'edit' | 'delete'
interface Props {
  action: BuildAction
  catalog: ProgressionDesignCatalog
  selectedClass: DesignClass
  preset?: DesignPreset
  onClose: () => void
  onDirtyChange?: (dirty: boolean) => void
  onEdit: (name: string, buildId: string) => Promise<boolean>
  onCommitted: (kind: 'created' | 'deleted', classId: string, presetId?: string) => void
}
const plain = stripMinecraftFormatting
const errorText = (reason: unknown) => reason instanceof Error ? reason.message : String(reason)
type Prepared = { path: string; raw?: string; revision?: string; warnings: string[] }

export function BuildManagementDialog({ action, catalog, selectedClass, preset, onClose, onDirtyChange, onEdit, onCommitted }: Props) {
  const [classId, setClassId] = useState(selectedClass.id)
  const [classLevel, setClassLevel] = useState(1)
  const [name, setName] = useState(action === 'edit' ? preset?.name ?? '' : action === 'duplicate' ? `${plain(preset?.name ?? '')} のコピー` : '')
  const [buildId, setBuildId] = useState(action === 'edit' ? preset?.buildId ?? '' : action === 'duplicate' ? `${preset?.buildId ?? 'build'}_copy` : '')
  const [internalId, setInternalId] = useState('')
  const [mainHandId, setMainHandId] = useState('')
  const [skillbookId, setSkillbookId] = useState('')
  const [nodeIds, setNodeIds] = useState<number[]>([])
  const [skillId, setSkillId] = useState('')
  const [picker, setPicker] = useState<'weapon' | 'book' | 'skill' | null>(null)
  const [treeOpen, setTreeOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [prepared, setPrepared] = useState<Prepared | null>(null)
  const id = internalId || `${classId}_${buildId}`
  const path = action === 'edit' || action === 'delete' ? preset?.path ?? '' : presetPath(id)
  const starter = useMemo(() => ({ id, name, path, classId, buildId, checkpoints: [{ level: 1, classLevel, pointer: '/levels/0',
    equipment: [...(mainHandId ? [{ slot: 'MAIN_HAND', itemId: mainHandId, pointer: '/levels/0/equipment/0', enhanceLevel: 0 }] : []),
      ...(skillbookId ? [{ slot: 'SKILLBOOK', itemId: skillbookId, pointer: '/levels/0/equipment/1', enhanceLevel: 0 }] : [])],
    nodes: nodeIds, skills: [], passives: [] }] } satisfies DesignPreset), [id, name, path, classId, buildId, classLevel, mainHandId, skillbookId, nodeIds])
  const permittedSkills = accountPermittedSkillIds(catalog, starter, starter.checkpoints[0])
  const weaponChoices: MasterEntityPickerChoice[] = catalog.items.filter(item => item.compatibleSlots.includes('MAIN_HAND')).map(item => ({ ...item,
    category: 'メインハンド', description: `必要Lv.${item.requiredLevel}`,
    disabledReason: equipmentRestriction(item, 'MAIN_HAND', starter, starter.checkpoints[0], catalog),
  }))
  const bookChoices: MasterEntityPickerChoice[] = catalog.items.filter(item => item.compatibleSlots.includes('SKILLBOOK')).map(item => ({ ...item,
    category: 'スキルブック', description: `必要Lv.${item.requiredLevel}`,
    disabledReason: equipmentRestriction(item, 'SKILLBOOK', starter, starter.checkpoints[0], catalog),
  }))
  const skillChoices: MasterEntityPickerChoice[] = (catalog.skills ?? []).filter(skill => !skill.passive).map(skill => ({ ...skill,
    category: 'アクティブスキル', description: `必要Lv.${skill.requiredLevel}`,
    disabledReason: skill.requiredLevel > 1 ? 'Lv.1の段階に設定できません'
      : permittedSkills && !permittedSkills.has(skill.id) ? 'この職業・習得ノード・スキルブックでは使用できません。対応するノードか本を先に設定してください' : undefined,
  }))
  const selectedItem = catalog.items.find(item => item.id === mainHandId)
  const selectedBook = catalog.items.find(item => item.id === skillbookId)
  const selectedSkill = catalog.skills?.find(skill => skill.id === skillId)
  const starterItemInvalid = !!mainHandId && (!selectedItem || !!equipmentRestriction(selectedItem, 'MAIN_HAND', starter, starter.checkpoints[0], catalog))
  const starterBookInvalid = !!skillbookId && (!selectedBook || !!equipmentRestriction(selectedBook, 'SKILLBOOK', starter, starter.checkpoints[0], catalog))
  const starterSkillReason = skillId && (!selectedSkill ? '選択したスキルを読み込めません' : selectedSkill.passive ? 'アクティブスキルを選択してください'
    : selectedSkill.requiredLevel > 1 ? 'Lv.1の段階に設定できません'
      : permittedSkills && !permittedSkills.has(skillId) ? 'このスキルは現在の職業・習得ノード・スキルブックでは使用できません。対応するノードか本を設定してください' : '')
  const availableClasses = catalog.classes.filter(entry => entry.id !== 'administrator')
  const currentClass = availableClasses.find(entry => entry.id === classId) ?? selectedClass
  const maxClassLevel = Math.min(2147483647, currentClass.maxLevel ?? 2147483647)
  const classLevelInvalid = !Number.isInteger(classLevel) || classLevel < 1 || classLevel > maxClassLevel
  const conflicting = catalog.presets.some(entry => entry.classId === classId && entry.buildId === buildId && entry.id !== (action === 'edit' ? preset?.id : undefined))
  const idConflict = catalog.presets.some(entry => entry.id === id && entry.id !== (action === 'edit' ? preset?.id : undefined))
  const invalid = action !== 'delete' && (!name.trim() || !validPresetId(buildId) || conflicting || (action !== 'edit' && (!validPresetId(id) || idConflict || (action === 'create' && (classLevelInvalid || !mainHandId || !skillId || starterItemInvalid || starterBookInvalid || !!starterSkillReason)))))
  const update = (setter: (value: string) => void, value: string) => { setter(value); setPrepared(null); setError('') }
  const initialDraft = useRef(JSON.stringify({ classId, classLevel, name, buildId, internalId, mainHandId, skillbookId, nodeIds, skillId }))
  const dirty = JSON.stringify({ classId, classLevel, name, buildId, internalId, mainHandId, skillbookId, nodeIds, skillId }) !== initialDraft.current
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange])
  useEffect(() => {
    if (!dirty) return
    const guard = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', guard)
    return () => window.removeEventListener('beforeunload', guard)
  }, [dirty])
  const close = () => {
    if (busy || picker || treeOpen || dirty && !window.confirm('入力中のビルド設定を破棄しますか？')) return
    onClose()
  }
  const choose = (value: string) => {
    update(picker === 'weapon' ? setMainHandId : picker === 'book' ? setSkillbookId : setSkillId, value)
    setPicker(null)
  }

  async function prepare() {
    if (invalid || busy) return
    setBusy(true); setError('')
    try {
      if (action === 'edit') {
        if (await onEdit(name, buildId)) onClose()
        return
      }
      if (action === 'delete') {
        if (!preset) throw new Error('削除対象がありません。')
        const document = await masterDataApi.file(preset.path)
        if (catalog.revisions?.[preset.path] && catalog.revisions[preset.path] !== document.revision) throw new Error('ファイルが別の場所で更新されました。再読込してください。')
        setPrepared({ path: preset.path, revision: document.revision, warnings: [] })
        return
      }
      let content: JsonValue
      if (action === 'duplicate') {
        if (!preset) throw new Error('複製元がありません。')
        const document = await masterDataApi.file(preset.path)
        if (catalog.revisions?.[preset.path] && catalog.revisions[preset.path] !== document.revision) throw new Error('複製元が別の場所で更新されました。再読込してください。')
        content = clonePreset(document.content, id, name, buildId)
      } else content = createBlankPreset(id, name, classId, buildId, mainHandId, skillId, { classLevel, skillbookId, nodeIds })
      const rendered = await masterDataApi.render(path, content)
      const validation = await masterDataApi.validate(path, rendered.raw)
      if (!validation.isValid) throw new Error(validation.issues.map(issue => issue.message).join('\n') || 'マスター検証で問題が見つかりました。')
      setPrepared({ path, raw: rendered.raw, warnings: [...rendered.warnings, ...validation.issues.filter(issue => issue.severity === 'warning').map(issue => issue.message)] })
    } catch (reason) { setError(errorText(reason)) }
    finally { setBusy(false) }
  }

  async function commit() {
    if (!prepared || busy) return
    setBusy(true); setError('')
    try {
      if (action === 'delete') {
        if (!preset || !prepared.revision) throw new Error('削除対象を再確認してください。')
        await masterDataApi.delete(prepared.path, prepared.revision)
        onCommitted('deleted', classId)
      } else {
        if (!prepared.raw) throw new Error('作成内容を再確認してください。')
        await masterDataApi.create({ path: prepared.path, raw: prepared.raw })
        onCommitted('created', classId, id)
      }
    } catch (reason) { setError(errorText(reason)) }
    finally { setBusy(false) }
  }

  const title = action === 'create' ? '新しいビルドを登録' : action === 'duplicate' ? 'ビルドを複製' : action === 'edit' ? 'ビルド名とIDを編集' : 'ビルドを削除'
  return <>
    <Modal ariaLabel={title} className="design-build-dialog" onClose={close} closeDisabled={busy || !!picker || treeOpen}>
      <header className="modal-header"><h2>{title}</h2></header>
      <div className="modal-body design-build-body">
      {action === 'delete' ? <p>「{plain(preset?.name ?? '')}」を削除します。確認後、保存先のファイルを削除します。</p> : <>
        <div className="design-build-identity">
        {action === 'create' && <label className="design-dialog-label">職業<select value={classId} disabled={busy || !!prepared} onChange={event => { update(setClassId, event.target.value); setClassLevel(1); setInternalId(''); setMainHandId(''); setSkillbookId(''); setNodeIds([]); setSkillId('') }}>{availableClasses.map(entry => <option key={entry.id} value={entry.id}>{plain(entry.name)}</option>)}</select></label>}
        {action === 'duplicate' && <p>「{plain(preset?.name ?? '')}」の全レベル構成を複製します。</p>}
        <label className="design-dialog-label">表示名<input value={name} disabled={busy || !!prepared} onChange={event => update(setName, event.target.value)} placeholder="例：剣士の標準構成" /></label>
        <label className="design-dialog-label">ビルドID<input value={buildId} disabled={busy || !!prepared} onChange={event => update(setBuildId, event.target.value)} placeholder="例：standard" spellCheck={false} /></label>
        </div>
        <small>小文字英数字とアンダースコアを使用します。表示名は日本語で入力できます。</small>
        {action !== 'edit' && <details className="design-build-advanced"><summary>保存用IDを確認・変更</summary><label className="design-dialog-label">保存用ID<input value={internalId || id} disabled={busy || !!prepared} onChange={event => update(setInternalId, event.target.value)} spellCheck={false} /></label><small>保存先: {path}</small></details>}
        {action === 'edit' && <p className="design-caption">保存用IDとファイルの場所は変わりません。変更は下書きに入り、画面上部から保存します。</p>}
        {action === 'create' && <div className="design-build-starters">
          <p>Lv.1の必須設定</p>
          <label className="design-dialog-label">初期職業Lv.<input type="number" min={1} max={maxClassLevel} step={1} value={classLevel} disabled={busy || !!prepared} onChange={event => { setClassLevel(Number(event.target.value)); setPrepared(null); setError('') }} /></label>
          {classLevelInvalid && <small role="alert" className="design-warning">初期職業Lv.は1〜{maxClassLevel}の整数で指定してください。</small>}
          <button className="button" disabled={busy || !!prepared} onClick={() => setPicker('weapon')}>{selectedItem ? <><MinecraftIcon icon={selectedItem.icon} customModelData={selectedItem.customModelData} iconTexture={selectedItem.iconTexture} />{plain(selectedItem.name)}</> : 'メインハンドを選択'}</button>
          <p>スキルの習得元（任意）</p>
          <button className="button" disabled={busy || !!prepared} onClick={() => setPicker('book')}>{selectedBook ? <><MinecraftIcon icon={selectedBook.icon} customModelData={selectedBook.customModelData} iconTexture={selectedBook.iconTexture} />{plain(selectedBook.name)}</> : 'スキルブックを選択'}</button>
          {skillbookId && <button className="text-button" disabled={busy || !!prepared} onClick={() => update(setSkillbookId, '')}>スキルブックを外す</button>}
          <div className="design-account-nodes">{nodeIds.map(nodeId => { const node = catalog.nodes?.find(entry => entry.id === nodeId); return <div className="design-account-node" key={nodeId}><MinecraftIcon icon={node?.icon ?? null} customModelData={node?.customModelData} iconTexture={node?.iconTexture} /><span>{node ? plain(node.name) : `ノード ${nodeId}`}</span><button className="text-button" aria-label={`${node ? plain(node.name) : `ノード ${nodeId}`}を外す`} disabled={busy || !!prepared} onClick={() => { setNodeIds(current => current.filter(id => id !== nodeId)); setPrepared(null); setError('') }}>外す</button></div> })}</div>
          <button className="button" disabled={busy || !!prepared || classLevelInvalid} onClick={() => setTreeOpen(true)}>スキルツリーで選択</button>
          <p>1番枠のアクティブスキル</p>
          <button className="button" disabled={busy || !!prepared} onClick={() => setPicker('skill')}>{selectedSkill ? <><MinecraftIcon icon={selectedSkill.icon} customModelData={selectedSkill.customModelData} iconTexture={selectedSkill.iconTexture} />{plain(selectedSkill.name)}</> : '1番枠のスキルを選択'}</button>
          {starterBookInvalid && <small role="alert" className="design-warning">このスキルブックはLv.1の職業・装備条件を満たしません。</small>}
          {starterSkillReason && <small role="alert" className="design-warning">{starterSkillReason}</small>}
          <small>初期職業Lv.と必要なノードを設定し、武器とアクティブスキルを選んで登録します。ノードの接続とポイント予算はゲーム適用時にも確認してください。</small>
        </div>}
      </>}
      {(conflicting || idConflict) && <p className="design-warning" role="alert">同じ職業のビルドID、または保存用IDが既にあります。</p>}
      {error && <p className="design-warning" role="alert">{error}</p>}
      {prepared && <div className="design-build-review"><strong>{action === 'delete' ? '削除対象' : '登録内容を確認'}</strong><p>{plain(name || preset?.name || '')} · {prepared.path}</p>{prepared.warnings.map((warning, index) => <p className="design-warning" key={index}>{warning}</p>)}{prepared.raw && <details><summary>作成するファイルを表示</summary><pre>{prepared.raw}</pre></details>}<p>この操作は確認後、ファイルへすぐに反映されます。</p></div>}
      </div>
      <footer className="modal-footer"><button className="button" disabled={busy} onClick={prepared ? () => setPrepared(null) : close}>{prepared ? '入力に戻る' : 'キャンセル'}</button><button className="button primary" disabled={busy || (!prepared && invalid)} onClick={() => void (prepared ? commit() : prepare())}>{busy ? '処理中…' : prepared ? action === 'delete' ? 'このビルドを削除' : 'このビルドを登録' : action === 'edit' ? '下書きに反映' : '内容を確認'}</button></footer>
    </Modal>
    {picker && <MasterEntityPicker title={picker === 'weapon' ? 'メインハンドを選択' : picker === 'book' ? 'スキルブックを選択' : '1番枠のスキルを選択'} choices={picker === 'weapon' ? weaponChoices : picker === 'book' ? bookChoices : skillChoices} value={picker === 'weapon' ? mainHandId : picker === 'book' ? skillbookId : skillId} onSelect={choose} onClose={() => setPicker(null)} />}
    {treeOpen && <ProgressionSkillTreePicker key={`${classId}:${classLevel}`} context={{ classId, playerLevel: 1, classLevel }} values={nodeIds} disabled={busy || !!prepared}
      onClose={() => setTreeOpen(false)} onApply={selected => {
        setNodeIds(selected.filter((value): value is number => typeof value === 'number'))
        setPrepared(null); setError(''); setTreeOpen(false)
      }} />}
  </>
}
