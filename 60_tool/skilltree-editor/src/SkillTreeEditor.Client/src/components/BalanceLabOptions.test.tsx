import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createScenario, loadScenario, saveScenario } from '../data/balanceLab'
import type { BalanceCatalog } from '../data/balanceLab'
import { BalanceLab } from './BalanceLab'

function fixture(count = 2) {
  const reads = { names: 0 }
  const catalog: BalanceCatalog = {
    masterHash: 'options-master', modelVersion: 'options-model', sources: [], diagnostics: [], skillCatalog: [],
    limits: { maxTrials: 500, maxSeconds: 600, maxBuilds: 4, maxTickWork: 2000000, wallSeconds: 30 },
    masters: ['class', 'equipment', 'mob', 'item'].flatMap(kind => Array.from({ length: count }, (_, index) => {
      const content: Record<string, unknown> = { id: `${kind}-${index}`, name: `&a${kind}§b候補${index}` }
      if (kind === 'class') content.baseStats = []
      if (kind === 'equipment') content.equipment = {}
      Object.defineProperty(content, 'displayName', { enumerable: true, get: () => { reads.names++; return content.name } })
      return { path: `${kind}/${kind}-${index}.yml`, kind, content }
    })),
  }
  return { catalog, reads }
}

async function setup(count = 2, builds = 1) {
  const state = fixture(count)
  let current = state.catalog
  const scenario = createScenario(current)
  scenario.builds = Array.from({ length: builds }, (_, index) => ({ ...structuredClone(scenario.builds[0]), id: `build-${index}`, name: `比較${index}`, equipment: [{ itemId: 'equipment-0', enhanceLevel: 0 }] }))
  scenario.economy = { targetItemId: 'item-0', quantity: 1, enhanceItemId: 'equipment-0', fromEnhanceLevel: 0, targetEnhanceLevel: 0, sellItemIds: [], moveSeconds: 0, waitSeconds: 0, resupplySeconds: 0 }
  saveScenario(scenario)
  const fetcher = vi.fn(async (url: string) => {
    if (!url.endsWith('/catalog')) throw new Error(`Unexpected ${url}`)
    return { ok: true, json: async () => current }
  })
  vi.stubGlobal('fetch', fetcher)
  render(<BalanceLab />)
  await screen.findByText('options-master')
  return { ...state, fetcher, reload: (next: BalanceCatalog) => { current = next; fireEvent.click(screen.getByRole('button', { name: 'カタログ再読込' })) } }
}

const select = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const save = () => fireEvent.click(screen.getByRole('button', { name: 'ブラウザに保存' }))
const values = (label: string) => Array.from((screen.getByLabelText(label) as HTMLSelectElement).options, option => option.value)

beforeEach(() => { localStorage.clear(); sessionStorage.clear() })
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear() })

describe('BalanceLab native master options', () => {
  it('does not rebuild catalog labels for unrelated inputs across four builds and economy selectors', async () => {
    const { reads, fetcher } = await setup(100, 4)
    expect(reads.names).toBeGreaterThan(0)
    reads.names = 0
    fireEvent.change(screen.getByLabelText('乱数seed'), { target: { value: '23' } })
    fireEvent.change(screen.getAllByLabelText('プレイヤーLv')[0], { target: { value: '30' } })
    fireEvent.change(screen.getAllByLabelText('名前')[0], { target: { value: '装備比較' } })
    expect(reads.names).toBe(0)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(values('モブ')).toHaveLength(101)
    expect(values('調整するマスター')).toHaveLength(401)
    save()
    expect(loadScenario()).toMatchObject({ seed: 23, builds: [{ name: '装備比較', playerLevel: 30 }, {}, {}, {}] })
  })

  it('updates selected values, clears optional values and retains the latest build handlers after deletion', async () => {
    await setup(2, 2)
    const first = within(screen.getByRole('heading', { name: 'ビルド 1' }).closest('article')!)
    fireEvent.click(first.getByRole('button', { name: '削除' }))
    select('職業', 'class-1'); select('装備 1', 'equipment-1'); select('モブ', 'mob-1')
    select('目標アイテム', 'item-1'); select('強化する装備', 'equipment-1'); select('オーブ', 'item-1')
    for (const [label, value] of [['職業', 'class-1'], ['装備 1', 'equipment-1'], ['モブ', 'mob-1'], ['目標アイテム', 'item-1'], ['強化する装備', 'equipment-1'], ['オーブ', 'item-1']]) expect(screen.getByLabelText(label)).toHaveValue(value)
    expect(within(screen.getByLabelText('職業')).getByRole('option', { name: 'class候補1 (class-1)' })).toBeInTheDocument()
    expect(within(screen.getByLabelText('職業')).getByRole('option', { name: '選択してください' })).toHaveValue('')
    expect(within(screen.getByLabelText('オーブ')).getByRole('option', { name: 'なし' })).toHaveValue('')
    select('オーブ', '')
    save()
    expect(loadScenario()).toMatchObject({ builds: [{ id: 'build-1', classId: 'class-1', equipment: [{ itemId: 'equipment-1' }] }], enemy: { mobId: 'mob-1' }, economy: { targetItemId: 'item-1', enhanceItemId: 'equipment-1' } })
    expect(loadScenario()!.economy!.orbItemId).toBeUndefined()
    select('調整するマスター', 'equipment/equipment-1.yml')
    expect(JSON.parse((screen.getByLabelText('調整案JSON') as HTMLTextAreaElement).value).id).toBe('equipment-1')
    expect(within(screen.getByLabelText('調整するマスター')).getByRole('option', { name: 'equipment候補1 · equipment/equipment-1.yml' })).toBeInTheDocument()
  })

  it('refreshes names, membership and order on catalog reload even when the hash is unchanged', async () => {
    const api = await setup()
    select('職業', 'class-0'); select('装備 1', 'equipment-0'); select('モブ', 'mob-0')
    const next = fixture(3)
    next.catalog.masters = next.catalog.masters.filter(master => !String(master.content.id).endsWith('-1')).reverse()
    for (const master of next.catalog.masters) master.content.name = `§d更新${master.content.id}`
    api.reload(next.catalog)
    await waitFor(() => expect(within(screen.getByLabelText('モブ')).getByRole('option', { name: '更新mob-0 (mob-0)' })).toBeInTheDocument())
    expect(api.fetcher).toHaveBeenCalledTimes(2)
    for (const [label, kind] of [['職業', 'class'], ['装備 1', 'equipment'], ['モブ', 'mob'], ['目標アイテム', 'item'], ['強化する装備', 'equipment']]) {
      expect(values(label)).toEqual(['', `${kind}-2`, `${kind}-0`])
      expect(screen.getByLabelText(label)).toHaveValue(`${kind}-0`)
    }
    expect(values('調整するマスター')).toEqual(['', ...next.catalog.masters.map(master => master.path)])
    expect(within(screen.getByLabelText('調整するマスター')).getByRole('option', { name: '更新item-2 · item/item-2.yml' })).toBeInTheDocument()
    select('職業', 'class-2'); select('モブ', 'mob-2'); select('装備 1', 'equipment-2')
    save()
    expect(loadScenario()).toMatchObject({ builds: [{ classId: 'class-2', equipment: [{ itemId: 'equipment-2' }] }], enemy: { mobId: 'mob-2' } })
    next.reads.names = 0
    fireEvent.change(screen.getByLabelText('乱数seed'), { target: { value: '24' } })
    expect(next.reads.names).toBe(0)
  })
})
