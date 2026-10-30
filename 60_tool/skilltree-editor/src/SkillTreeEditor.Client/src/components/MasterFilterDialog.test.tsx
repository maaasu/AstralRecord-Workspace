import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MasterFilterDialog } from './MasterFilterDialog'
import { emptyMasterListFilters, type MasterListFilters } from '../data/masterFileQuery'
import type { MasterFileSummary, MasterFilterIndex } from '../types/masterData'

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
