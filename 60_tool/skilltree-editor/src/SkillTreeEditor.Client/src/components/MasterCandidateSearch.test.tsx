import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MasterDataForm } from './MasterDataForm'
import type { MasterReference } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'

vi.mock('../utils/minecraft', async importOriginal => {
  const actual = await importOriginal<typeof import('../utils/minecraft')>()
  return { ...actual, stripMinecraftFormatting: vi.fn(actual.stripMinecraftFormatting) }
})
afterEach(() => { vi.clearAllMocks(); vi.restoreAllMocks() })

const references: MasterReference[] = Array.from({ length: 5000 }, (_, index) => ({
  kind: 'mob', value: `cache${String(index).padStart(5, '0')}`, label: `&a性能候補 ${String(index).padStart(5, '0')}`, path: `mob/${index}.yml`, pointer: '/id',
}))
const open = () => fireEvent.click(screen.getByText('mobIdの候補を選択'))
const search = () => screen.getByRole('searchbox', { name: '/spawnMobs/0/mobId 候補を検索' })

describe('reference search reuse', () => {
  it('bounds initial normalization, reuses names during typing and rerenders, and defers closed browser searches', () => {
    const onChange = vi.fn()
    const form = (value: string, entries = references) => <MasterDataForm path="/spawnMobs/0/mobId" value={value} references={entries} fields={[]} onChange={onChange} />
    const observations: { action: string; nameNormalizations: number }[] = []
    const record = (action: string, expected: number) => {
      const count = vi.mocked(stripMinecraftFormatting).mock.calls.filter(([text]) => /^&[ab](?:新版)?性能候補 /.test(text)).length
      observations.push({ action, nameNormalizations: count })
      expect.soft(count, action).toBe(expected)
      vi.mocked(stripMinecraftFormatting).mockClear()
    }
    const { rerender } = render(form('性能'))
    record('initial matching draft / 5000 candidates', 100)
    expect(document.querySelectorAll('datalist option')).toHaveLength(100)
    for (let index = 0; index < 10; index++) rerender(form('性能'))
    record('10 unrelated parent renders', 0)
    open()
    record('open browser with empty search', 0)
    expect(document.querySelectorAll('.master-candidate')).toHaveLength(50)
    fireEvent.change(search(), { target: { value: '候補' } })
    record('first full name search', 4900)
    expect(screen.getByRole('status')).toHaveTextContent('5000 件 / 全 5000 件')
    fireEvent.change(search(), { target: { value: '候補 04999' } })
    record('refine name search', 0)
    expect(screen.getByRole('button', { name: '性能候補 04999mob:cache04999' })).toBeInTheDocument()
    fireEvent.keyDown(search(), { key: 'Escape' })
    record('close browser', 0)
    expect(document.querySelectorAll('.master-candidate')).toHaveLength(0)
    const replacement = references.map(entry => ({ ...entry, label: entry.label!.replace('&a', '&b新版') }))
    rerender(form('', replacement))
    record('replace references while browser closed', 100)
    open()
    record('reopen with retained search and fresh names', 4900)
    expect(screen.getByRole('button', { name: '新版性能候補 04999mob:cache04999' })).toBeInTheDocument()
    rerender(form('性能候補 04999', replacement))
    expect(document.querySelectorAll('datalist option')).toHaveLength(1)
    expect(document.querySelector('datalist option')).toHaveAttribute('value', 'mob:cache04999')
    expect(onChange).not.toHaveBeenCalled()
    console.info('5000 reference candidates: name normalization counts', observations)
  })

  it('retains the browser query and page while closed and clamps against new references on reopening', () => {
    const entries = references.slice(0, 123)
    const form = (items: MasterReference[]) => <MasterDataForm path="/spawnMobs/0/mobId" value="自由入力" fields={[]} references={items} onChange={vi.fn()} />
    const { rerender } = render(form(entries))
    open()
    fireEvent.change(search(), { target: { value: ' 性能候補 ' } })
    fireEvent.click(screen.getByRole('button', { name: '次へ' }))
    fireEvent.click(screen.getByRole('button', { name: '次へ' }))
    open(); open()
    expect(search()).toHaveValue(' 性能候補 ')
    expect(screen.getByRole('status')).toHaveTextContent('3 / 3 ページ')
    expect(document.querySelectorAll('.master-candidate')).toHaveLength(23)
    open()
    rerender(form(entries.slice(0, 60)))
    open()
    expect(search()).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent('60 件 / 全 60 件 · 2 / 2 ページ')
    expect(document.querySelectorAll('.master-candidate')).toHaveLength(10)
  })

  it('reuses filtered fishing candidates and preserves kind, exclusion, prefix and duplicate-label rules', () => {
    const entries: MasterReference[] = [
      { kind: 'item', value: 'fish', label: '&a初期の魚', path: '10.features.item/10.material/fish.yml', pointer: '/id' },
      { kind: 'item', value: 'item:fish', label: '&b新しい魚', path: '10.features.item/10.material/duplicate.yml', pointer: '/id' },
      ...['20.equipment', '80.pet', '81.pet_egg'].map(category => ({ kind: 'item', value: category, path: `10.features.item/${category}/blocked.yml`, pointer: '/id', label: '除外候補' })),
      { kind: 'mob', value: 'other', label: '別の種類', path: 'mob/other.yml', pointer: '/id' },
    ]
    const form = () => <MasterDataForm path="/caughtItemId/ref" value="魚" fields={[]} references={entries} onChange={vi.fn()} />
    const { rerender } = render(form())
    expect(document.querySelectorAll('datalist option')).toHaveLength(1)
    expect(document.querySelector('datalist option')).toHaveTextContent('新しい魚')
    expect(document.querySelector('datalist option')).toHaveAttribute('value', 'item:fish')
    vi.mocked(stripMinecraftFormatting).mockClear()
    rerender(form())
    expect(vi.mocked(stripMinecraftFormatting).mock.calls.some(([value]) => value === '&b新しい魚')).toBe(false)
  })

  it('reuses built-in status candidates when references are omitted', () => {
    const form = () => <MasterDataForm path="/status" value="攻撃" fields={[]} onChange={vi.fn()} />
    const { rerender } = render(form())
    expect(document.querySelectorAll('datalist option').length).toBeGreaterThan(0)
    vi.mocked(stripMinecraftFormatting).mockClear()
    rerender(form())
    expect(vi.mocked(stripMinecraftFormatting).mock.calls.every(([value]) => value === '攻撃')).toBe(true)
  })

  it('preserves formatting, case and whitespace rules while allowing arbitrary draft values', () => {
    const entries: MasterReference[] = [{ kind: 'mob', value: 'RedWolf', label: '&a赤&lオオカミ Wolf', path: 'mob/red.yml', pointer: '/id' }]
    const onChange = vi.fn()
    const form = (value: string) => <MasterDataForm path="/spawnMobs/0/mobId" value={value} fields={[]} references={entries} onChange={onChange} />
    const { rerender } = render(form('&a赤オオカミ wOLF'))
    expect(document.querySelector('datalist option')).toHaveAttribute('value', 'mob:RedWolf')
    expect(document.querySelector('datalist option')).toHaveTextContent('赤オオカミ Wolf')
    rerender(form(' 赤オオカミ wOLF '))
    expect(document.querySelectorAll('datalist option')).toHaveLength(0) // Draft completion does not trim.
    open()
    fireEvent.change(search(), { target: { value: ' 赤オオカミ wOLF ' } })
    expect(screen.getByRole('button', { name: '赤オオカミ Wolfmob:RedWolf' })).toBeInTheDocument()
    fireEvent.change(search(), { target: { value: '&a赤' } })
    expect(screen.getByText('一致する候補がありません。')).toBeInTheDocument() // Browser search does not strip query formatting.
    fireEvent.change(search(), { target: { value: 'REDWOLF' } })
    fireEvent.click(screen.getByRole('button', { name: '赤オオカミ Wolfmob:RedWolf' }))
    expect(onChange).toHaveBeenLastCalledWith('mob:RedWolf')
    fireEvent.change(screen.getByRole('combobox', { name: '/spawnMobs/0/mobId' }), { target: { value: '未登録の自由入力' } })
    expect(onChange).toHaveBeenLastCalledWith('未登録の自由入力')
  })

  it('skips display formatting for unmatched references without names and formats only visible ID fallbacks', () => {
    const entries: MasterReference[] = Array.from({ length: 200 }, (_, index) => ({ kind: 'mob', value: `unnamed${index}`, path: `mob/${index}.yml`, pointer: '/id' }))
    const form = (value: string) => <MasterDataForm path="/spawnMobs/0/mobId" value={value} fields={[]} references={entries} onChange={vi.fn()} />
    const { rerender } = render(form('not-found'))
    expect(document.querySelectorAll('datalist option')).toHaveLength(0)
    expect(vi.mocked(stripMinecraftFormatting).mock.calls.some(([value]) => value.startsWith('mob:unnamed'))).toBe(false)
    vi.mocked(stripMinecraftFormatting).mockClear()
    rerender(form(''))
    expect(document.querySelectorAll('datalist option')).toHaveLength(100)
    expect(document.querySelector('datalist option')).toHaveTextContent('mob:unnamed0')
    expect(vi.mocked(stripMinecraftFormatting).mock.calls.filter(([value]) => value.startsWith('mob:unnamed'))).toHaveLength(100)
  })
})
