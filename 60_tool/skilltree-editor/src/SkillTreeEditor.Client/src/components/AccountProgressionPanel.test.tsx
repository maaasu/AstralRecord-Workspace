import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AccountProgressionPanel } from './AccountProgressionPanel'
import type { DesignCheckpoint, DesignPreset, DesignSkill, ProgressionDesignCatalog } from '../types/progressionDesign'

const skill: DesignSkill = { id: 'slash', name: '剣撃', path: '30.features.skill/v1.slash.yml', icon: 'IRON_SWORD', iconTexture: null, customModelData: null,
  passive: false, bindRequired: true, requiredLevel: 1, maxLevel: 5, sigilSlotsByLevel: [{ level: 1, slots: 1 }], allowedSigilIds: [] }
const checkpoint: DesignCheckpoint = { level: 10, classLevel: 10, pointer: '/levels/0', equipment: [], nodes: [1],
  skills: [{ slot: 1, skillId: 'slash', pointer: '/levels/0/skills/0', skillLevel: 1, sigils: [] }], passives: [] }
const preset: DesignPreset = { id: 'sword', name: '剣士', path: '90.shared.progression_preset/v1.sword.yml', classId: 'sword', buildId: 'sword', checkpoints: [checkpoint] }
const catalog: ProgressionDesignCatalog = { classes: [{ id: 'sword', name: '剣士', path: 'class.yml', prerequisiteClassIds: [], usableSkillIds: ['slash'] }],
  presets: [preset], skills: [skill, { ...skill, id: 'meteor', name: '流星魔術' }, { ...skill, id: 'node_skill', name: 'ノードの技' }],
  nodes: [{ id: 1, name: '技の習得', path: 'node.json', icon: null, iconTexture: null, customModelData: null, skillIds: ['node_skill'] }],
  items: [], sources: [], usages: [], addTargets: [] }

function show(data = catalog, row = checkpoint) {
  const edit = vi.fn().mockResolvedValue(true)
  render(<AccountProgressionPanel catalog={data} preset={preset} checkpoint={row} disabled={false} renderNumber={() => null} edit={edit} onExploreItem={vi.fn()} />)
  fireEvent.click(screen.getByText('アカウント進行設定'))
  return edit
}

describe('account progression safety', () => {
  it('bounds generated sigil slots while keeping sparse existing slots removable', () => {
    show({ ...catalog, skills: [{ ...skill, sigilSlotsByLevel: [{ level: 1, slots: 2147483647 }] }] },
      { ...checkpoint, skills: [{ ...checkpoint.skills![0], sigils: [{ slot: 2147483647, itemId: 'missing', pointer: '/levels/0/skills/0/sigils/0' }] }] })
    const group = screen.getByRole('group', { name: 'アクションリング 1番枠のシジル' })
    expect(within(group).getAllByRole('button', { name: '設定' })).toHaveLength(24)
    expect(within(group).getByText('シジル 2147483647')).toBeVisible()
    expect(within(group).getByRole('button', { name: '外す' })).toBeEnabled()
    expect(screen.getByText(/解放枠は先頭24枠まで/)).toBeVisible()
  })

  it('keeps free passive attachments removable but prevents assigning new ones', () => {
    show({ ...catalog, skills: [{ ...skill, passive: true, bindRequired: false }] },
      { ...checkpoint, skills: [], passives: [{ ...checkpoint.skills![0], pointer: '/levels/0/passives/0', sigils: [{ slot: 1, itemId: 'old', pointer: '/levels/0/passives/0/sigils/0' }] }] })
    const group = screen.getByRole('group', { name: 'パッシブ 1番枠のシジル' })
    expect(within(group).getByRole('button', { name: '変更' })).toBeDisabled()
    expect(within(group).getByRole('button', { name: '外す' })).toBeEnabled()
    expect(screen.getByText(/バインド不要のパッシブは/)).toBeVisible()
  })

  it('offers class and learned-node skills and marks foreign skills incompatible', () => {
    show()
    fireEvent.click(within(screen.getByRole('article', { name: 'アクションリング 1番枠' })).getByRole('button', { name: '変更' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('button', { name: /ノードの技/ })).toBeEnabled()
    expect(within(dialog).queryByRole('button', { name: /流星魔術/ })).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('checkbox', { name: /選択できない候補も表示/ }))
    expect(within(dialog).getByRole('button', { name: /流星魔術/ })).toBeDisabled()
  })
})
