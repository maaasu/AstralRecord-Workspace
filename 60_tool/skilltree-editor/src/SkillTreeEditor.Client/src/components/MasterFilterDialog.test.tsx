import { fireEvent, render, screen } from '@testing-library/react'
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
