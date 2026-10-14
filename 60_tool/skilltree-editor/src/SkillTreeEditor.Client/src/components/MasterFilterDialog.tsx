import { useMemo, useState } from 'react'
import { Modal } from './Modal'
import type { MasterFileSummary, MasterFilterIndex, MasterReference } from '../types/masterData'
import { categoryFacetOptions, categoryNumericOptions, facetLabels, isSkillDirectory, numericFilterError, numericFilterLabels, searchMasterName } from '../data/masterDataFilters'
import { emptyMasterListFilters, filterMasterFiles, type MasterListFilters } from '../data/masterFileQuery'
import { namedMasterOption } from '../data/masterDataPresentation'
import { masterValueLabel } from '../data/masterValuePresentation'
import { stripMinecraftFormatting } from '../utils/minecraft'

interface MasterFilterDialogProps {
  value: MasterListFilters
  files: MasterFileSummary[]
  allFiles: MasterFileSummary[]
  index: MasterFilterIndex | null
  candidates: MasterReference[]
  scopeLabel: string
  query: string
  itemScope: boolean
  dropScope: boolean
  skillScope: boolean
  onApply: (value: MasterListFilters) => void
  onCancel: () => void
}

export function masterFacetValueLabel(key: string, value: string, index: MasterFilterIndex | null, candidates: MasterReference[]): string {
  if (key === 'tags' || key === 'requiredToolTags') return namedMasterOption(index?.tags.find((entry) => entry.id === value)?.name, value)
  if (key === 'world' || key === 'requiredClasses') return namedMasterOption(stripMinecraftFormatting(candidates.find((entry) => entry.kind === (key === 'world' ? 'world' : 'class') && entry.value === value)?.label ?? ''), value)
  if (key === 'unTradeable') return value === 'true' ? 'トレード禁止' : 'トレード可能'
  if (key === 'unSellable') return value === 'true' ? '売却禁止' : '売却可能'
  if (['enchantEnabled', 'enhanceEnabled'].includes(key)) return value === 'true' ? '可能' : '不可'
  if (key === 'classRestricted' || key === 'passiveConfigured') return value === 'true' ? 'あり' : 'なし'
  if (key === 'enabled') return value === 'true' ? '有効' : '無効'
  return masterValueLabel(key, value)
}

export function MasterFilterDialog({ value, files, allFiles, index, candidates, scopeLabel, query, itemScope, dropScope, skillScope, onApply, onCancel }: MasterFilterDialogProps) {
  const [draft, setDraft] = useState<MasterListFilters>(() => structuredClone(value))
  const facets = useMemo(() => categoryFacetOptions(files, index), [files, index])
  const numbers = useMemo(() => categoryNumericOptions(files, index), [files, index])
  const numericError = numericFilterError(draft.numericFilters)
  const count = useMemo(() => filterMasterFiles(files, index, draft, query).length, [files, index, draft, query])
  const set = <K extends keyof MasterListFilters>(key: K, next: MasterListFilters[K]) => setDraft((current) => ({ ...current, [key]: next }))
  const paths = new Set(files.map((file) => file.path))
  const sources = (index?.sources ?? []).filter((source) => source.path === draft.dropSourcePath || searchMasterName(draft.sourceQuery, source.name, source.id, source.path))
  const dropIds = new Set(index?.sources.filter((source) => paths.has(source.path)).flatMap((source) => source.itemIds) ?? [])
  const items = [...dropIds].map((id) => ({ value: id, label: candidates.find((item) => item.kind === 'item' && item.value === id)?.label ?? allFiles.find((file) => file.id === id && file.path.startsWith('10.features.item/'))?.name ?? id }))
    .filter((item) => item.value === draft.dropItemId || searchMasterName(draft.dropItemQuery, item.label, item.value))
    .sort((left, right) => left.label.localeCompare(right.label, 'ja'))
  const tags = new Set(index?.skillTags.filter((skill) => paths.has(skill.path)).flatMap((skill) => skill.tags) ?? [])
  const stale = !index && Boolean(draft.dropSourcePath || draft.skillTag || draft.dropItemId || draft.dropPresence)
    || Boolean(draft.dropSourcePath && !index?.sources.some((source) => source.path === draft.dropSourcePath))
    || Boolean(draft.skillTag && (!tags.has(draft.skillTag) || !index?.tags.some((tag) => tag.id === draft.skillTag)))
    || Boolean(draft.dropItemId && !dropIds.has(draft.dropItemId))
    || Boolean(draft.format && !files.some((file) => file.format === draft.format))
    || Object.entries(draft.facetFilters).some(([key, selected]) => !facets[key]?.includes(selected))
    || Object.entries(draft.numericFilters).some(([key, range]) => (range.min.trim() || range.max.trim()) && !numbers[key])
  const identityKeys = ['category', 'type', 'rarity', 'slot', 'handType', 'equipmentTag', 'role', 'entityType']
  const availabilityKeys = ['unTradeable', 'unSellable', 'classRestricted', 'requiredClasses', 'enchantEnabled', 'enhanceEnabled', 'setId']
  const onlySkills = files.length > 0 && files.every((file) => isSkillDirectory(file.path))
  const shownFacetKeys = Object.keys(facets).filter((key) => key !== 'tags' || !onlySkills)
  const facetField = (key: string) => <label key={key}>{facetLabels[key]}<select aria-label={facetLabels[key]} value={draft.facetFilters[key] ?? ''} onChange={(event) => {
    const next = { ...draft.facetFilters }
    if (event.target.value) next[key] = event.target.value; else delete next[key]
    set('facetFilters', next)
  }}><option value="">指定しない</option>{facets[key].map((option) => <option key={option} value={option}>{masterFacetValueLabel(key, option, index, candidates)}</option>)}</select></label>
  const facetSection = (title: string, keys: string[]) => keys.length > 0 && <fieldset className="master-filter-section"><legend>{title}</legend><div className="master-filter-fields">{keys.map(facetField)}</div></fieldset>
  return <Modal ariaLabel="詳細フィルター" className="master-filter-dialog" onClose={onCancel}>
    <form onSubmit={(event) => { event.preventDefault(); if (!numericError && !stale) onApply(draft) }}>
      <header className="modal-header"><div><span className="master-dialog-kicker">ファイルを探す</span><h2>詳細フィルター</h2><p>{scopeLabel}の保存済みデータを絞り込みます。条件はすべてANDで組み合わせます。</p></div><button type="button" className="icon-button" aria-label="フィルターを閉じる" onClick={onCancel}>×</button></header>
      <div className="modal-body master-filter-body">
        {!index && <p role="status" className="master-message warning">詳細条件の索引を取得できません。ファイル形式・状態での絞り込みは利用できます。一覧を再読込すると再試行できます。</p>}
        {stale && <p role="alert" className="error-message">対象ファイルや索引の更新により、利用できなくなった条件があります。「条件をクリア」して再設定してください。</p>}
        {facetSection('取引・装備の条件', shownFacetKeys.filter((key) => availabilityKeys.includes(key)))}
        {Object.keys(numbers).length > 0 && <fieldset className="master-filter-section"><legend>レベル・数値の範囲</legend><p>空欄は制限なしです。モブは範囲内の定義レベルを1つ以上含むものを表示します。</p><div className="master-range-fields">{Object.entries(numbers).map(([key, values]) => <div className="master-range-field" key={key}><strong>{numericFilterLabels[key]}</strong><div><label><span>下限</span><input aria-label={`${numericFilterLabels[key]} 下限`} inputMode="decimal" placeholder="制限なし" value={draft.numericFilters[key]?.min ?? ''} onChange={(event) => set('numericFilters', { ...draft.numericFilters, [key]: { min: event.target.value, max: draft.numericFilters[key]?.max ?? '' } })} /></label><span aria-hidden="true">〜</span><label><span>上限</span><input aria-label={`${numericFilterLabels[key]} 上限`} inputMode="decimal" placeholder="制限なし" value={draft.numericFilters[key]?.max ?? ''} onChange={(event) => set('numericFilters', { ...draft.numericFilters, [key]: { min: draft.numericFilters[key]?.min ?? '', max: event.target.value } })} /></label></div><small>保存済み: {Math.min(...values).toLocaleString('ja-JP')} 〜 {Math.max(...values).toLocaleString('ja-JP')}</small></div>)}</div></fieldset>}
        {numericError && <p role="alert" className="error-message">{numericError}</p>}
        {facetSection('種類・分類', shownFacetKeys.filter((key) => identityKeys.includes(key)))}
        {facetSection('タグ・ワールド・動作', shownFacetKeys.filter((key) => !identityKeys.includes(key) && !availabilityKeys.includes(key)))}
        {skillScope && <fieldset className="master-filter-section"><legend>スキルの共有タグ</legend><label>スキルタグ<select aria-label="スキルタグ" value={draft.skillTag} onChange={(event) => set('skillTag', event.target.value)} disabled={!index}><option value="">指定しない</option>{index?.tags.filter((tag) => tags.has(tag.id)).map((tag) => <option key={tag.id} value={tag.id}>{namedMasterOption(tag.name, tag.id)}</option>)}</select></label></fieldset>}
        {itemScope && <fieldset className="master-filter-section"><legend>アイテムのドロップ元</legend><div className="master-filter-fields"><label>モブ・採集オブジェクトを検索<input aria-label="ドロップ元を名前で検索" value={draft.sourceQuery} placeholder="名前 / ID" onChange={(event) => setDraft((current) => ({ ...current, sourceQuery: event.target.value, dropSourcePath: '' }))} /></label><label>ドロップ元<select aria-label="ドロップ元" value={draft.dropSourcePath} onChange={(event) => set('dropSourcePath', event.target.value)} disabled={!index}><option value="">指定しない</option>{sources.map((source) => <option key={source.path} value={source.path}>{source.kind === 'mob' ? 'モブ' : '採集'} · {namedMasterOption(stripMinecraftFormatting(source.name), source.id)}</option>)}</select></label></div><p>レベル別・共通報酬テーブル経由のドロップ品も対象です。</p></fieldset>}
        {dropScope && <fieldset className="master-filter-section"><legend>モブ・採集のドロップ品</legend><div className="master-filter-fields"><label>ドロップ品を検索<input aria-label="ドロップ品を名前で検索" value={draft.dropItemQuery} onChange={(event) => setDraft((current) => ({ ...current, dropItemQuery: event.target.value, dropItemId: '' }))} /></label><label>ドロップするアイテム<select aria-label="ドロップするアイテム" value={draft.dropItemId} onChange={(event) => set('dropItemId', event.target.value)} disabled={!index}><option value="">指定しない</option>{[...new Map(items.map((item) => [item.value, item])).values()].map((item) => <option key={item.value} value={item.value}>{namedMasterOption(stripMinecraftFormatting(item.label ?? ''), item.value)}</option>)}</select></label><label>アイテムドロップの有無<select aria-label="アイテムドロップの有無" value={draft.dropPresence} onChange={(event) => set('dropPresence', event.target.value)} disabled={!index}><option value="">すべて</option><option value="has">あり</option><option value="none">なし</option></select></label></div></fieldset>}
        <fieldset className="master-filter-section"><legend>ファイルの条件</legend><div className="master-filter-fields"><label>ファイル形式<select aria-label="ファイル形式" value={draft.format} onChange={(event) => set('format', event.target.value)}><option value="">すべて</option>{[...new Set(files.map((file) => file.format))].map((format) => <option key={format} value={format}>{format.toUpperCase()}</option>)}</select></label><label>ファイル状態<select aria-label="ファイル状態" value={draft.fileStatus} onChange={(event) => set('fileStatus', event.target.value)}><option value="">すべて</option><option value="editable">編集可能</option><option value="readOnly">閲覧のみ</option><option value="error">解析エラーあり</option></select></label></div></fieldset>
      </div>
      <footer className="modal-footer"><button type="button" className="button subtle" onClick={() => setDraft(emptyMasterListFilters())}>条件をクリア</button><span className="master-filter-preview" role="status">{stale ? '条件を再設定してください' : numericError ? '範囲を修正してください' : `${count} 件が一致`}</span><button type="button" className="button" onClick={onCancel}>キャンセル</button><button type="submit" className="button primary" disabled={Boolean(numericError) || stale}>条件を適用</button></footer>
    </form>
  </Modal>
}
