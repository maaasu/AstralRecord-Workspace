import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BalanceCatalog, BalanceScenario, BalanceSkillCatalogEntry } from '../data/balanceLab'
import { BalanceLab } from './BalanceLab'

function skill(id: string, name: string, implementationId: string, supported: boolean, kind: 'active' | 'passive', classId?: string): BalanceSkillCatalogEntry {
  return { id, name, implementationId, supported, kind, path: `skill/mage/${id}.yml`, reasons: supported ? [] : ['計算未対応です。'], assumptions: [], sources: [], classLinks: classId ? [{ classId, kind: 'node', path: 'tree.yml' }] : [] }
}
function fixture(): BalanceCatalog {
  const skills = [skill('bolt', '&b雷&aの矢', 'shock_bolt', true, 'active'), skill('guard', '§d雷の守り', 'storm_guard', true, 'passive', 'mage'), skill('fire', '炎の矢', 'fire_arrow', false, 'active', 'mage'), skill('slash', '斬撃', 'slash', true, 'active', 'warrior')]
  return {
    masterHash: 'current', modelVersion: 'model-1', sources: [], diagnostics: [], skillCatalog: skills,
    masters: [
      { path: 'class/mage.yml', kind: 'class', content: { id: 'mage', name: '魔法使い', baseStats: [], usableSkills: ['skill:bolt'] } },
      { path: 'class/warrior.yml', kind: 'class', content: { id: 'warrior', name: '戦士', baseStats: [], usableSkills: ['skill:slash'] } },
      { path: 'mob/wolf.yml', kind: 'mob', content: { id: 'wolf', name: '狼' } },
      ...skills.map(entry => ({ path: entry.path, kind: 'skill', content: { id: entry.id, name: entry.name } })),
      { path: 'item/eriva/ore-silver.yml', kind: 'item', content: { id: 'ore-silver', name: '&b銀&aの鉱石' } },
      { path: 'item/midgard/ore-copper.yml', kind: 'item', content: { id: 'ore-copper', name: '銅の鉱石' } },
      { path: 'item/eriva/orb.yml', kind: 'item', content: { id: 'orb', name: '銀のオーブ' } },
    ],
  }
}
const box = (id: string) => screen.getByRole('checkbox', { name: new RegExp(`${id}$`) })
const query = (label: string, value: string) => fireEvent.change(screen.getByRole('searchbox', { name: label }), { target: { value } })
function installCatalog(initial = fixture()) {
  let current = initial
  let submitted: BalanceScenario | undefined
  const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
    if (init?.method === 'POST') { submitted = JSON.parse(String(init.body)); return { ok: true, json: async () => ({ id: 'test-job', status: 'completed', completed: 1, total: 1 }) } }
    return { ok: true, json: async () => current }
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, reload: (next: BalanceCatalog) => { current = next; fireEvent.click(screen.getByRole('button', { name: 'カタログ再読込' })) }, submitted: () => submitted }
}
beforeEach(() => { localStorage.clear(); sessionStorage.clear() })
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear() })

describe('BalanceLab catalog search', () => {
  it('matches skill words across formatted names, IDs, implementation IDs and paths in any order', async () => {
    const api = installCatalog()
    render(<BalanceLab />)
    await screen.findByText('current')
    const search = screen.getByRole('searchbox', { name: 'スキル検索' })
    expect(search).toHaveAccessibleDescription('名前・ID・実装ID・パスを検索します。空白で区切ると、すべての語で絞り込みます。')
    search.focus()
    for (const text of ['矢 雷', ' §b雷　SHOCK \t MAGE ', 'bolt 雷 bolt']) {
      query('スキル検索', text)
      expect(box('bolt')).toBeInTheDocument()
      expect(screen.queryByRole('checkbox', { name: /guard$/ })).not.toBeInTheDocument()
      expect(search).toHaveFocus()
    }
    query('スキル検索', '雷 missing')
    expect(screen.getByText('条件に合うスキルがありません。検索語や候補の絞り込みを変更してください。')).toBeInTheDocument()
    query('スキル検索', ' \t　 ')
    expect(screen.getByText('候補 4 / 全 4 件')).toBeInTheDocument()
    expect(api.fetchMock).toHaveBeenCalledTimes(1)
  })

  it('combines search with class and support filters while keeping active priority and passive slots', async () => {
    const api = installCatalog()
    render(<BalanceLab />)
    await screen.findByText('current')
    fireEvent.click(box('bolt')); fireEvent.click(box('slash'))
    query('スキル検索', '守り 雷')
    fireEvent.click(box('guard'))
    fireEvent.click(screen.getByLabelText('職業の潜在候補のみ'))
    fireEvent.click(screen.getByLabelText('計算対応のみ'))
    expect(box('guard')).toBeChecked()
    query('スキル検索', '矢 FIRE')
    expect(screen.queryByRole('checkbox', { name: /fire$/ })).not.toBeInTheDocument()
    query('スキル検索', '雷 矢')
    expect(box('bolt')).toBeChecked()
    fireEvent.change(screen.getByLabelText('職業'), { target: { value: 'warrior' } })
    expect(screen.queryByRole('checkbox', { name: /bolt$/ })).not.toBeInTheDocument()
    query('スキル検索', '斬 撃')
    expect(box('slash')).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'slashの優先順位を上げる' }))
    fireEvent.click(screen.getByRole('button', { name: 'この条件で実行' }))
    await waitFor(() => expect(api.submitted()?.builds[0].skillIds).toEqual(['slash', 'bolt']))
    expect(api.submitted()?.builds[0].passiveSkillIds).toEqual(['guard'])
    expect(api.submitted()?.builds[0].classId).toBe('warrior')
  })

  it('searches sell items across fields without dropping selected items outside the query', async () => {
    const api = installCatalog()
    render(<BalanceLab />)
    await screen.findByText('current')
    fireEvent.click(screen.getByLabelText('収集・強化・成長・金策も計算する'))
    fireEvent.click(screen.getByText('売却アイテム（0件選択）'))
    expect(screen.getByRole('searchbox', { name: '売却アイテム検索' })).toHaveAccessibleDescription('名前・ID・パスを検索します。空白で区切ると、すべての語で絞り込みます。')
    query('売却アイテム検索', '鉱石 銀')
    fireEvent.click(box('ore-silver'))
    query('売却アイテム検索', ' §b銀　ERIVA \t SILVER ')
    expect(box('ore-silver')).toBeChecked()
    query('売却アイテム検索', '鉱石 MIDGARD')
    fireEvent.click(box('ore-copper'))
    query('売却アイテム検索', '銀 missing')
    expect(screen.getByText('条件に合うアイテムがありません。')).toBeInTheDocument()
    expect(screen.getByText('売却アイテム（2件選択）')).toBeInTheDocument()
    query('売却アイテム検索', '　 ')
    expect(box('ore-silver')).toBeChecked()
    expect(box('ore-copper')).toBeChecked()
    expect(box('orb')).not.toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'ブラウザに保存' }))
    const saved = JSON.parse(localStorage.getItem('astralrecord.balance-lab.scenario.v1')!)
    expect(saved.economy.sellItemIds).toEqual(['ore-silver', 'ore-copper'])
    expect(api.fetchMock).toHaveBeenCalledTimes(1)
  })

  it('refreshes names, support and class candidates on catalog reload without losing query or selection', async () => {
    const api = installCatalog()
    render(<BalanceLab />)
    await screen.findByText('current')
    fireEvent.click(box('bolt'))
    query('スキル検索', '雷 矢')
    fireEvent.click(screen.getByLabelText('職業の潜在候補のみ'))
    const next = fixture()
    next.masterHash = 'reloaded'
    next.masters.find(master => master.content.id === 'mage')!.content.usableSkills = []
    next.masters.find(master => master.content.id === 'bolt')!.content.name = '&b氷の矢'
    Object.assign(next.skillCatalog![0], { name: '氷の矢', implementationId: 'frost_arrow', supported: false, reasons: ['氷は未対応です。'] })
    api.reload(next)
    await screen.findByText('reloaded')
    expect(screen.getByRole('searchbox', { name: 'スキル検索' })).toHaveValue('雷 矢')
    query('スキル検索', '氷 frost')
    expect(screen.queryByRole('checkbox', { name: /bolt$/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('職業の潜在候補のみ'))
    expect(box('bolt')).toBeChecked()
    expect(screen.getByText('氷は未対応です。')).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('計算対応のみ'))
    expect(screen.queryByRole('checkbox', { name: /bolt$/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'boltの優先順位を下げる' }).parentElement?.parentElement).toHaveTextContent('氷の矢')
    expect(api.fetchMock).toHaveBeenCalledTimes(2)
  })

  it('refreshes cached sell-item names after reload while retaining selection and query', async () => {
    const api = installCatalog()
    render(<BalanceLab />)
    await screen.findByText('current')
    fireEvent.click(screen.getByLabelText('収集・強化・成長・金策も計算する'))
    fireEvent.click(screen.getByText('売却アイテム（0件選択）'))
    query('売却アイテム検索', '銀 鉱石')
    fireEvent.click(box('ore-silver'))
    const next = fixture()
    next.masterHash = 'renamed-item'
    next.masters.find(master => master.content.id === 'ore-silver')!.content.name = '&b精錬銀'
    api.reload(next)
    await screen.findByText('renamed-item')
    expect(screen.getByRole('searchbox', { name: '売却アイテム検索' })).toHaveValue('銀 鉱石')
    expect(screen.getByText('条件に合うアイテムがありません。')).toBeInTheDocument()
    query('売却アイテム検索', '精錬 SILVER')
    expect(box('ore-silver')).toBeChecked()
    expect(box('ore-silver').closest('label')).toHaveTextContent('精錬銀')
  })

  it('reuses class candidate metadata for unrelated scenario edits and search changes', async () => {
    const catalog = fixture()
    const entries = Array.from({ length: 100 }, (_, index) => skill(`probe-${index}`, `検証スキル${index}`, `impl-${index}`, true, 'active'))
    catalog.skillCatalog = entries
    catalog.masters = [...catalog.masters.filter(master => master.kind !== 'skill'), ...entries.map(entry => ({ path: entry.path, kind: 'skill', content: { id: entry.id, name: entry.name } }))]
    let candidateReads = 0
    Object.defineProperty(catalog.masters[0].content, 'usableSkills', { enumerable: true, get: () => { candidateReads++; return [] } })
    installCatalog(catalog)
    render(<BalanceLab />)
    await screen.findByText('current')
    candidateReads = 0
    for (const seed of ['2', '3', '4']) fireEvent.change(screen.getByLabelText('乱数seed'), { target: { value: seed } })
    expect(candidateReads).toBe(0)
    for (const text of ['スキル probe', '検証 7', 'impl 9', '']) query('スキル検索', text)
    fireEvent.click(box('probe-0'))
    expect(candidateReads).toBe(0)
  })
})
