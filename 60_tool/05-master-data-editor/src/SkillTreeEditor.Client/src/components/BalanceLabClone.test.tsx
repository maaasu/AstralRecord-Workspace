import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createScenario, loadScenario, saveScenario } from '../data/balanceLab'
import type { BalanceCatalog, BalanceScenario } from '../data/balanceLab'
import { BalanceLab } from './BalanceLab'

const catalog: BalanceCatalog = {
  masterHash: 'clone-master', sourceHash: 'clone-source', modelVersion: 'clone-model', sources: [], diagnostics: [], supportedSkillIds: [],
  limits: { maxTrials: 500, maxSeconds: 600, maxBuilds: 4, maxTickWork: 2000000, wallSeconds: 30 },
  masters: [
    { path: '20.features.class/mage.yml', kind: 'class', content: { id: 'mage', name: '魔法使い', baseStats: [] } },
    { path: '40.features.mob/wolf.yml', kind: 'mob', content: { id: 'wolf', name: '狼' } },
    { path: '10.features.item/sword.yml', kind: 'equipment', content: { id: 'sword', name: '検証の剣', equipment: {} } },
  ],
}
const cloneButton = (position = 1) => screen.getByRole('button', { name: `ビルド${position}を複製` })
const save = () => fireEvent.click(screen.getByRole('button', { name: 'ブラウザに保存' }))
const buildArticle = (position: number) => within(screen.getByRole('heading', { name: `ビルド ${position}` }).closest('article') as HTMLElement)

async function setup(maxBuilds = 4, second = false) {
  const activeCatalog = { ...catalog, limits: { ...catalog.limits!, maxBuilds } }
  const scenario = createScenario(activeCatalog)
  scenario.seed = 44; scenario.trials = 2; scenario.enemy.level = 18
  scenario.builds[0] = {
    id: 'original', name: '氷の比較', classId: 'mage', playerLevel: 30, classLevel: 12,
    equipment: [{ itemId: 'sword', enhanceLevel: 3, runes: [{ id: 'rune-a', values: [1, 2] }], randomBonus: { attack: 17 } }],
    skillIds: ['second', 'first'], passiveSkillIds: ['passive-a'], baseStats: { MAX_HEALTH: 123, ATTACK: 40 },
    normalAttacks: false, initialShield: 17,
  }
  if (second) scenario.builds.push({ ...structuredClone(scenario.builds[0]), id: 'other', name: '別の比較', playerLevel: 45 })
  saveScenario(scenario)
  let submitted: BalanceScenario | undefined
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/catalog')) return { ok: true, json: async () => activeCatalog }
    if (init?.method === 'POST') {
      submitted = JSON.parse(String(init.body)) as BalanceScenario
      return { ok: true, json: async () => ({ id: 'clone-job', status: 'completed', completed: 1, total: 1 }) }
    }
    throw new Error(`Unexpected ${url}`)
  })
  vi.stubGlobal('fetch', fetcher)
  render(<BalanceLab />)
  await screen.findByText('clone-master')
  return { scenario, fetcher, submitted: () => submitted }
}

beforeEach(() => { localStorage.clear(); sessionStorage.clear() })
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear() })

describe('BalanceLab build duplication', () => {
  it.each([1, 2])('copies build %s next to its source, preserves the baseline and focuses the new name', async position => {
    const { scenario } = await setup(4, true)
    fireEvent.click(cloneButton(position))
    const names = screen.getAllByLabelText('名前') as HTMLInputElement[]
    const copiedName = `${scenario.builds[position - 1].name}（コピー）`
    expect(names[position]).toHaveValue(copiedName)
    expect(names[position]).toHaveFocus()
    expect(names[position].selectionStart).toBe(0)
    expect(names[position].selectionEnd).toBe(copiedName.length)
    save()
    const saved = loadScenario()!
    expect(saved.builds).toHaveLength(3)
    expect(saved.builds[0]).toEqual(scenario.builds[0])
    expect(saved.builds[position]).toEqual({ ...scenario.builds[position - 1], id: expect.any(String), name: copiedName })
    expect(new Set(saved.builds.map(build => build.id)).size).toBe(3)
    expect(saved.builds.filter(build => build.name !== copiedName)).toEqual(scenario.builds)
    expect({ ...saved, builds: scenario.builds }).toEqual(scenario)
  })

  it('keeps nested equipment and skill changes in the copy, then submits both builds', async () => {
    const state = await setup()
    fireEvent.click(cloneButton())
    const copy = buildArticle(2)
    fireEvent.change(copy.getByLabelText('名前'), { target: { value: '装備差の検証' } })
    fireEvent.change(copy.getByLabelText('強化Lv'), { target: { value: '9' } })
    fireEvent.click(copy.getByRole('button', { name: 'firstの優先順位を上げる' }))
    const details = copy.getByLabelText('装備差の検証の詳細JSON') as HTMLTextAreaElement
    const changed = JSON.parse(details.value)
    changed.equipment[0].runes[0].values[0] = 99
    changed.baseStats.ATTACK = 80
    changed.passiveSkillIds = ['passive-b']
    fireEvent.change(details, { target: { value: JSON.stringify(changed) } }); fireEvent.blur(details)
    fireEvent.click(screen.getByRole('button', { name: 'この条件で実行' }))
    await waitFor(() => expect(state.submitted()).toBeDefined())
    const sent = state.submitted()!
    expect(sent.builds[0]).toEqual(state.scenario.builds[0])
    expect(sent.builds[1]).toMatchObject({ name: '装備差の検証', equipment: [{ enhanceLevel: 9, runes: [{ values: [99, 2] }], randomBonus: { attack: 17 } }], skillIds: ['first', 'second'], passiveSkillIds: ['passive-b'], baseStats: { ATTACK: 80 } })
    expect(sent.enemy).toEqual(state.scenario.enemy)
    expect(sent.seed).toBe(44); expect(sent.trials).toBe(2)
  })

  it('honors the catalog limit for copy and empty add and re-enables them after deletion', async () => {
    await setup(2)
    fireEvent.click(cloneButton())
    for (const button of screen.getAllByRole('button', { name: /ビルド\d+を複製/ })) expect(button).toBeDisabled()
    expect(screen.getByRole('button', { name: 'ビルドを追加' })).toBeDisabled()
    fireEvent.click(buildArticle(2).getByRole('button', { name: '削除' }))
    expect(cloneButton()).toBeEnabled()
    fireEvent.click(cloneButton())
    expect(screen.getAllByLabelText('名前')).toHaveLength(2)
  })

  it('keeps pending JSON intact and enables duplication after the detailed input is committed', async () => {
    await setup()
    const details = screen.getByLabelText('氷の比較の基礎ステータスJSON')
    fireEvent.change(details, { target: { value: '{unfinished' } }); fireEvent.blur(details)
    expect(cloneButton()).toBeDisabled()
    expect(screen.getByRole('button', { name: 'ビルドを追加' })).toBeDisabled()
    fireEvent.click(cloneButton())
    expect(details).toHaveValue('{unfinished')
    expect(screen.getAllByLabelText('名前')).toHaveLength(1)
    fireEvent.change(details, { target: { value: '{"ATTACK":77}' } }); fireEvent.blur(details)
    fireEvent.click(cloneButton())
    save()
    expect(loadScenario()!.builds.map(build => build.baseStats)).toEqual([{ ATTACK: 77 }, { ATTACK: 77 }])
  })

  it('does not duplicate while a run request is pending and keeps an override draft intact', async () => {
    const { fetcher } = await setup()
    type FetchResponse = Awaited<ReturnType<typeof fetcher>>
    let finish!: (value: FetchResponse) => void
    fetcher.mockImplementationOnce(() => new Promise<FetchResponse>(resolve => { finish = resolve }))
    fireEvent.click(screen.getByRole('button', { name: 'この条件で実行' }))
    expect(cloneButton()).toBeDisabled()
    await act(async () => finish({ ok: true, json: async () => ({ id: 'done', status: 'completed', completed: 1, total: 1 }) }))
    const proposal = screen.getByLabelText('調整案JSON')
    fireEvent.change(proposal, { target: { value: '{keep proposal' } })
    fireEvent.click(cloneButton())
    expect(screen.getAllByLabelText('名前')).toHaveLength(2)
    expect(proposal).toHaveValue('{keep proposal')
    expect(screen.getByRole('button', { name: 'ブラウザに保存' })).toBeDisabled()
  })

  it('uses distinct IDs for rapid empty additions and duplicate actions', async () => {
    await setup()
    vi.spyOn(Date, 'now').mockReturnValue(12345)
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('00000000-0000-4000-8000-000000000000')
    fireEvent.click(screen.getByRole('button', { name: 'ビルドを追加' }))
    fireEvent.click(screen.getByRole('button', { name: 'ビルドを追加' }))
    fireEvent.click(cloneButton())
    save()
    const builds = loadScenario()!.builds
    expect(builds).toHaveLength(4)
    expect(new Set(builds.map(build => build.id)).size).toBe(4)
  })
})
