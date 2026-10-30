import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MasterFilterDialog, masterFacetValueLabel } from './MasterFilterDialog'
import { plainMasterFilterName } from '../data/masterDataPresentation'
import { emptyMasterListFilters, type MasterListFilters } from '../data/masterFileQuery'
import type { MasterFileSummary, MasterFilterIndex, MasterReference } from '../types/masterData'

const file: MasterFileSummary = { path: '10.features.item/item.yml', category: 'item', format: 'yaml', name: 'アイテム', id: 'item', revision: 'a', size: 10, modifiedUtc: '' }
const index: MasterFilterIndex = {
  sources: [{ path: '40.features.mob/mob.yml', kind: 'mob', name: 'モブ', id: 'mob', itemIds: ['item'] }],
  skillTags: [], tags: [], entries: [{ path: file.path, facets: { unTradeable: ['true'] }, numbers: { requiredLevel: [10] } }],
}
const props = { files: [file], allFiles: [file], index, candidates: [], scopeLabel: 'アイテム', query: '', itemScope: true, dropScope: false, skillScope: false, onCancel: vi.fn() }

describe('filter draft freshness', () => {
  it.each([
    { facetFilters: { unTradeable: 'true' } },
    { numericFilters: { requiredLevel: { min: '5', max: '15' } } },
    { dropSourcePath: '40.features.mob/mob.yml' },
  ] as Partial<MasterListFilters>[])('requires resetting a condition removed by an index refresh: %j', (condition) => {
    const onApply = vi.fn()
    const value = { ...emptyMasterListFilters(), ...condition }
    const view = render(<MasterFilterDialog {...props} value={value} onApply={onApply} />)
    expect(screen.getByRole('button', { name: '条件を適用' })).toBeEnabled()
    view.rerender(<MasterFilterDialog {...props} index={{ sources: [], skillTags: [], tags: [], entries: [] }} value={emptyMasterListFilters()} onApply={onApply} />)
    expect(screen.getByRole('alert')).toHaveTextContent('利用できなくなった条件')
    expect(screen.getByRole('button', { name: '条件を適用' })).toBeDisabled()
    fireEvent.submit(screen.getByRole('button', { name: '条件を適用' }).closest('form')!)
    expect(onApply).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '条件をクリア' }))
    fireEvent.click(screen.getByRole('button', { name: '条件を適用' }))
    expect(onApply).toHaveBeenCalledWith(emptyMasterListFilters())
  })

  it('rejects a format that disappeared while the dialog was open', () => {
    const onApply = vi.fn()
    const view = render(<MasterFilterDialog {...props} value={{ ...emptyMasterListFilters(), format: 'yaml' }} onApply={onApply} />)
    view.rerender(<MasterFilterDialog {...props} files={[{ ...file, format: 'json' }]} value={emptyMasterListFilters()} onApply={onApply} />)
    expect(screen.getByRole('button', { name: '条件を適用' })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent('利用できなくなった条件')
  })

  it('exposes generic tags and skill tags separately when searching all masters', () => {
    const skill = { ...file, path: '30.features.skill/skill.yml', id: 'skill', category: 'skill' }
    const mixedIndex = { ...index, tags: [{ id: 'magic', name: '魔法' }], skillTags: [{ path: skill.path, tags: ['magic'] }], entries: [{ path: file.path, facets: { tags: ['magic'] } }] }
    render(<MasterFilterDialog {...props} files={[file, skill]} index={mixedIndex} scopeLabel="Filebase 全体" skillScope value={emptyMasterListFilters()} onApply={vi.fn()} />)
    expect(screen.getByRole('combobox', { name: 'タグ' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'スキルタグ' })).toBeInTheDocument()
  })
})

describe('multiword drop candidate search', () => {
  it('matches drop sources across name and ID and preserves the selected source in the options', () => {
    const source = { path: '40.features.mob/eriva_boar.yml', kind: 'mob' as const, name: '§aグラスボア', id: 'eriva_boar', itemIds: ['item'] }
    const selected = { path: '42.features.gathering/old.yml', kind: 'gathering' as const, name: '古い採集', id: 'old', itemIds: ['item'] }
    const value = { ...emptyMasterListFilters(), sourceQuery: 'eriva　グラス', dropSourcePath: selected.path }
    render(<MasterFilterDialog {...props} index={{ ...index, sources: [source, selected] }} value={value} onApply={vi.fn()} />)
    const options = within(screen.getByRole('combobox', { name: 'ドロップ元' })).getAllByRole('option')
    expect(options.map((option) => option.getAttribute('value'))).toEqual(['', source.path, selected.path])
    expect(screen.getByRole('combobox', { name: 'ドロップ元' })).toHaveValue(selected.path)
    expect(screen.getByText('1 件が一致')).toBeInTheDocument()
  })

  it('matches drop items across name and ID and preserves the selected item', () => {
    const mob = { ...file, path: '40.features.mob/mob.yml', id: 'mob', category: 'mob' }
    const item = { ...file, path: '10.features.item/eriva_sword.yml', id: 'eriva_sword', name: '§b勇者の剣' }
    const oldItem = { ...file, path: '10.features.item/old.yml', id: 'old', name: '古い剣' }
    const dropIndex: MasterFilterIndex = { sources: [{ path: mob.path, kind: 'mob', name: 'モブ', id: 'mob', itemIds: [item.id!, oldItem.id!] }], skillTags: [], tags: [], entries: [] }
    const value = { ...emptyMasterListFilters(), dropItemQuery: 'ERIVA　勇者', dropItemId: oldItem.id! }
    render(<MasterFilterDialog {...props} files={[mob]} allFiles={[mob, item, oldItem]} index={dropIndex} itemScope={false} dropScope value={value} onApply={vi.fn()} />)
    const options = within(screen.getByRole('combobox', { name: 'ドロップするアイテム' })).getAllByRole('option')
    expect(options.map((option) => option.getAttribute('value'))).toEqual(expect.arrayContaining(['', oldItem.id, item.id]))
    expect(screen.getByRole('combobox', { name: 'ドロップするアイテム' })).toHaveValue(oldItem.id)
    expect(screen.getByText('1 件が一致')).toBeInTheDocument()
  })
})

describe('drop candidate preparation', () => {
  const mob = { ...file, path: '40.features.mob/mob.yml', id: 'mob', category: 'mob' }
  const itemFile = (id: string, name: string | null, path = `10.features.item/${id}.yml`): MasterFileSummary => ({ ...file, id, name, path })
  const itemRef = (id: string, label?: string): MasterReference => ({ path: '', pointer: '', kind: 'item', value: id, label })
  const dropIndex = (ids: string[]): MasterFilterIndex => ({ ...index, sources: [{ path: mob.path, kind: 'mob', name: 'モブ', id: 'mob', itemIds: ids }], entries: [] })
  const options = () => within(screen.getByRole('combobox', { name: 'ドロップするアイテム' })).getAllByRole('option').slice(1).map((option) => ({ value: option.getAttribute('value'), text: option.textContent }))

  it('keeps first labels and item files, handles missing and empty labels, and sorts equal labels stably', () => {
    render(<MasterFilterDialog {...props} files={[mob]} allFiles={[
      itemFile('first_file', '最初の名前'), itemFile('first_file', '後の名前'),
      itemFile('wrong_path', '他カテゴリ', 'other/10.features.item/wrong_path.yml'),
      itemFile('empty', 'ファイルの名前'), itemFile('missing_label', '補完名'),
    ]} index={dropIndex(['z', 'same_b', 'same_a', 'first_file', 'empty', 'missing_label', 'wrong_path', 'z'])} candidates={[
      itemRef('z', '§b青'), itemRef('z', '後の候補'),
      itemRef('same_b', '同名'), itemRef('same_a', '同名'),
      itemRef('first_file', undefined), itemRef('first_file', '後の候補'),
      itemRef('empty', ''), itemRef('missing_label', undefined),
    ]} itemScope={false} dropScope value={emptyMasterListFilters()} onApply={vi.fn()} />)
    expect(options()).toEqual([
      { value: 'empty', text: 'empty' },
      { value: 'wrong_path', text: 'wrong_path' },
      { value: 'first_file', text: '最初の名前 (first_file)' },
      { value: 'z', text: '青 (z)' },
      { value: 'same_b', text: '同名 (same_b)' },
      { value: 'same_a', text: '同名 (same_a)' },
      { value: 'missing_label', text: '補完名 (missing_label)' },
    ])
  })

  it('sorts by the visible Japanese names, retains equal-name source order, and leaves literal entities intact', () => {
    const references = [
      itemRef('apple', '&cりんご'), itemRef('berry', '§#123456あんず'),
      itemRef('melon', '&x&1&2&3&4&5&6いちご'),
      itemRef('same_b', '&f同名'), itemRef('same_a', '§l同名'),
      itemRef('entity', '&amp;まめ'), itemRef('empty_code', '&f§l'),
    ]
    const originalLabels = references.map((reference) => reference.label)
    render(<MasterFilterDialog {...props} files={[mob]} allFiles={[mob, itemFile('empty_code', 'ファイルの名前')]}
      index={dropIndex(['apple', 'same_b', 'melon', 'berry', 'same_a', 'entity', 'empty_code'])}
      candidates={references} itemScope={false} dropScope value={emptyMasterListFilters()} onApply={vi.fn()} />)
    expect(options()).toEqual([
      { value: 'entity', text: '&amp;まめ (entity)' },
      { value: 'empty_code', text: 'empty_code' },
      { value: 'berry', text: 'あんず (berry)' },
      { value: 'melon', text: 'いちご (melon)' },
      { value: 'apple', text: 'りんご (apple)' },
      { value: 'same_b', text: '同名 (same_b)' },
      { value: 'same_a', text: '同名 (same_a)' },
    ])
    expect(references.map((reference) => reference.label)).toEqual(originalLabels)
  })

  it('searches the raw label once when formatting exposes a literal code-like prefix', () => {
    const reference = itemRef('peach', '&§fa桃')
    render(<MasterFilterDialog {...props} files={[mob]} allFiles={[mob]} index={dropIndex(['peach'])}
      candidates={[reference]} itemScope={false} dropScope value={emptyMasterListFilters()} onApply={vi.fn()} />)
    expect(options()).toEqual([{ value: 'peach', text: '&a桃 (peach)' }])
    expect(screen.getByText('1 件が一致')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: 'ドロップ品を名前で検索' }), { target: { value: '&' } })
    expect(options()).toEqual([{ value: 'peach', text: '&a桃 (peach)' }])
    expect(screen.getByText('1 件が一致')).toBeInTheDocument()
    expect(reference.label).toBe('&§fa桃')
  })

  it('keeps a missing-name fallback ID verbatim even when it resembles a formatting code', () => {
    render(<MasterFilterDialog {...props} files={[mob]} allFiles={[mob]} index={dropIndex(['sword&a'])}
      candidates={[]} itemScope={false} dropScope value={emptyMasterListFilters()} onApply={vi.fn()} />)
    expect(options()).toEqual([{ value: 'sword&a', text: 'sword&a' }])
  })

  it('shows plain names and verbatim ID fallbacks for source, tag, world, and class choices', () => {
    const source = { path: '40.features.mob/eriva.yml', kind: 'mob' as const, name: '&f§l', id: 'eriva', itemIds: ['item'] }
    const tagged = { ...index, sources: [source], tags: [{ id: 'magic', name: '&b 魔法 ' }, { id: 'blank', name: '§l' }],
      skillTags: [{ path: file.path, tags: ['magic', 'blank'] }],
      entries: [{ path: file.path, facets: { tags: ['magic', 'blank'], requiredToolTags: ['magic'], world: ['eriva_world'], requiredClasses: ['mage'] } }] }
    const candidates: MasterReference[] = [
      { path: '', pointer: '', kind: 'world', value: 'eriva_world', label: '§#00aa00 エリヴァ ' },
      { path: '', pointer: '', kind: 'class', value: 'mage', label: '&l' },
    ]
    render(<MasterFilterDialog {...props} index={tagged} candidates={candidates} skillScope value={emptyMasterListFilters()} onApply={vi.fn()} />)
    expect(within(screen.getByRole('combobox', { name: 'ドロップ元' })).getByRole('option', { name: 'モブ · eriva' })).toHaveValue(source.path)
    expect(within(screen.getByRole('combobox', { name: 'スキルタグ' })).getByRole('option', { name: '魔法 (magic)' })).toHaveValue('magic')
    expect(within(screen.getByRole('combobox', { name: 'スキルタグ' })).getByRole('option', { name: 'blank' })).toHaveValue('blank')
    expect(masterFacetValueLabel('tags', 'magic', tagged, candidates)).toBe('魔法 (magic)')
    expect(masterFacetValueLabel('requiredToolTags', 'blank', tagged, candidates)).toBe('blank')
    expect(masterFacetValueLabel('world', 'eriva_world', tagged, candidates)).toBe('エリヴァ (eriva_world)')
    expect(masterFacetValueLabel('requiredClasses', 'mage', tagged, candidates)).toBe('mage')
    expect(masterFacetValueLabel('tags', 'unknown', { ...tagged, tags: [{ id: 'unknown', name: '&q未知' }] }, candidates)).toBe('&q未知 (unknown)')
    expect(plainMasterFilterName('§l', 'id&f')).toBe('id&f')
  })

  it('updates candidates and scope asynchronously while retaining a hidden selection and detecting stale data', () => {
    const value = { ...emptyMasterListFilters(), dropItemQuery: 'unmatched', dropItemId: 'sword' }
    const files = [mob]
    const view = render(<MasterFilterDialog {...props} files={files} allFiles={files} index={dropIndex(['sword'])} candidates={[]} itemScope={false} dropScope value={value} onApply={vi.fn()} />)
    expect(options()).toEqual([{ value: 'sword', text: 'sword' }])
    view.rerender(<MasterFilterDialog {...props} files={files} allFiles={files} index={dropIndex(['sword'])} candidates={[itemRef('sword', '新しい剣')]} itemScope={false} dropScope value={value} onApply={vi.fn()} />)
    expect(options()).toEqual([{ value: 'sword', text: '新しい剣 (sword)' }])
    view.rerender(<MasterFilterDialog {...props} files={[file]} allFiles={files} index={dropIndex(['sword'])} candidates={[itemRef('sword', '新しい剣')]} itemScope={false} dropScope value={value} onApply={vi.fn()} />)
    expect(screen.getByRole('button', { name: '条件を適用' })).toBeDisabled()
    view.rerender(<MasterFilterDialog {...props} files={files} allFiles={files} index={dropIndex(['other'])} candidates={[itemRef('sword', '新しい剣')]} itemScope={false} dropScope value={value} onApply={vi.fn()} />)
    expect(screen.getByRole('button', { name: '条件を適用' })).toBeDisabled()
  })

  it('reuses label lookup, item ordering, and count for candidate searches', () => {
    let labelReads = 0
    let nameReads = 0
    const candidate = itemRef('sword', '剣')
    Object.defineProperty(candidate, 'value', { get: () => { labelReads++; return 'sword' } })
    const countedFile = { ...mob }
    Object.defineProperty(countedFile, 'name', { get: () => { nameReads++; return 'モブ' } })
    const files = [countedFile]
    const candidates = [candidate, itemRef('shield', '盾')]
    const currentIndex = dropIndex(['sword', 'shield'])
    const comparisons = vi.spyOn(String.prototype, 'localeCompare')
    try {
      render(<MasterFilterDialog {...props} files={files} allFiles={files} index={currentIndex} candidates={candidates} itemScope dropScope value={emptyMasterListFilters()} onApply={vi.fn()} />)
      const originalLabelReads = labelReads
      const originalNameReads = nameReads
      const originalComparisons = comparisons.mock.calls.length
      expect(originalComparisons).toBeGreaterThan(0)
      fireEvent.change(screen.getByRole('textbox', { name: 'ドロップ品を名前で検索' }), { target: { value: '剣' } })
      fireEvent.change(screen.getByRole('textbox', { name: 'ドロップ元を名前で検索' }), { target: { value: 'モブ' } })
      expect(labelReads).toBe(originalLabelReads)
      expect(nameReads).toBe(originalNameReads)
      expect(comparisons).toHaveBeenCalledTimes(originalComparisons)
      expect(options()).toEqual([{ value: 'sword', text: '剣 (sword)' }])
      fireEvent.change(screen.getByRole('combobox', { name: 'ファイル形式' }), { target: { value: 'yaml' } })
      expect(nameReads).toBeGreaterThan(originalNameReads)
    } finally {
      comparisons.mockRestore()
    }
  })

  it('skips label scans without drop options or scoped IDs and prepares them when enabled', () => {
    let candidateReads = 0
    let fileReads = 0
    const candidate = itemRef('sword', '候補名')
    Object.defineProperty(candidate, 'value', { get: () => { candidateReads++; return 'sword' } })
    const namedFile = itemFile('sword', 'ファイル名')
    Object.defineProperty(namedFile, 'id', { get: () => { fileReads++; return 'sword' } })
    const candidates = [candidate]
    const allFiles = [namedFile]
    const files = [mob]
    const noIds = dropIndex([])
    const withIds = dropIndex(['sword'])
    const base = { ...props, files, allFiles, candidates, itemScope: false, value: emptyMasterListFilters(), onApply: vi.fn() }
    const view = render(<MasterFilterDialog {...base} index={withIds} dropScope={false} />)
    expect(candidateReads).toBe(0)
    expect(fileReads).toBe(0)
    view.rerender(<MasterFilterDialog {...base} index={noIds} dropScope />)
    expect(candidateReads).toBe(0)
    expect(fileReads).toBe(0)
    expect(options()).toEqual([])
    view.rerender(<MasterFilterDialog {...base} index={withIds} dropScope />)
    expect(candidateReads).toBeGreaterThan(0)
    expect(fileReads).toBeGreaterThan(0)
    expect(options()).toEqual([{ value: 'sword', text: '候補名 (sword)' }])
    const preparedReads = [candidateReads, fileReads]
    view.rerender(<MasterFilterDialog {...base} index={withIds} dropScope={false} />)
    expect([candidateReads, fileReads]).toEqual(preparedReads)
  })
})
