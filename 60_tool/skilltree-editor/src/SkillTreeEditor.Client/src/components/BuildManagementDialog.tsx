import { useMemo, useState } from 'react'
import { masterDataApi } from '../api/masterDataApi'
import { equipmentRestriction } from '../data/progressionDesign'
import { clonePreset, createBlankPreset, presetPath, validPresetId } from '../data/progressionBuildManagement'
import type { JsonValue } from '../types/editor'
import type { DesignClass, DesignPreset, ProgressionDesignCatalog } from '../types/progressionDesign'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MasterEntityPicker, type MasterEntityPickerChoice } from './MasterEntityPicker'
import { MinecraftIcon } from './MinecraftIcon'
import { Modal } from './Modal'

export type BuildAction = 'create' | 'duplicate' | 'edit' | 'delete'
interface Props {
  action: BuildAction
  catalog: ProgressionDesignCatalog
  selectedClass: DesignClass
  preset?: DesignPreset
  onClose: () => void
  onEdit: (name: string, buildId: string) => Promise<boolean>
  onCommitted: (kind: 'created' | 'deleted', classId: string, presetId?: string) => void
}
const plain = stripMinecraftFormatting
const errorText = (reason: unknown) => reason instanceof Error ? reason.message : String(reason)
type Prepared = { path: string; raw?: string; revision?: string; warnings: string[] }

export function BuildManagementDialog({ action, catalog, selectedClass, preset, onClose, onEdit, onCommitted }: Props) {
  const [classId, setClassId] = useState(selectedClass.id)
  const [name, setName] = useState(action === 'edit' ? preset?.name ?? '' : action === 'duplicate' ? `${plain(preset?.name ?? '')} のコピー` : '')
  const [buildId, setBuildId] = useState(action === 'edit' ? preset?.buildId ?? '' : action === 'duplicate' ? `${preset?.buildId ?? 'build'}_copy` : '')
  const [internalId, setInternalId] = useState('')
  const [mainHandId, setMainHandId] = useState('')
  const [skillId, setSkillId] = useState('')
  const [picker, setPicker] = useState<'weapon' | 'skill' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [prepared, setPrepared] = useState<Prepared | null>(null)
  const selected = catalog.classes.find(entry => entry.id === classId) ?? selectedClass
  const id = internalId || `${classId}_${buildId}`
  const path = action === 'edit' || action === 'delete' ? preset?.path ?? '' : presetPath(id)
  const starter = useMemo(() => ({ id, name, path, classId, buildId, checkpoints: [{ level: 1, classLevel: 1, pointer: '/levels/0', equipment: [], nodes: [], skills: [], passives: [] }] } satisfies DesignPreset), [id, name, path, classId, buildId])
  const weaponChoices: MasterEntityPickerChoice[] = catalog.items.filter(item => item.compatibleSlots.includes('MAIN_HAND')).map(item => ({ ...item,
    category: 'メインハンド', description: `必要Lv.${item.requiredLevel}`,
    disabledReason: equipmentRestriction(item, 'MAIN_HAND', starter, starter.checkpoints[0], catalog),
  }))
  const skillChoices: MasterEntityPickerChoice[] = (catalog.skills ?? []).filter(skill => !skill.passive).map(skill => ({ ...skill,
    category: 'アクティブスキル', description: `必要Lv.${skill.requiredLevel}`,
    disabledReason: skill.requiredLevel > 1 ? 'Lv.1の段階に設定できません' : selected.usableSkillIds?.length && !selected.usableSkillIds.includes(skill.id) ? 'この職業の初期使用可能スキルではありません' : undefined,
  }))
  const selectedItem = catalog.items.find(item => item.id === mainHandId)
  const selectedSkill = catalog.skills?.find(skill => skill.id === skillId)
  const starterItemInvalid = !!selectedItem && !!equipmentRestriction(selectedItem, 'MAIN_HAND', starter, starter.checkpoints[0], catalog)
  const starterSkillInvalid = !!selectedSkill && (selectedSkill.passive || selectedSkill.requiredLevel > 1 || !!selected.usableSkillIds?.length && !selected.usableSkillIds.includes(selectedSkill.id))
  const availableClasses = catalog.classes.filter(entry => entry.id !== 'administrator')
  const conflicting = catalog.presets.some(entry => entry.classId === classId && entry.buildId === buildId && entry.id !== (action === 'edit' ? preset?.id : undefined))
  const idConflict = catalog.presets.some(entry => entry.id === id && entry.id !== (action === 'edit' ? preset?.id : undefined))
  const invalid = action !== 'delete' && (!name.trim() || !validPresetId(buildId) || conflicting || (action !== 'edit' && (!validPresetId(id) || idConflict || (action === 'create' && (!mainHandId || !skillId || starterItemInvalid || starterSkillInvalid)))))
  const update = (setter: (value: string) => void, value: string) => { setter(value); setPrepared(null); setError('') }

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
      } else content = createBlankPreset(id, name, classId, buildId, mainHandId, skillId)
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
    <Modal ariaLabel={title} className="design-build-dialog" onClose={onClose} closeDisabled={busy || !!picker}>
      <header className="modal-header"><h2>{title}</h2></header>
      {action === 'delete' ? <p>「{plain(preset?.name ?? '')}」を削除します。登録するとファイルから削除されます。</p> : <>
        {action === 'create' && <label className="design-dialog-label">職業<select value={classId} disabled={busy || !!prepared} onChange={event => { update(setClassId, event.target.value); setMainHandId(''); setSkillId('') }}>{availableClasses.map(entry => <option key={entry.id} value={entry.id}>{plain(entry.name)}</option>)}</select></label>}
        {action === 'duplicate' && <p>「{plain(preset?.name ?? '')}」の全レベル構成を複製します。</p>}
        <label className="design-dialog-label">表示名<input value={name} disabled={busy || !!prepared} onChange={event => update(setName, event.target.value)} placeholder="例：剣士の標準構成" /></label>
        <label className="design-dialog-label">ビルドID<input value={buildId} disabled={busy || !!prepared} onChange={event => update(setBuildId, event.target.value)} placeholder="例：standard" spellCheck={false} /></label>
        <small>小文字英数字とアンダースコアを使用します。表示名は日本語で入力できます。</small>
        {action !== 'edit' && <details className="design-build-advanced"><summary>保存用IDを確認・変更</summary><label className="design-dialog-label">保存用ID<input value={internalId || id} disabled={busy || !!prepared} onChange={event => update(setInternalId, event.target.value)} spellCheck={false} /></label><small>保存先: {path}</small></details>}
        {action === 'edit' && <p className="design-caption">保存用IDとファイルの場所は変わりません。変更は下書きに入り、画面上部から保存します。</p>}
        {action === 'create' && <div className="design-build-starters"><p>Lv.1の必須設定</p><button className="button" disabled={busy || !!prepared} onClick={() => setPicker('weapon')}>{selectedItem ? <><MinecraftIcon icon={selectedItem.icon} customModelData={selectedItem.customModelData} iconTexture={selectedItem.iconTexture} />{plain(selectedItem.name)}</> : 'メインハンドを選択'}</button><button className="button" disabled={busy || !!prepared} onClick={() => setPicker('skill')}>{selectedSkill ? <><MinecraftIcon icon={selectedSkill.icon} customModelData={selectedSkill.customModelData} iconTexture={selectedSkill.iconTexture} />{plain(selectedSkill.name)}</> : '1番枠のスキルを選択'}</button><small>武器とアクティブスキルを選んで登録します。追加の装備・スキル・ノードは登録後に編集できます。</small></div>}
      </>}
      {(conflicting || idConflict) && <p className="design-warning" role="alert">同じ職業のビルドID、または保存用IDが既にあります。</p>}
      {error && <p className="design-warning" role="alert">{error}</p>}
      {prepared && <div className="design-build-review"><strong>{action === 'delete' ? '削除対象' : '登録内容を確認'}</strong><p>{plain(name || preset?.name || '')} · {prepared.path}</p>{prepared.warnings.map((warning, index) => <p className="design-warning" key={index}>{warning}</p>)}{prepared.raw && <details><summary>作成するファイルを表示</summary><pre>{prepared.raw}</pre></details>}<p>この操作は確認後、ファイルへすぐに反映されます。</p></div>}
      <footer className="modal-footer"><button className="button" disabled={busy} onClick={prepared ? () => setPrepared(null) : onClose}>{prepared ? '入力に戻る' : 'キャンセル'}</button><button className="button primary" disabled={busy || (!prepared && invalid)} onClick={() => void (prepared ? commit() : prepare())}>{busy ? '処理中…' : prepared ? action === 'delete' ? 'このビルドを削除' : 'このビルドを登録' : action === 'edit' ? '下書きに反映' : '内容を確認'}</button></footer>
    </Modal>
    {picker && <MasterEntityPicker title={picker === 'weapon' ? 'メインハンドを選択' : '1番枠のスキルを選択'} choices={picker === 'weapon' ? weaponChoices : skillChoices} value={picker === 'weapon' ? mainHandId : skillId} onSelect={value => { update(picker === 'weapon' ? setMainHandId : setSkillId, value); setPicker(null) }} onClose={() => setPicker(null)} />}
  </>
}
