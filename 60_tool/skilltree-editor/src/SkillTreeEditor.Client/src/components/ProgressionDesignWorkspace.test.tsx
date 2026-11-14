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
    const confirm = within(await screen.findByRole('dialog', { name: '装備設計の変更を確認' }))
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
})
