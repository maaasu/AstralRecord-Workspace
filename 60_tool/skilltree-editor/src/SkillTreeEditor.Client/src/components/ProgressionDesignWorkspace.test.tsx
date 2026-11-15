import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import { progressionDesignApi } from '../api/progressionDesignApi'
import type { JsonValue } from '../types/editor'
import type { DesignItem, DesignPreset, DesignSource, ProgressionDesignCatalog } from '../types/progressionDesign'
import { ProgressionDesignWorkspace } from './ProgressionDesignWorkspace'

const buildPath = 'presets/knight.yml'
const shopPath = '45.features.shop/merchant.yml'
const poolPath = '80.shared.loot/pool.yml'
const recipePath = '85.shared.recipe/forge.yml'
const mobPath = 'mobs/skeleton.yml'
const shopContent = { offers: [{ amount: 2 }, { amount: 6 }] }
const poolContent = { rolls: 2, pick: '1~2', amount: 2 }
const baseDrop = { itemId: 'item:bone', rate: 35, amount: 2 }
const mobContent = { drops: { items: [baseDrop] }, levels: [{ level: 10, drops: { rolls: 3 } }] }
const buildContent = { levels: [
  { level: 1, equipment: [{ slot: 'MAIN_HAND', item: 'item:old_sword', enhanceLevel: 4 }] },
  { level: 10, equipment: [{ slot: 'MAIN_HAND', item: 'item:old_sword', enhanceLevel: 4 }] },
] }
const raw = (content: JsonValue) => JSON.stringify(content)
const documents: Record<string, JsonValue> = {
  [recipePath]: { result: { amount: 1 } },
  [buildPath]: buildContent, [shopPath]: shopContent, [poolPath]: poolContent, [mobPath]: mobContent,
}
const savedDocument = (path: string, content: JsonValue, revision = 'original') =>
  ({ path, content, revision, raw: raw(content), format: 'yaml', issues: [] })

const sword = (id: string, name: string, maxEnhanceLevel: number): DesignItem => ({
  path: `items/${id}.yml`, id, name, category: 'weapon', rarity: null, icon: 'IRON_SWORD',
  customModelData: null, iconTexture: null, slot: 'MAIN_HAND', handType: null, requiredLevel: 1,
  requiredClasses: [], compatibleSlots: ['MAIN_HAND'], maxEnhanceLevel,
})
const source = (id: string, name: string, kind: string, path: string, pointer: string): DesignSource => ({
  id, name, kind, path, pointer, itemId: 'old_sword', itemPath: path, itemPointer: `${pointer}/itemId`,
  quantity: [], rates: [], costs: [], ingredients: [], ingredientContainer: null, context: [],
  level: null, mode: null, availability: 'configured', warnings: [],
})
const knight: DesignPreset = {
  id: 'knight', name: '防御型', path: buildPath, classId: 'warrior', buildId: 'knight',
  checkpoints: buildContent.levels.map(({ level }, index) => ({
    level, classLevel: level, pointer: `/levels/${index}`,
    equipment: [{ slot: 'MAIN_HAND', itemId: 'old_sword', pointer: `/levels/${index}/equipment/0`, enhanceLevel: 4 }],
  })),
}
const fixture: ProgressionDesignCatalog = {
  revisions: Object.fromEntries(Object.keys(documents).map(path => [path, 'original'])),
  classes: [
    { id: 'mage', name: '魔術師', path: 'classes/mage.yml', prerequisiteClassIds: [] },
    { id: 'warrior', name: '戦士', path: 'classes/warrior.yml', prerequisiteClassIds: [] },
  ],
  presets: [
    { ...knight, id: 'mage-preset', classId: 'mage', name: '魔術型', path: 'presets/mage.yml' },
    { ...knight, id: 'assault', name: '攻撃型', path: 'presets/assault.yml' }, knight,
  ],
  items: [sword('old_sword', '鉄の剣', 5), sword('new_sword', '鋼の剣', 2)],
  sources: [
    { ...source('shop:1', '駅前の店', 'shop', shopPath, '/offers/0'), quantity: [{ path: shopPath, pointer: '/offers/0/amount', value: 2 }] },
    { ...source('shop:2', '市場の店', 'shop', shopPath, '/offers/1'), quantity: [{ path: shopPath, pointer: '/offers/1/amount', value: 6 }] },
    { ...source('recipe:1', '鍛冶レシピ', 'recipe', recipePath, '/result'), quantity: [{ path: recipePath, pointer: '/result/amount', value: 1 }] },
    { ...source('pool:1', '共有の宝箱', 'lootPool', poolPath, ''), quantity: [{ path: poolPath, pointer: '/amount', value: 2 }], rates: [
      { path: poolPath, pointer: '/rolls', value: 2 },
      { path: poolPath, pointer: '/pick', value: '1~2' },
    ] },
  ],
  usages: [],
  addTargets: [{ id: 'skeleton:level:10', kind: 'mob', name: '骸骨兵', path: mobPath, pointer: '/levels/0/drops/items', level: 10 }],
}

const inspector = () => within(screen.getByRole('complementary', { name: '選択した項目の詳細' }))
const saveButton = () => screen.getByRole('button', { name: '変更を確認して保存' })
const addSource = () => inspector().getByRole('button', { name: /入手先を設定/ })
const changeWeapon = () => within(screen.getByRole('group', { name: '部位別の装備' })).getByRole('button', { name: /装備を変更/ })

async function loadWorkspace() {
  const user = userEvent.setup()
  render(<ProgressionDesignWorkspace onOpenMaster={vi.fn()} />)
  const job = await screen.findByRole('combobox', { name: '職業' })
  await waitFor(() => expect(progressionDesignApi.catalog).toHaveBeenCalledTimes(2))
  await waitFor(() => expect(job).toBeEnabled())
  await user.selectOptions(job, 'warrior')
  await user.selectOptions(screen.getByRole('combobox', { name: 'ビルド' }), 'knight')
  await user.selectOptions(screen.getByRole('combobox', { name: '適用開始レベル' }), '10')
  return user
}

describe('ProgressionDesignWorkspace', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    vi.spyOn(progressionDesignApi, 'catalog').mockResolvedValue(fixture)
    vi.spyOn(progressionDesignApi, 'preview').mockResolvedValue(fixture)
    vi.spyOn(masterDataApi, 'catalog').mockResolvedValue({ root: 'integration-workspace', backups: '', categories: [] })
    vi.spyOn(masterDataApi, 'file').mockImplementation(async path => savedDocument(path, documents[path]))
    vi.spyOn(masterDataApi, 'render').mockImplementation(async (_path, content) => ({ raw: raw(content), commentsPreserved: true, warnings: [] }))
    vi.spyOn(masterDataApi, 'validate').mockResolvedValue({ isValid: true, issues: [] })
    vi.spyOn(masterDataApi, 'save').mockImplementation(async (path, content) => savedDocument(path, JSON.parse(content), 'written'))
  })

  afterEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
    localStorage.clear()
  })

  it('registers a first build for a class after reviewing its required level-one choices', async () => {
    const emptyClassCatalog: ProgressionDesignCatalog = { ...fixture,
      classes: [...fixture.classes, { id: 'ranger', name: '弓使い', path: 'classes/ranger.yml', prerequisiteClassIds: [], usableSkillIds: ['shot'] }],
      skills: [{ id: 'shot', name: '射撃', path: 'skills/shot.yml', icon: 'BOW', customModelData: null, iconTexture: null, passive: false, bindRequired: true, requiredLevel: 1, maxLevel: 10, sigilSlotsByLevel: [], allowedSigilIds: [] }],
    }
    vi.mocked(progressionDesignApi.catalog).mockResolvedValue(emptyClassCatalog)
    vi.mocked(progressionDesignApi.preview).mockResolvedValue(emptyClassCatalog)
    vi.spyOn(masterDataApi, 'create').mockImplementation(async draft => savedDocument(draft.path, JSON.parse(draft.raw)))
    const user = userEvent.setup()
    render(<ProgressionDesignWorkspace onOpenMaster={vi.fn()} />)
    await waitFor(() => expect(screen.getByRole('combobox', { name: '職業' })).toBeEnabled())
    await waitFor(() => expect(progressionDesignApi.catalog).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByRole('button', { name: '＋ 新規ビルド' })).toBeEnabled())
    await user.selectOptions(screen.getByRole('combobox', { name: '職業' }), 'ranger')
    expect(screen.getByRole('combobox', { name: '職業' })).toHaveValue('ranger')
    expect(screen.getByRole('combobox', { name: 'ビルド' })).toHaveValue('')
    await user.click(screen.getByRole('button', { name: '＋ 新規ビルド' }))
    const dialog = within(screen.getByRole('dialog', { name: '新しいビルドを登録' }))
    await user.type(dialog.getByRole('textbox', { name: '表示名' }), '弓の基本')
    await user.type(dialog.getByRole('textbox', { name: 'ビルドID' }), 'basic')
    await user.click(dialog.getByRole('button', { name: 'メインハンドを選択' }))
    await user.click(within(screen.getByRole('dialog', { name: 'メインハンドを選択' })).getByRole('button', { name: /鉄の剣/ }))
    await user.click(dialog.getByRole('button', { name: '1番枠のスキルを選択' }))
    await user.click(within(screen.getByRole('dialog', { name: '1番枠のスキルを選択' })).getByRole('button', { name: /射撃/ }))
    await user.click(dialog.getByRole('button', { name: '内容を確認' }))
    await waitFor(() => expect(masterDataApi.validate).toHaveBeenCalled())
    expect(masterDataApi.create).not.toHaveBeenCalled()
    await user.click(dialog.getByRole('button', { name: 'このビルドを登録' }))
    await waitFor(() => expect(masterDataApi.create).toHaveBeenCalledWith(expect.objectContaining({ path: '90.shared.progression_preset/v1.ranger_basic.yml', raw: expect.stringContaining('"classId":"ranger"') })))
    expect(screen.getByText('ビルドを登録しました。')).toBeInTheDocument()
  })

  it('requires deletion review and does not repeat deletion when list refresh fails', async () => {
    const user = await loadWorkspace()
    vi.spyOn(masterDataApi, 'delete').mockResolvedValue(undefined)
    await user.click(screen.getByRole('button', { name: '削除' }))
    const dialog = within(screen.getByRole('dialog', { name: 'ビルドを削除' }))
    expect(dialog.getByText(/防御型/)).toBeInTheDocument()
    await user.click(dialog.getByRole('button', { name: '内容を確認' }))
    expect(masterDataApi.delete).not.toHaveBeenCalled()
    vi.mocked(progressionDesignApi.catalog).mockRejectedValueOnce(new Error('一覧に接続できません'))
    await user.click(dialog.getByRole('button', { name: 'このビルドを削除' }))
    await waitFor(() => expect(masterDataApi.delete).toHaveBeenCalledExactlyOnceWith(buildPath, 'original'))
    expect(await screen.findByText(/ビルドを削除しました/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '一覧を再取得' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '＋ 新規ビルド' })).toBeEnabled())
    expect(masterDataApi.delete).toHaveBeenCalledTimes(1)
  })

  it('adds a directly entered level by copying the currently selected checkpoint into the shared draft', async () => {
    const user = await loadWorkspace()
    await user.type(screen.getByRole('spinbutton', { name: 'レベルを直接入力' }), '12')
    await user.click(screen.getByRole('button', { name: '移動・追加' }))
    const dialog = within(screen.getByRole('dialog', { name: 'レベル段階を追加' }))
    expect(dialog.getByRole('spinbutton', { name: '適用開始レベル' })).toHaveValue(12)
    await user.click(dialog.getByRole('button', { name: '段階を追加' }))
    await waitFor(() => expect(progressionDesignApi.preview).toHaveBeenCalled())
    const draft = vi.mocked(progressionDesignApi.preview).mock.calls.at(-1)?.[0]
    expect(draft?.[0].path).toBe(buildPath)
    const updated = draft![0].content as typeof buildContent
    expect(updated.levels.map(entry => entry.level)).toEqual([1, 10, 12])
    expect(updated.levels[2].equipment).toEqual(buildContent.levels[1].equipment)
    expect(saveButton()).toBeEnabled()
  })

  it('keeps build name and build ID edits in the shared undoable draft and blocks file operations while dirty', async () => {
    const user = await loadWorkspace()
    await user.click(screen.getByRole('button', { name: '名前・IDを編集' }))
    const dialog = within(screen.getByRole('dialog', { name: 'ビルド名とIDを編集' }))
    await user.clear(dialog.getByRole('textbox', { name: '表示名' }))
    await user.type(dialog.getByRole('textbox', { name: '表示名' }), '新しい名前')
    await user.clear(dialog.getByRole('textbox', { name: 'ビルドID' }))
    await user.type(dialog.getByRole('textbox', { name: 'ビルドID' }), 'new_build')
    await user.click(dialog.getByRole('button', { name: '下書きに反映' }))
    await waitFor(() => expect(saveButton()).toBeEnabled())
    expect(screen.getByRole('button', { name: '＋ 新規ビルド' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '削除' })).toBeDisabled()
    const draft = vi.mocked(progressionDesignApi.preview).mock.calls.at(-1)?.[0]
    expect(draft?.[0].content).toEqual({ ...buildContent, name: '新しい名前', buildId: 'new_build' })
    await user.click(screen.getByRole('button', { name: '元に戻す' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '＋ 新規ビルド' })).toBeEnabled())
  })

  it('duplicates the selected build only after a reviewed source revision check', async () => {
    const user = await loadWorkspace()
    vi.spyOn(masterDataApi, 'create').mockImplementation(async draft => savedDocument(draft.path, JSON.parse(draft.raw)))
    await user.click(screen.getByRole('button', { name: '複製' }))
    const dialog = within(screen.getByRole('dialog', { name: 'ビルドを複製' }))
    await user.click(dialog.getByRole('button', { name: '内容を確認' }))
    await waitFor(() => expect(masterDataApi.file).toHaveBeenCalledWith(buildPath))
    expect(masterDataApi.create).not.toHaveBeenCalled()
    await user.click(dialog.getByRole('button', { name: 'このビルドを登録' }))
    await waitFor(() => expect(masterDataApi.create).toHaveBeenCalledWith(expect.objectContaining({ path: '90.shared.progression_preset/v1.warrior_knight_copy.yml' })))
  })

  it('keeps canceled equipment unchanged and saves a replacement only after explicit confirmation', async () => {
    const user = await loadWorkspace()
    vi.mocked(progressionDesignApi.preview).mockResolvedValueOnce({ ...fixture, presets: fixture.presets.map(preset => preset.id !== knight.id ? preset : {
      ...preset, checkpoints: preset.checkpoints.map(checkpoint => checkpoint.level !== 10 ? checkpoint : {
        ...checkpoint, equipment: checkpoint.equipment.map(gear => ({ ...gear, itemId: 'new_sword', enhanceLevel: 2 })),
      }),
    }) })
    await user.click(changeWeapon())
    let picker = within(screen.getByRole('dialog', { name: '武器を選択' }))
    expect(picker.getByRole('button', { name: /鉄の剣/ })).toHaveAttribute('aria-pressed', 'true')
    await user.click(picker.getByRole('button', { name: 'キャンセル' }))
    expect(progressionDesignApi.preview).not.toHaveBeenCalled()
    expect(masterDataApi.file).not.toHaveBeenCalled()

    await user.click(changeWeapon())
    picker = within(screen.getByRole('dialog', { name: '武器を選択' }))
    await user.click(picker.getByRole('button', { name: /鋼の剣/ }))
    const expected = { levels: [
      buildContent.levels[0],
      { level: 10, equipment: [{ slot: 'MAIN_HAND', item: 'item:new_sword', enhanceLevel: 2 }] },
    ] }
    await waitFor(() => expect(progressionDesignApi.preview).toHaveBeenCalled())
    expect(vi.mocked(progressionDesignApi.preview).mock.calls[0][0]).toEqual([{ path: buildPath, content: expected, revision: 'original' }])
    expect(masterDataApi.save).not.toHaveBeenCalled()

    expect(await inspector().findByRole('heading', { name: '鋼の剣' })).toBeInTheDocument()
    await waitFor(() => expect(saveButton()).toBeEnabled())
    await user.click(saveButton())
    const confirm = within(await screen.findByRole('dialog', { name: '装備・進行設計の変更を確認' }))
    expect(confirm.getByText(buildPath)).toBeInTheDocument()
    expect(masterDataApi.save).not.toHaveBeenCalled()
    await user.click(confirm.getByRole('button', { name: '1件を保存' }))
    await waitFor(() => expect(masterDataApi.save).toHaveBeenCalledExactlyOnceWith(buildPath, raw(expected), 'original'))
  })

  it('edits the selected duplicate item route and presents shared rolls and pick as count fields', async () => {
    const user = await loadWorkspace()
    await user.click(inspector().getByRole('button', { name: /市場の店/ }))
    const editor = within(inspector().getByRole('region', { name: '入手先の設定' }))
    const quantity = editor.getByRole('textbox', { name: '獲得数 /offers/1/amount' })
    expect(quantity).toHaveValue('6')
    fireEvent.change(quantity, { target: { value: '12' } })

    await waitFor(() => expect(progressionDesignApi.preview).toHaveBeenCalled())
    expect(vi.mocked(progressionDesignApi.preview).mock.calls[0][0]).toEqual([
      { path: shopPath, revision: 'original', content: { offers: [{ amount: 2 }, { amount: 12 }] } },
    ])
    await waitFor(() => expect(saveButton()).toBeEnabled())
    await user.click(inspector().getByRole('button', { name: /共有の宝箱/ }))
    expect(editor.getByRole('textbox', { name: '抽選回数 /rolls' })).toHaveValue('2')
    expect(editor.getByRole('textbox', { name: '抽選する件数 /pick' })).toHaveValue('1~2')
    expect(editor.queryByRole('textbox', { name: /設定確率/ })).not.toBeInTheDocument()
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('adds a level-specific drop without replacing inherited drops and retries a failed preview with the same draft', async () => {
    const user = await loadWorkspace()
    let rejectPreview!: (reason: Error) => void
    vi.mocked(progressionDesignApi.preview).mockReturnValueOnce(
      new Promise((_resolve, reject) => { rejectPreview = reject }),
    )
    await user.click(addSource())
    const picker = within(screen.getByRole('dialog', { name: '入手先を選択' }))
    await user.click(picker.getByRole('button', { name: /骸骨兵.*Lv\.10/ }))
    await waitFor(() => expect(progressionDesignApi.preview).toHaveBeenCalledTimes(1))
    expect(addSource()).toBeDisabled()
    expect(changeWeapon()).toBeDisabled()
    expect(saveButton()).toBeDisabled()

    const payload = [{
      path: mobPath, revision: 'original',
      content: { ...mobContent, levels: [{ level: 10, drops: {
        rolls: 3, items: [baseDrop, { itemId: 'item:old_sword', rate: 100, amount: 1, luckAffected: true, hidden: false }],
      } }] },
    }]
    expect(vi.mocked(progressionDesignApi.preview).mock.calls[0][0]).toEqual(payload)
    rejectPreview(new Error('一時的な通信エラー'))
    expect(await screen.findByRole('alert')).toHaveTextContent('一時的な通信エラー')
    expect(saveButton()).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '下書きを保持して図を再取得' }))
    await waitFor(() => expect(progressionDesignApi.preview).toHaveBeenCalledTimes(2))
    expect(vi.mocked(progressionDesignApi.preview).mock.calls[1][0]).toEqual(payload)
    await waitFor(() => expect(saveButton()).toBeEnabled())
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })
  it('locks source quantity inputs immediately through each delayed undo and redo refresh', async () => {
    const user = await loadWorkspace()
    await user.click(inspector().getByRole('button', { name: /市場の店/ }))
    const getQuantity = () => inspector().getByRole('textbox', { name: '獲得数 /offers/1/amount' })
    const updated = {
      ...fixture,
      sources: fixture.sources.map(entry => entry.id === 'shop:2' ? {
        ...entry, quantity: entry.quantity.map(field => ({ ...field, value: 12 })),
      } : entry),
    }
    vi.mocked(progressionDesignApi.preview).mockResolvedValueOnce(updated)
    await user.click(getQuantity())
    fireEvent.change(getQuantity(), { target: { value: '12' } })
    await user.tab()
    await waitFor(() => expect(saveButton()).toBeEnabled())
    const undo = screen.getByRole('button', { name: '元に戻す' })
    const redo = screen.getByRole('button', { name: 'やり直す' })

    let finishUndo!: (catalog: ProgressionDesignCatalog) => void
    vi.mocked(progressionDesignApi.catalog).mockReturnValueOnce(new Promise(resolve => { finishUndo = resolve }))
    expect(undo).toBeEnabled()
    fireEvent.click(undo)
    expect(getQuantity()).toBeDisabled()
    await waitFor(() => expect(progressionDesignApi.catalog).toHaveBeenCalledTimes(3))
    expect(getQuantity()).toBeDisabled()
    finishUndo(fixture)
    await waitFor(() => expect(getQuantity()).toHaveValue('6'))
    expect(getQuantity()).toBeEnabled()

    let finishRedo!: (catalog: ProgressionDesignCatalog) => void
    vi.mocked(progressionDesignApi.preview).mockReturnValueOnce(new Promise(resolve => { finishRedo = resolve }))
    expect(redo).toBeEnabled()
    fireEvent.click(redo)
    expect(getQuantity()).toBeDisabled()
    await waitFor(() => expect(progressionDesignApi.preview).toHaveBeenCalledTimes(2))
    expect(getQuantity()).toBeDisabled()
    finishRedo(updated)
    await waitFor(() => expect(getQuantity()).toHaveValue('12'))
    expect(getQuantity()).toBeEnabled()
  })

  it('lets an operator export after a revision conflict and discard only after confirming reload', async () => {
    const user = await loadWorkspace()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    const exportUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:design-draft')
    const releaseUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const download = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    vi.mocked(progressionDesignApi.preview).mockRejectedValueOnce(new ApiError('保存済み原稿が変更されました', 409))

    await user.click(inspector().getByRole('button', { name: /市場の店/ }))
    fireEvent.change(inspector().getByRole('textbox', { name: '獲得数 /offers/1/amount' }), { target: { value: '12' } })
    const alert = within(await screen.findByRole('alert'))
    expect(alert.getByText(/保存済み原稿が変更されました/)).toBeInTheDocument()
    await user.click(alert.getByRole('button', { name: '下書きを書き出す' }))
    expect(exportUrl).toHaveBeenCalledExactlyOnceWith(expect.any(Blob))
    expect(download).toHaveBeenCalledOnce()
    expect(releaseUrl).toHaveBeenCalledExactlyOnceWith('blob:design-draft')
    expect(saveButton()).toBeDisabled()

    const reload = screen.getByRole('button', { name: '再読込' })
    expect(reload).toBeEnabled()
    await user.click(reload)
    expect(confirm).toHaveBeenCalledOnce()
    expect(screen.getByText('1ファイル 未保存')).toBeInTheDocument()
    expect(progressionDesignApi.catalog).not.toHaveBeenCalledWith(true)

    await user.click(reload)
    expect(confirm).toHaveBeenCalledTimes(2)
    await waitFor(() => expect(progressionDesignApi.catalog).toHaveBeenCalledWith(true))
    expect(screen.getByText('保存済み')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('accepts an open-ended shared loot amount while rejecting amount ranges in shop and recipe entries', async () => {
    const user = await loadWorkspace()
    for (const name of [/市場の店/, /鍛冶レシピ/]) {
      await user.click(inspector().getByRole('button', { name }))
      const amount = inspector().getByRole('textbox', { name: /^獲得数/ })
      fireEvent.change(amount, { target: { value: '1~3' } })
      expect(amount).toHaveAttribute('aria-invalid', 'true')
      expect(saveButton()).toBeDisabled()
      fireEvent.keyDown(amount, { key: 'Escape' })
      expect(amount).toHaveAttribute('aria-invalid', 'false')
    }
    expect(masterDataApi.file).not.toHaveBeenCalled()
    await user.click(inspector().getByRole('button', { name: /共有の宝箱/ }))
    const amount = inspector().getByRole('textbox', { name: '獲得数 /amount' })
    fireEvent.change(amount, { target: { value: '1~0' } })
    expect(amount).toHaveAttribute('aria-invalid', 'false')
    await waitFor(() => expect(progressionDesignApi.preview).toHaveBeenCalledTimes(1))
    expect(vi.mocked(progressionDesignApi.preview).mock.calls[0][0]).toEqual([
      { path: poolPath, revision: 'original', content: { ...poolContent, amount: '1~0' } },
    ])
  })

  it('edits account progression at the selected checkpoint and saves it with equipment intact', async () => {
    const accountContent = { levels: [
      { ...buildContent.levels[0], classLevel: 1, nodes: [10], skills: [{ slot: 1, skill: 'skill:slash', skillLevel: 1 }], passives: [] },
      { ...buildContent.levels[1], classLevel: 20, nodes: [10], skills: [{ slot: 1, skill: 'skill:slash', skillLevel: 1 }], passives: [] },
    ] }
    const accountFixture: ProgressionDesignCatalog = {
      ...fixture,
      classes: fixture.classes.map(entry => entry.id === 'warrior' ? { ...entry, maxLevel: 40 } : entry),
      nodes: [{ id: 10, name: '基礎', path: 'nodes/10.json', icon: 'STONE', iconTexture: null, customModelData: null }, { id: 12, name: '鉄壁', path: 'nodes/12.json', icon: 'SHIELD', iconTexture: null, customModelData: null }],
      skills: [{ id: 'slash', name: '斬撃', path: 'skills/slash.yml', icon: 'IRON_SWORD', iconTexture: null, customModelData: null, passive: false, bindRequired: true, requiredLevel: 1, maxLevel: 3, sigilSlotsByLevel: [{ level: 1, slots: 1 }], allowedSigilIds: [] },
        { id: 'burst', name: '連撃', path: 'skills/burst.yml', icon: 'DIAMOND_SWORD', iconTexture: null, customModelData: null, passive: false, bindRequired: true, requiredLevel: 1, maxLevel: 3, sigilSlotsByLevel: [{ level: 1, slots: 1 }], allowedSigilIds: ['sigil_red'] },
        { id: 'guard', name: '防御術', path: 'skills/guard.yml', icon: 'SHIELD', iconTexture: null, customModelData: null, passive: true, bindRequired: true, requiredLevel: 1, maxLevel: 2, sigilSlotsByLevel: [], allowedSigilIds: [] }],
      items: [...fixture.items, { ...sword('sigil_red', '赤のシジル', 0), category: 'sigil', sigilEquipGroupId: 'red' }],
      presets: fixture.presets.map(entry => entry.id !== 'knight' ? entry : { ...entry, checkpoints: entry.checkpoints.map(checkpoint => checkpoint.level !== 10 ? checkpoint : {
        ...checkpoint, classLevel: 20, nodes: [10], skills: [{ slot: 1, skillId: 'slash', pointer: '/levels/1/skills/0', skillLevel: 1, sigils: [] }], passives: [],
      }) }),
    }
    vi.mocked(progressionDesignApi.catalog).mockResolvedValue(accountFixture)
    vi.mocked(masterDataApi.file).mockImplementation(async path => savedDocument(path, path === buildPath ? accountContent : documents[path]))
    vi.mocked(progressionDesignApi.preview).mockImplementation(async entries => {
      const content = entries.find(entry => entry.path === buildPath)?.content as { levels: { classLevel: number; nodes: number[]; skills: { slot: number; skill: string; skillLevel: number; sigils?: { slot: number; item: string }[] }[]; passives: { slot: number; skill: string; skillLevel: number }[] }[] } | undefined
      if (!content) return accountFixture
      const row = content.levels[1]
      return { ...accountFixture, presets: accountFixture.presets.map(entry => entry.id !== 'knight' ? entry : {
        ...entry, checkpoints: entry.checkpoints.map(checkpoint => checkpoint.level !== 10 ? checkpoint : {
          ...checkpoint, classLevel: row.classLevel, nodes: row.nodes,
          skills: row.skills.map((skill, index) => ({ slot: skill.slot, skillId: skill.skill.replace(/^skill:/, ''), skillLevel: skill.skillLevel, pointer: `/levels/1/skills/${index}`,
            sigils: (skill.sigils ?? []).map((sigil, sigilIndex) => ({ slot: sigil.slot, itemId: sigil.item.replace(/^item:/, ''), pointer: `/levels/1/skills/${index}/sigils/${sigilIndex}` })) })),
          passives: row.passives.map((skill, index) => ({ slot: skill.slot, skillId: skill.skill.replace(/^skill:/, ''), skillLevel: skill.skillLevel, pointer: `/levels/1/passives/${index}`, sigils: [] })),
        }),
      }) }
    })
    const user = await loadWorkspace()
    const account = screen.getByText('アカウント進行設定').closest('details')!
    await user.click(screen.getByText('アカウント進行設定'))
    const section = within(account)
    const classLevel = section.getByRole('textbox', { name: '職業レベル /levels/1/classLevel' })
    fireEvent.change(classLevel, { target: { value: '41' } })
    expect(classLevel).toHaveAttribute('aria-invalid', 'true')
    expect(saveButton()).toBeDisabled()
    fireEvent.keyDown(classLevel, { key: 'Escape' })
    fireEvent.change(classLevel, { target: { value: '25' } })
    await waitFor(() => expect(progressionDesignApi.preview).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(screen.getByRole('button', { name: '元に戻す' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: '元に戻す' }))
    await waitFor(() => expect(classLevel).toHaveValue('20'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'やり直す' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'やり直す' }))
    await waitFor(() => expect(classLevel).toHaveValue('25'))
    await waitFor(() => expect(section.getByRole('button', { name: /ノードを追加/ })).toBeEnabled())
    await user.click(section.getByRole('button', { name: /ノードを追加/ }))
    await user.click(within(screen.getByRole('dialog', { name: '習得ノードを追加' })).getByRole('button', { name: /鉄壁/ }))
    await waitFor(() => expect(section.getByText('鉄壁')).toBeInTheDocument())
    await user.click(within(section.getByRole('article', { name: 'アクションリング 1番枠' })).getByRole('button', { name: '変更' }))
    await user.click(within(screen.getByRole('dialog', { name: 'アクションリング 1番枠のスキル' })).getByRole('button', { name: /連撃/ }))
    await waitFor(() => expect(section.getByText('連撃')).toBeInTheDocument())
    await user.click(within(section.getByRole('group', { name: 'アクションリング 1番枠のシジル' })).getByRole('button', { name: '設定' }))
    await user.click(within(screen.getByRole('dialog', { name: 'シジル 1番枠を選択' })).getByRole('button', { name: /赤のシジル/ }))
    await waitFor(() => expect(section.getByText('赤のシジル')).toBeInTheDocument())
    await user.click(section.getByRole('button', { name: /パッシブ 1番枠/ }))
    await user.click(within(screen.getByRole('dialog', { name: 'パッシブ 1番枠のスキル' })).getByRole('button', { name: /防御術/ }))
    await waitFor(() => expect(section.getByText('防御術')).toBeInTheDocument())
    await waitFor(() => expect(saveButton()).toBeEnabled())
    await user.click(saveButton())
    await user.click(within(await screen.findByRole('dialog', { name: '装備・進行設計の変更を確認' })).getByRole('button', { name: '1件を保存' }))
    await waitFor(() => expect(masterDataApi.save).toHaveBeenCalledOnce())
    const saved = JSON.parse(vi.mocked(masterDataApi.save).mock.calls[0][1]) as typeof accountContent
    expect(saved.levels[0]).toEqual(accountContent.levels[0])
    expect(saved.levels[1]).toEqual({ ...accountContent.levels[1], classLevel: 25, nodes: [10, 12], skills: [{ slot: 1, skill: 'skill:burst', skillLevel: 1, sigils: [{ slot: 1, item: 'item:sigil_red' }] }], passives: [{ slot: 1, skill: 'skill:guard', skillLevel: 1 }] })
  })

  it('resizes the graph inspector by keyboard and persists its preferred width', async () => {
    const first = render(<ProgressionDesignWorkspace onOpenMaster={vi.fn()} />)
    const divider = await screen.findByRole('separator', { name: '入手経路の詳細の幅を変更' })
    expect(divider).toHaveAttribute('aria-valuenow', '350')
    fireEvent.keyDown(divider, { key: 'ArrowLeft' })
    expect(divider).toHaveAttribute('aria-valuenow', '366')
    expect(localStorage.getItem('astralrecord.layout.progression-inspector')).toBe('366')
    first.unmount()
    render(<ProgressionDesignWorkspace onOpenMaster={vi.fn()} />)
    const restored = await screen.findByRole('separator', { name: '入手経路の詳細の幅を変更' })
    expect(restored).toHaveAttribute('aria-valuenow', '366')
    fireEvent.keyDown(restored, { key: 'Home' })
    expect(restored).toHaveAttribute('aria-valuenow', '290')
    fireEvent.doubleClick(restored)
    expect(restored).toHaveAttribute('aria-valuenow', '350')
  })
})
