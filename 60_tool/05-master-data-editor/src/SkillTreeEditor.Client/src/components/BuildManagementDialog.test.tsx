import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { editorApi } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import type { NodeMaster, StructureDocument } from '../types/editor'
import type { DesignPreset, ProgressionDesignCatalog } from '../types/progressionDesign'
import { BuildManagementDialog, type BuildAction } from './BuildManagementDialog'

const archer = { id: 'phantom_archer', name: '幻影弓使い', path: 'class/archer.yml', prerequisiteClassIds: [], maxLevel: 50, usableSkillIds: [] }
const warrior = { id: 'warrior', name: '戦士', path: 'class/warrior.yml', prerequisiteClassIds: [], maxLevel: 10, usableSkillIds: ['slash'] }
const preset: DesignPreset = { id: 'phantom_archer_base', name: '既存構成', path: '90.shared.progression_preset/v1.phantom_archer_base.yml',
  classId: archer.id, buildId: 'base', checkpoints: [] }
const catalog: ProgressionDesignCatalog = {
  classes: [archer, warrior], presets: [preset], sources: [], usages: [], addTargets: [],
  items: [
    { id: 'bow', name: '弓', path: 'item/bow.yml', category: 'weapon', rarity: null, icon: 'BOW', customModelData: null, iconTexture: null,
      slot: 'MAIN_HAND', handType: null, requiredLevel: 1, requiredClasses: [], compatibleSlots: ['MAIN_HAND'], maxEnhanceLevel: 0 },
    { id: 'archer_book', name: '射撃教本', path: 'item/book.yml', category: 'equipment', rarity: null, icon: 'BOOK', customModelData: null, iconTexture: null,
      slot: 'SKILLBOOK', handType: null, requiredLevel: 1, requiredClasses: ['phantom_archer'], requiredClassLevels: [{ classId: 'phantom_archer', level: 2 }],
      compatibleSlots: ['SKILLBOOK'], maxEnhanceLevel: 0, usableSkillIds: ['shot'] },
  ],
  nodes: [{ id: 42, name: '射撃の心得', path: 'node/42.yml', icon: 'ARROW', customModelData: null, iconTexture: null, skillIds: ['shot'] }],
  skills: [{ id: 'shot', name: '射撃', path: 'skill/shot.yml', icon: 'BOW', customModelData: null, iconTexture: null,
    passive: false, bindRequired: true, requiredLevel: 1, maxLevel: 1, sigilSlotsByLevel: [], allowedSigilIds: [] }],
}
const node: NodeMaster = { $schema: '', schemaVersion: 1, nodeId: '42', name: '射撃の心得', icon: 'ARROW', tags: [],
  pointType: 'CP', pointCost: 1, effects: [], unlockCondition: { classId: archer.id, playerLevel: 1 } }
const structure: StructureDocument = { $schema: '', schemaVersion: 1, structureId: 'main', name: 'ゲームのツリー', rootNodeId: '42',
  nodes: [{ nodeId: '42', x: 0, y: 0, z: 0 }], edges: [] }

beforeEach(() => {
  vi.spyOn(editorApi, 'listNodes').mockResolvedValue([{ content: node, fileName: '42.json', revision: 'n' }])
  vi.spyOn(editorApi, 'listStructures').mockResolvedValue([{ content: structure, fileName: 'main.json', revision: 's' }])
  vi.spyOn(editorApi, 'listClasses').mockResolvedValue([archer, warrior].map(entry => ({ id: entry.id, name: entry.name, parentClassIds: [] })))
  vi.spyOn(editorApi, 'listSkills').mockResolvedValue([])
  vi.spyOn(editorApi, 'getSettings').mockResolvedValue({ revision: 'c', content: { structureId: 'main', worldName: 'world', centerX: 0, centerY: 0, centerZ: 0 } })
})

function setup(action: BuildAction = 'create') {
  const onClose = vi.fn()
  const onDirtyChange = vi.fn()
  const user = userEvent.setup()
  const view = render(<BuildManagementDialog action={action} catalog={catalog} selectedClass={archer} preset={action === 'create' ? undefined : preset}
    onClose={onClose} onDirtyChange={onDirtyChange} onEdit={vi.fn(async () => true)} onCommitted={vi.fn()} />)
  const dialog = within(screen.getByRole('dialog', { name: action === 'create' ? '新しいビルドを登録' : action === 'duplicate' ? 'ビルドを複製' : 'ビルド名とIDを編集' }))
  return { user, dialog, onClose, onDirtyChange, ...view }
}

afterEach(() => vi.restoreAllMocks())

async function selectLearnedNode(user: ReturnType<typeof userEvent.setup>, dialog: ReturnType<typeof within>) {
  await user.click(dialog.getByRole('button', { name: 'スキルツリーで選択' }))
  const tree = within(screen.getByRole('dialog', { name: 'スキルツリーから習得ノードを選択' }))
  expect(tree.getByText(/プレイヤーLv.1 · 職業Lv.2/)).toBeInTheDocument()
  fireEvent.click(await tree.findByRole('checkbox', { name: '射撃の心得' }))
  expect(tree.getByRole('checkbox', { name: '射撃の心得' })).toHaveAttribute('aria-checked', 'true')
  await user.click(tree.getByRole('button', { name: '選択を反映' }))
}

describe('BuildManagementDialog', () => {
  it('allows a book or node to grant the first skill and invalidates it when the source is removed', async () => {
    const create = vi.spyOn(masterDataApi, 'create')
    const { user, dialog } = setup()
    await user.type(dialog.getByRole('textbox', { name: '表示名' }), '弓の構成')
    await user.type(dialog.getByRole('textbox', { name: 'ビルドID' }), 'starter')
    await user.click(dialog.getByRole('button', { name: 'メインハンドを選択' }))
    await user.click(within(screen.getByRole('dialog', { name: 'メインハンドを選択' })).getByRole('button', { name: /弓/ }))
    await user.click(dialog.getByRole('button', { name: '1番枠のスキルを選択' }))
    const noPermission = within(screen.getByRole('dialog', { name: '1番枠のスキルを選択' }))
    expect(noPermission.getByRole('status')).toHaveTextContent('0件')
    await user.click(noPermission.getByRole('button', { name: 'キャンセル' }))

    await user.click(dialog.getByRole('button', { name: 'スキルブックを選択' }))
    const tooEarly = within(screen.getByRole('dialog', { name: 'スキルブックを選択' }))
    expect(tooEarly.getByRole('status')).toHaveTextContent('0件')
    await user.click(tooEarly.getByRole('button', { name: 'キャンセル' }))
    await user.clear(dialog.getByRole('spinbutton', { name: '初期職業Lv.' }))
    await user.type(dialog.getByRole('spinbutton', { name: '初期職業Lv.' }), '2')
    await user.click(dialog.getByRole('button', { name: 'スキルブックを選択' }))
    const bookPicker = within(screen.getByRole('dialog', { name: 'スキルブックを選択' }))
    expect(bookPicker.getByRole('button', { name: /射撃教本/ }).querySelector('img')).toBeTruthy()
    await user.click(bookPicker.getByRole('button', { name: /射撃教本/ }))
    await user.click(dialog.getByRole('button', { name: '1番枠のスキルを選択' }))
    await user.click(within(screen.getByRole('dialog', { name: '1番枠のスキルを選択' })).getByRole('button', { name: /射撃/ }))
    expect(dialog.getByRole('button', { name: '内容を確認' })).toBeEnabled()

    await user.click(dialog.getByRole('button', { name: 'スキルブックを外す' }))
    expect(dialog.getByRole('button', { name: '内容を確認' })).toBeDisabled()
    expect(dialog.getByRole('alert')).toHaveTextContent('スキルブックでは使用できません')
    await selectLearnedNode(user, dialog)
    expect(dialog.getByRole('button', { name: '内容を確認' })).toBeEnabled()
    expect(create).not.toHaveBeenCalled()
    fireEvent.change(dialog.getByRole('spinbutton', { name: '初期職業Lv.' }), { target: { value: '51' } })
    expect(dialog.getByRole('button', { name: '内容を確認' })).toBeDisabled()
    fireEvent.change(dialog.getByRole('spinbutton', { name: '初期職業Lv.' }), { target: { value: '2' } })
    expect(dialog.getByRole('button', { name: '内容を確認' })).toBeEnabled()
    await user.click(dialog.getByRole('button', { name: '射撃の心得を外す' }))
    expect(dialog.getByRole('button', { name: '内容を確認' })).toBeDisabled()
  })

  it('resets all initial selections after changing class', async () => {
    const { user, dialog } = setup()
    await user.click(dialog.getByRole('button', { name: 'メインハンドを選択' }))
    await user.click(within(screen.getByRole('dialog', { name: 'メインハンドを選択' })).getByRole('button', { name: /弓/ }))
    fireEvent.change(dialog.getByRole('spinbutton', { name: '初期職業Lv.' }), { target: { value: '2' } })
    await user.click(dialog.getByRole('button', { name: 'スキルブックを選択' }))
    await user.click(within(screen.getByRole('dialog', { name: 'スキルブックを選択' })).getByRole('button', { name: /射撃教本/ }))
    await selectLearnedNode(user, dialog)
    await user.click(dialog.getByRole('button', { name: '1番枠のスキルを選択' }))
    await user.click(within(screen.getByRole('dialog', { name: '1番枠のスキルを選択' })).getByRole('button', { name: /射撃/ }))
    await user.selectOptions(dialog.getByRole('combobox', { name: '職業' }), 'warrior')
    expect(dialog.getByRole('spinbutton', { name: '初期職業Lv.' })).toHaveValue(1)
    expect(dialog.getByRole('spinbutton', { name: '初期職業Lv.' })).toHaveAttribute('max', '10')
    expect(dialog.getByRole('button', { name: 'メインハンドを選択' })).toBeInTheDocument()
    expect(dialog.getByRole('button', { name: 'スキルブックを選択' })).toBeInTheDocument()
    expect(dialog.getByRole('button', { name: '1番枠のスキルを選択' })).toBeInTheDocument()
    expect(dialog.queryByText('射撃の心得')).not.toBeInTheDocument()
  })

  it('cancels a tree selection without adding nodes or leaving the build dialog', async () => {
    const create = vi.spyOn(masterDataApi, 'create')
    const { user, dialog, onClose } = setup()
    await user.click(dialog.getByRole('button', { name: 'スキルツリーで選択' }))
    const tree = within(screen.getByRole('dialog', { name: 'スキルツリーから習得ノードを選択' }))
    fireEvent.click(await tree.findByRole('checkbox', { name: '射撃の心得' }))
    await user.click(tree.getByRole('button', { name: 'キャンセル' }))
    expect(screen.queryByRole('dialog', { name: 'スキルツリーから習得ノードを選択' })).not.toBeInTheDocument()
    expect(dialog.queryByRole('button', { name: '射撃の心得を外す' })).not.toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    expect(create).not.toHaveBeenCalled()
  })

  it.each(['create', 'duplicate', 'edit'] as const)('confirms edited %s input before Escape or Cancel closes it', async action => {
    const { user, dialog, onClose } = setup(action)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await user.type(dialog.getByRole('textbox', { name: '表示名' }), '変更')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(confirm).toHaveBeenCalledOnce()
    expect(onClose).not.toHaveBeenCalled()
    await user.click(dialog.getByRole('button', { name: 'キャンセル' }))
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(onClose).not.toHaveBeenCalled()
    confirm.mockReturnValue(true)
    await user.click(dialog.getByRole('button', { name: 'キャンセル' }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('warns on browser close only while input is edited and clears the guard when unmounted', async () => {
    const { user, dialog, onDirtyChange, unmount } = setup()
    const unload = () => {
      const event = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(event)
      return event.defaultPrevented
    }
    expect(unload()).toBe(false)
    await user.type(dialog.getByRole('textbox', { name: '表示名' }), '下書き')
    expect(onDirtyChange).toHaveBeenLastCalledWith(true)
    expect(unload()).toBe(true)
    unmount()
    expect(onDirtyChange).toHaveBeenLastCalledWith(false)
    expect(unload()).toBe(false)
  })
})
