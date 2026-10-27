import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { JsonObject, JsonValue } from '../types/editor'
import { closestContainerPointer } from '../data/masterDataNavigation'
import { MasterDataDetails, MasterDataSearch } from './MasterDataNavigation'

const initial = { schemaVersion: 1, id: 'test', unknown: 'root', skills: [{ params: { 'a/b~c': 2, UnknownRpc: 'keep' } }, { id: 'sibling' }] }
function Editable({ readOnly = false, value: startingValue = initial, schema = {}, onFocus = vi.fn() }: { readOnly?: boolean; value?: JsonValue; schema?: JsonObject; onFocus?: (path: string) => void }) {
  const [value, setValue] = useState<JsonValue>(startingValue)
  const [path, setPath] = useState('')
  const selected = closestContainerPointer(value, path)
  return <><MasterDataDetails value={value} path={selected} fields={[]} references={[]} schema={schema} disabled={readOnly} navigationDisabled={false} onSelect={setPath} onFocus={onFocus} onInvalid={vi.fn()} onChange={setValue} />
    <output data-testid="value">{JSON.stringify(value)}</output></>
}
describe('master data sections, search and details', () => {
  it('moves through search results by keyboard, returns to the query, and supports another search', () => {
    const onFocus = vi.fn()
    render(<MasterDataSearch value={{ alpha: 'match', beta: 'match', gamma: 'match' }} fields={[]} schema={{}} onSelect={vi.fn()} onFocus={onFocus} />)
    const input = screen.getByRole('textbox', { name: /フォーム内検索/ })
    fireEvent.change(input, { target: { value: 'match' } })
    const results = ['/alpha', '/beta', '/gamma'].map((path) => screen.getByRole('button', { name: new RegExp('^' + path + ' ') }))
    input.focus()
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    expect(results[0]).toHaveFocus()
    fireEvent.keyDown(results[0], { key: 'ArrowDown' })
    expect(results[1]).toHaveFocus()
    fireEvent.keyDown(results[1], { key: 'End' })
    expect(results[2]).toHaveFocus()
    fireEvent.keyDown(results[2], { key: 'Home' })
    expect(results[0]).toHaveFocus()
    fireEvent.keyDown(results[0], { key: 'ArrowUp' })
    expect(results[2]).toHaveFocus()
    fireEvent.keyDown(results[2], { key: 'Escape' })
    expect(input).toHaveFocus()
    expect(input).toHaveValue('match')
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(results[0]).toHaveFocus()
    fireEvent.click(results[0])
    expect(onFocus).toHaveBeenCalledWith('/alpha')
    expect(input).toHaveValue('')
    fireEvent.change(input, { target: { value: 'gamma' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(screen.getByRole('button', { name: /^\/gamma / })).toHaveFocus()
  })

  it('ignores composition keys and respects disabled navigation', () => {
    const onFocus = vi.fn()
    const { rerender } = render(<MasterDataSearch value={{ alpha: 'match' }} fields={[]} schema={{}} onSelect={vi.fn()} onFocus={onFocus} />)
    const input = screen.getByRole('textbox', { name: /フォーム内検索/ })
    fireEvent.change(input, { target: { value: 'match' } })
    input.focus()
    fireEvent.keyDown(input, { key: 'ArrowDown', isComposing: true })
    expect(input).toHaveFocus()
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 })
    expect(input).toHaveFocus()
    rerender(<MasterDataSearch value={{ alpha: 'match' }} fields={[]} schema={{}} disabled onSelect={vi.fn()} onFocus={onFocus} />)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    expect(input).toHaveFocus()
    expect(screen.getByRole('button', { name: /^\/alpha / })).toBeDisabled()
    expect(onFocus).not.toHaveBeenCalled()
  })

  it('selects a structured search result and then requests focus at its heading', () => {
    const onSelect = vi.fn()
    const onFocus = vi.fn()
    render(<MasterDataSearch value={{ nested: { id: 'target' } }} fields={[]} schema={{}} onSelect={onSelect} onFocus={onFocus} />)
    fireEvent.change(screen.getByRole('textbox', { name: /フォーム内検索/ }), { target: { value: 'target' } })
    fireEvent.click(screen.getByRole('button', { name: /^\/nested に移動/ }))
    expect(onSelect).toHaveBeenCalledWith('/nested')
    expect(onFocus).toHaveBeenCalledWith('/nested')
    expect(onSelect.mock.invocationCallOrder[0]).toBeLessThan(onFocus.mock.invocationCallOrder[0])
  })

  it('requests focus for sections and breadcrumbs without editing values', () => {
    const onFocus = vi.fn()
    render(<Editable value={{ id: 'one', nested: { child: { id: 'two' } } }} onFocus={onFocus} />)
    fireEvent.click(screen.getByRole('button', { name: '/nested を開く' }))
    expect(onFocus).toHaveBeenCalledWith('/nested')
    fireEvent.click(screen.getByRole('button', { name: '/nested/child を開く' }))
    expect(onFocus).toHaveBeenCalledWith('/nested/child')
    fireEvent.click(within(screen.getByRole('navigation', { name: /現在の階層/ })).getByRole('button', { name: /基本情報/ }))
    expect(onFocus).toHaveBeenLastCalledWith('')
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ id: 'one', nested: { child: { id: 'two' } } })
  })
  it('searches catalog status names in scalar values and Map keys without rewriting stored IDs', () => {
    render(<Editable value={{ stats: { MAX_HEALTH: 5 }, baseStats: [{ status: 'MAX_HEALTH', value: 7 }] }} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: '最大HP' } })
    expect(screen.getByRole('button', { name: '/stats/MAX_HEALTH に移動' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '/baseStats/0/status に移動' })).toHaveTextContent('最大HP（MAX_HEALTH）')
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ stats: { MAX_HEALTH: 5 }, baseStats: [{ status: 'MAX_HEALTH', value: 7 }] })
  })

  it('moves directly between Japanese sections without returning to the root', () => {
    render(<Editable value={{ id: 'dungeon', entry: { worldId: 'world' }, challenge: { timeLimitSeconds: 900 } }} />)
    fireEvent.click(within(screen.getByRole('navigation', { name: '編集セクション' })).getByRole('button', { name: '入場地点' }))
    expect(screen.getByRole('textbox', { name: '/entry/worldId' })).toHaveValue('world')
    fireEvent.click(within(screen.getByRole('navigation', { name: '編集セクション' })).getByRole('button', { name: '挑戦ルール' }))
    fireEvent.change(screen.getByRole('textbox', { name: '/challenge/timeLimitSeconds' }), { target: { value: '600' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ id: 'dungeon', entry: { worldId: 'world' }, challenge: { timeLimitSeconds: 600 } })
  })

  it('renders colored node summaries and searches visible names without changing stored codes', () => {
    const name = '&6Go&alden'
    const value = { items: [{ name }] }
    render(<Editable value={value} />)
    fireEvent.click(screen.getByRole('button', { name: '/items を開く' }))
    const summary = screen.getByRole('button', { name: '/items/0 を開く' }).parentElement!
    expect(within(summary).getByText('Go')).toHaveStyle({ color: '#ffaa00' })
    expect(within(summary).getByText(/^lden/)).toHaveStyle({ color: '#55ff55' })
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: 'Golden' } })
    expect(screen.getByRole('button', { name: '/items/0 に移動' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '/items/0/name に移動' })).toBeInTheDocument()
    expect(screen.getByTestId('value')).toHaveTextContent(JSON.stringify(value))
  })
  it('edits an escaped deep pointer without dropping unknown fields or array siblings', () => {
    render(<Editable />)
    fireEvent.click(screen.getByRole('button', { name: '/skills を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0 を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0/params を開く' }))
    fireEvent.change(screen.getByRole('textbox', { name: '/skills/0/params/a~1b~0c' }), { target: { value: '8' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ ...initial, skills: [{ params: { 'a/b~c': 8, UnknownRpc: 'keep' } }, { id: 'sibling' }] })
    fireEvent.click(within(screen.getByRole('navigation', { name: '現在の階層' })).getByRole('button', { name: '基本情報' }))
    expect(screen.getByRole('textbox', { name: '/id' })).toHaveValue('test')
  })
  it('searches Japanese schema titles, keys and values across collapsed nodes', () => {
    const onFocus = vi.fn()
    const schema = { properties: { skills: { items: { properties: { params: { properties: { UnknownRpc: { title: '固有の設定' } } } } } } } }
    render(<MasterDataSearch value={initial} fields={[]} schema={schema} onSelect={vi.fn()} onFocus={onFocus} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: '固有の設定' } })
    fireEvent.click(screen.getByRole('button', { name: '/skills/0/params/UnknownRpc に移動' }))
    expect(onFocus).toHaveBeenCalledWith('/skills/0/params/UnknownRpc')
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: 'keep' } })
    expect(screen.getByRole('button', { name: '/skills/0/params/UnknownRpc に移動' })).toBeInTheDocument()
  })
  it('keeps navigation available for read-only documents while disabling deep mutations', () => {
    render(<Editable readOnly />)
    fireEvent.click(screen.getByRole('button', { name: /^skills/ }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0 を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0/params を開く' }))
    expect(screen.getByRole('textbox', { name: '/skills/0/params/UnknownRpc' })).toBeDisabled()
    expect(screen.getByTestId('value')).toHaveTextContent(JSON.stringify(initial))
  })
  it('searches the end of long values while keeping result summaries short', () => {
    const onFocus = vi.fn()
    render(<MasterDataSearch value={{ description: 'x'.repeat(100) + 'UNIQUE_LATE_VALUE' }} fields={[]} schema={{}} onSelect={vi.fn()} onFocus={onFocus} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: 'UNIQUE_LATE_VALUE' } })
    const result = screen.getByRole('button', { name: '/description に移動' })
    expect(result.querySelector('small')?.textContent?.length).toBeLessThanOrEqual(80)
    fireEvent.click(result)
    expect(onFocus).toHaveBeenCalledWith('/description')
  })
  it('keeps a union type picker available when editing an array element', () => {
    const schema = { properties: { skills: { type: 'array', items: { oneOf: [
      { title: '攻撃', type: 'object', required: ['type', 'amount'], properties: { type: { const: 'attack' }, amount: { type: 'integer' } } },
      { title: '回復', type: 'object', required: ['type', 'power'], properties: { type: { const: 'heal' }, power: { type: 'number' } } },
    ] } } } }
    render(<Editable value={{ skills: [{ type: 'attack', amount: 2, UnknownRpc: 'keep' }] }} schema={schema} />)
    fireEvent.click(screen.getByRole('button', { name: '/skills を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '/skills/0 を開く' }))
    fireEvent.change(screen.getByRole('combobox', { name: '/skills/0 定義の種類' }), { target: { value: '1' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ skills: [{ type: 'heal', power: 0, amount: 2, UnknownRpc: 'keep' }] })
  })
  it('pages structured arrays while applying row actions to the actual index', () => {
    const items = Array.from({ length: 27 }, (_, index) => ({ id: 'row-' + index }))
    render(<Editable value={{ items }} />)
    fireEvent.click(screen.getByRole('button', { name: '/items を開く' }))
    fireEvent.click(screen.getByRole('button', { name: '次の要素' }))
    fireEvent.click(screen.getByRole('button', { name: '/items/25 を削除' }))
    expect(JSON.parse(screen.getByTestId('value').textContent!).items.map((item: { id: string }) => item.id)).toEqual(items.filter((_, index) => index !== 25).map((item) => item.id))
  })
})
