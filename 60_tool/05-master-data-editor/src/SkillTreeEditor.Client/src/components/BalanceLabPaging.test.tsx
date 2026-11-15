import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BalanceCatalog, BalanceScenario, BalanceSkillCatalogEntry } from '../data/balanceLab'
import { BalanceLab } from './BalanceLab'

const padded = (index: number) => String(index).padStart(3, '0')
function fixture(skillCount = 123, itemCount = 113): BalanceCatalog {
  const skills: BalanceSkillCatalogEntry[] = Array.from({ length: skillCount }, (_, index) => ({
    id: `skill-${padded(index)}`, name: `検証スキル${padded(index)}`, path: `skill/${padded(index)}.yml`, implementationId: `impl-${index}`,
    supported: index % 3 !== 0, kind: index % 7 === 0 ? 'passive' : 'active', reasons: [], assumptions: [], sources: [],
    classLinks: [{ classId: index < 80 ? 'mage' : 'warrior', kind: 'node', path: 'tree.yml' }],
  }))
  return { masterHash: `catalog-${skillCount}-${itemCount}`, modelVersion: 'model-1', sources: [], diagnostics: [], skillCatalog: skills, masters: [
    { path: 'class/mage.yml', kind: 'class', content: { id: 'mage', name: '魔法使い', baseStats: [] } },
    { path: 'class/warrior.yml', kind: 'class', content: { id: 'warrior', name: '戦士', baseStats: [] } },
    { path: 'mob/wolf.yml', kind: 'mob', content: { id: 'wolf', name: '狼' } },
    ...skills.map(entry => ({ path: entry.path, kind: 'skill', content: { id: entry.id, name: entry.name } })),
    ...Array.from({ length: itemCount }, (_, index) => ({ path: `item/${padded(index)}.yml`, kind: 'item', content: { id: `item-${padded(index)}`, name: `売却素材${padded(index)}` } })),
  ] }
}
function installCatalog() {
  let current = fixture(), submitted: BalanceScenario | undefined
  const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
    if (init?.method === 'POST') { submitted = JSON.parse(String(init.body)); return { ok: true, json: async () => ({ id: 'test-job', status: 'completed', completed: 1, total: 1 }) } }
    return { ok: true, json: async () => current }
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, submitted: () => submitted, reload: async (next: BalanceCatalog) => { current = next; fireEvent.click(screen.getByRole('button', { name: 'カタログ再読込' })); await screen.findByText(next.masterHash) } }
}
const skillLabel = 'ビルド1のスキル候補'
const next = (label = skillLabel) => fireEvent.click(screen.getByRole('button', { name: `${label}の次のページ` }))
const previous = (label = skillLabel) => fireEvent.click(screen.getByRole('button', { name: `${label}の前のページ` }))
const page = (label = skillLabel) => screen.getByRole('group', { name: `${label}のページ` })
const box = (id: string) => screen.getByRole('checkbox', { name: new RegExp(`${id}$`) })
function enableSales() {
  fireEvent.click(screen.getByLabelText('収集・強化・成長・金策も計算する'))
  fireEvent.click(screen.getByText('売却アイテム（0件選択）'))
}
beforeEach(() => { localStorage.clear(); sessionStorage.clear() })
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear() })

describe('BalanceLab candidate paging', () => {
  it('bounds candidate cards to 50 per build and sell list instead of rendering every match', async () => {
    const api = installCatalog()
    const { container } = render(<BalanceLab />)
    await screen.findByText('catalog-123-113')
    for (let index = 0; index < 3; index++) fireEvent.click(screen.getByRole('button', { name: 'ビルドを追加' }))
    enableSales()
    expect.soft(container.querySelectorAll('.balance-skill-row')).toHaveLength(200)
    expect.soft(container.querySelectorAll('.balance-sell-picker .balance-check-item')).toHaveLength(50)
    expect(screen.getByRole('heading', { name: 'ビルド（4/4）' })).toBeInTheDocument()
    expect(api.fetchMock).toHaveBeenCalledTimes(1)
  })

  it('retains active priority and passive slots across skill pages and focuses only explicit paging', async () => {
    const api = installCatalog()
    const { container } = render(<BalanceLab />)
    await screen.findByText('catalog-123-113')
    const list = container.querySelector('.balance-skill-checks')!
    fireEvent.click(box('skill-001'))
    list.scrollTop = 200
    next()
    expect(page()).toHaveTextContent('51–100件 · 2 / 3ページ')
    expect(list.scrollTop).toBe(0)
    expect(box('skill-050')).toHaveFocus()
    fireEvent.click(box('skill-056'))
    next()
    fireEvent.click(box('skill-101'))
    expect(page()).toHaveTextContent('101–123件 · 3 / 3ページ')
    expect(screen.getByRole('button', { name: `${skillLabel}の次のページ` })).toBeDisabled()
    expect(list.querySelectorAll('input[type=checkbox]')).toHaveLength(23)
    const search = screen.getByRole('searchbox', { name: 'スキル検索' })
    search.focus(); fireEvent.change(search, { target: { value: 'skill-101' } })
    expect(search).toHaveFocus()
    expect(box('skill-101')).toBeChecked()
    fireEvent.change(search, { target: { value: '' } })
    expect(page()).toHaveTextContent('1–50件 · 1 / 3ページ')
    expect(box('skill-001')).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'skill-101の優先順位を上げる' }))
    fireEvent.click(screen.getByRole('button', { name: 'この条件で実行' }))
    await waitFor(() => expect(api.submitted()?.builds[0].skillIds).toEqual(['skill-101', 'skill-001']))
    expect(api.submitted()?.builds[0].passiveSkillIds).toEqual(['skill-056'])
  })

  it('keeps off-page sell selections through searching, returning and saving', async () => {
    installCatalog()
    const { container } = render(<BalanceLab />)
    await screen.findByText('catalog-123-113')
    enableSales()
    const list = container.querySelector('.balance-sell-picker .balance-checks')!
    fireEvent.click(box('item-000'))
    list.scrollTop = 150; next('売却候補')
    expect(list.scrollTop).toBe(0)
    expect(box('item-050')).toHaveFocus()
    fireEvent.click(box('item-070'))
    next('売却候補'); fireEvent.click(box('item-112'))
    expect(page('売却候補')).toHaveTextContent('101–113件 · 3 / 3ページ')
    const search = screen.getByRole('searchbox', { name: '売却アイテム検索' })
    search.focus(); fireEvent.change(search, { target: { value: 'item-112' } })
    expect(search).toHaveFocus(); expect(box('item-112')).toBeChecked()
    fireEvent.change(search, { target: { value: '' } })
    expect(box('item-000')).toBeChecked()
    next('売却候補'); expect(box('item-070')).toBeChecked()
    previous('売却候補'); expect(box('item-000')).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: 'ブラウザに保存' }))
    expect(JSON.parse(localStorage.getItem('astralrecord.balance-lab.scenario.v1')!).economy.sellItemIds).toEqual(['item-000', 'item-070', 'item-112'])
  })

  it('keeps the pages of different builds and the sell list independent', async () => {
    installCatalog()
    render(<BalanceLab />)
    await screen.findByText('catalog-123-113')
    fireEvent.click(screen.getByRole('button', { name: 'ビルドを追加' }))
    enableSales()
    next(); next(); next('ビルド2のスキル候補'); next('売却候補')
    expect(page()).toHaveTextContent('3 / 3ページ')
    expect(page('ビルド2のスキル候補')).toHaveTextContent('2 / 3ページ')
    expect(page('売却候補')).toHaveTextContent('2 / 3ページ')
    fireEvent.change(screen.getAllByRole('searchbox', { name: 'スキル検索' })[0], { target: { value: 'skill-001' } })
    expect(screen.queryByRole('group', { name: `${skillLabel}のページ` })).not.toBeInTheDocument()
    expect(page('ビルド2のスキル候補')).toHaveTextContent('2 / 3ページ')
    expect(page('売却候補')).toHaveTextContent('2 / 3ページ')
  })

  it('uses positional page labels even when two builds have the same editable name', async () => {
    installCatalog()
    render(<BalanceLab />)
    await screen.findByText('catalog-123-113')
    fireEvent.click(screen.getByRole('button', { name: 'ビルドを追加' }))
    for (const name of screen.getAllByLabelText('名前')) fireEvent.change(name, { target: { value: '同名ビルド' } })
    next()
    expect(page()).toHaveTextContent('2 / 3ページ')
    expect(page('ビルド2のスキル候補')).toHaveTextContent('1 / 3ページ')
    const firstTarget = within(page()).getByRole('button', { name: /次のページ$/ }).getAttribute('aria-controls')
    const secondTarget = within(page('ビルド2のスキル候補')).getByRole('button', { name: /次のページ$/ }).getAttribute('aria-controls')
    expect(firstTarget).not.toBe(secondTarget)
  })

  it('resets to the first page on search, support, class-filter and class changes', async () => {
    installCatalog()
    render(<BalanceLab />)
    await screen.findByText('catalog-123-113')
    next(); next()
    const search = screen.getByRole('searchbox', { name: 'スキル検索' })
    search.focus(); fireEvent.change(search, { target: { value: '検証' } })
    expect(page()).toHaveTextContent('1 / 3ページ'); expect(search).toHaveFocus()
    next(); fireEvent.click(screen.getByLabelText('計算対応のみ'))
    expect(page()).toHaveTextContent('1–50件 · 1 / 2ページ')
    next(); fireEvent.click(screen.getByLabelText('職業の潜在候補のみ'))
    expect(page()).toHaveTextContent('1–50件 · 1 / 2ページ')
    next()
    const classSelect = screen.getByLabelText('職業')
    classSelect.focus(); fireEvent.change(classSelect, { target: { value: 'warrior' } })
    expect(classSelect).toHaveFocus()
    expect(box('skill-080')).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: `${skillLabel}のページ` })).not.toBeInTheDocument()
  })

  it('clamps both lists after a shrinking reload and does not restore the old page when rows return', async () => {
    const api = installCatalog()
    render(<BalanceLab />)
    await screen.findByText('catalog-123-113')
    enableSales(); next(); next(); next('売却候補'); next('売却候補')
    await api.reload(fixture(65, 65))
    expect(page()).toHaveTextContent('51–65件 · 2 / 2ページ')
    expect(page('売却候補')).toHaveTextContent('51–65件 · 2 / 2ページ')
    await api.reload(fixture())
    expect(page()).toHaveTextContent('51–100件 · 2 / 3ページ')
    expect(page('売却候補')).toHaveTextContent('51–100件 · 2 / 3ページ')
    await api.reload(fixture(0, 0))
    expect(screen.getByText('条件に合うスキルがありません。検索語や候補の絞り込みを変更してください。')).toBeInTheDocument()
    expect(screen.getByText('条件に合うアイテムがありません。')).toBeInTheDocument()
    await api.reload(fixture())
    expect(page()).toHaveTextContent('1–50件 · 1 / 3ページ')
    expect(page('売却候補')).toHaveTextContent('1–50件 · 1 / 3ページ')
    expect(within(page()).getByRole('button', { name: /前のページ$/ })).toBeDisabled()
  })
})
