import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { JsonObject, JsonValue } from '../types/editor'
import { closestContainerPointer } from '../data/masterDataNavigation'
import * as navigation from '../data/masterDataNavigation'
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

describe('paged form search', () => {
  const fields: [] = []
  const schema: JsonObject = {}
  const many = Object.fromEntries(Array.from({ length: 205 }, (_, index) => [`field${index}`, 'needle']))
  const resultButtons = () => screen.getAllByRole('button', { name: / に移動$/ })

  it('reaches every result with a bounded page and keyboard focus on the new page', () => {
    const onFocus = vi.fn()
    render(<MasterDataSearch value={many} fields={fields} schema={schema} onSelect={vi.fn()} onFocus={onFocus} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: 'needle' } })
    expect(screen.getByRole('status')).toHaveTextContent('205件 · 1〜100件を表示')
    expect(resultButtons()).toHaveLength(100)
    expect(screen.getByRole('button', { name: '前の検索結果' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '次の検索結果' }))
    expect(screen.getByRole('button', { name: '/field100 に移動' })).toHaveFocus()
    expect(screen.queryByRole('button', { name: '/field0 に移動' })).not.toBeInTheDocument()
    expect(resultButtons()).toHaveLength(100)
    fireEvent.click(screen.getByRole('button', { name: '次の検索結果' }))
    expect(resultButtons()).toHaveLength(5)
    expect(screen.getByRole('status')).toHaveTextContent('205件 · 201〜205件を表示')
    const first = screen.getByRole('button', { name: '/field200 に移動' })
    const last = screen.getByRole('button', { name: '/field204 に移動' })
    expect(first).toHaveFocus()
    expect(screen.getByRole('button', { name: '次の検索結果' })).toBeDisabled()
    fireEvent.keyDown(first, { key: 'End' })
    expect(last).toHaveFocus()
    fireEvent.keyDown(last, { key: 'ArrowDown' })
    expect(first).toHaveFocus()
    fireEvent.keyDown(first, { key: 'ArrowUp' })
    expect(last).toHaveFocus()
    fireEvent.keyDown(last, { key: 'Escape' })
    expect(screen.getByRole('textbox', { name: 'フォーム内検索' })).toHaveFocus()
    fireEvent.keyDown(screen.getByRole('textbox', { name: 'フォーム内検索' }), { key: 'Enter' })
    expect(first).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: '前の検索結果' }))
    expect(screen.getByRole('button', { name: '/field100 に移動' })).toHaveFocus()
    fireEvent.click(screen.getByRole('button', { name: '次の検索結果' }))
    fireEvent.click(screen.getByRole('button', { name: '/field204 に移動' }))
    expect(onFocus).toHaveBeenCalledWith('/field204')
    expect(screen.getByRole('textbox', { name: 'フォーム内検索' })).toHaveValue('')
  })

  it('returns to the first page when the query changes, including returning to an earlier query', () => {
    render(<MasterDataSearch value={many} fields={fields} schema={schema} onSelect={vi.fn()} onFocus={vi.fn()} />)
    const input = screen.getByRole('textbox', { name: 'フォーム内検索' })
    fireEvent.change(input, { target: { value: 'needle' } })
    fireEvent.click(screen.getByRole('button', { name: '次の検索結果' }))
    fireEvent.change(input, { target: { value: 'field204' } })
    expect(resultButtons()).toHaveLength(1)
    expect(screen.queryByRole('navigation', { name: 'フォーム内検索のページ' })).not.toBeInTheDocument()
    fireEvent.change(input, { target: { value: 'needle' } })
    expect(screen.getByRole('button', { name: '/field0 に移動' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '前の検索結果' })).toBeDisabled()
    fireEvent.change(input, { target: { value: 'not-present' } })
    expect(screen.getByRole('status')).toHaveTextContent('0件')
    input.focus()
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(input).toHaveFocus()
  })

  it('returns to the first page even when only query capitalization or surrounding spaces change', () => {
    render(<MasterDataSearch value={many} fields={fields} schema={schema} onSelect={vi.fn()} onFocus={vi.fn()} />)
    const input = screen.getByRole('textbox', { name: 'フォーム内検索' })
    fireEvent.change(input, { target: { value: 'needle' } })
    for (const query of ['NEEDLE', ' NEEDLE ', 'needle']) {
      fireEvent.click(screen.getByRole('button', { name: '次の検索結果' }))
      expect(screen.getByRole('status')).toHaveTextContent('101〜200件')
      fireEvent.change(input, { target: { value: query } })
      expect(screen.getByRole('status')).toHaveTextContent('1〜100件')
      expect(screen.getByRole('button', { name: '/field0 に移動' })).toBeInTheDocument()
    }
  })

  it('does not repeat schema traversal for query changes, page changes or unrelated rerenders', () => {
    const resolve = vi.spyOn(navigation, 'schemaAtPointer')
    try {
      const props = { value: many, fields, schema, onSelect: vi.fn(), onFocus: vi.fn() }
      const { rerender } = render(<MasterDataSearch {...props} />)
      expect(resolve).not.toHaveBeenCalled()
      const input = screen.getByRole('textbox', { name: 'フォーム内検索' })
      fireEvent.change(input, { target: { value: 'n' } })
      expect(resolve).toHaveBeenCalledTimes(205)
      for (const query of ['ne', 'nee', 'needle']) fireEvent.change(input, { target: { value: query } })
      fireEvent.click(screen.getByRole('button', { name: '次の検索結果' }))
      rerender(<MasterDataSearch {...props} onFocus={vi.fn()} />)
      expect(resolve).toHaveBeenCalledTimes(205)
      expect(screen.getByRole('button', { name: '/field100 に移動' })).toBeInTheDocument()
      rerender(<MasterDataSearch {...props} value={{ ...many, field0: 'updated' }} />)
      expect(resolve).toHaveBeenCalledTimes(410)
      expect(screen.getByRole('status')).toHaveTextContent('204件 · 1〜100件を表示')
      expect(screen.queryByRole('button', { name: '/field0 に移動' })).not.toBeInTheDocument()
      fireEvent.change(input, { target: { value: 'updated' } })
      expect(screen.getByRole('button', { name: '/field0 に移動' })).toHaveTextContent('updated')
      expect(resolve).toHaveBeenCalledTimes(410)
    } finally { resolve.mockRestore() }
  })

  it('refreshes Japanese field titles and schema titles when definitions change', () => {
    const props = { value: { custom: 'x' }, fields, schema, onSelect: vi.fn(), onFocus: vi.fn() }
    const { rerender } = render(<MasterDataSearch {...props} />)
    const input = screen.getByRole('textbox', { name: 'フォーム内検索' })
    fireEvent.change(input, { target: { value: '日本語' } })
    expect(screen.getByRole('status')).toHaveTextContent('0件')
    rerender(<MasterDataSearch {...props} schema={{ properties: { custom: { title: '日本語の定義' } } }} />)
    expect(screen.getByRole('button', { name: '/custom に移動' })).toHaveTextContent('日本語の定義')
    rerender(<MasterDataSearch {...props} fields={[{ path: 'custom', key: 'custom', label: '日本語の項目', type: 'string', required: false, description: '' }]} />)
    expect(screen.getByRole('button', { name: '/custom に移動' })).toHaveTextContent('日本語の項目')
    rerender(<MasterDataSearch {...props} />)
    expect(screen.getByRole('status')).toHaveTextContent('0件')
  })

  it('updates result pointers and summaries after array reorder and removal', () => {
    const onFocus = vi.fn()
    const props = { fields, schema, onSelect: vi.fn(), onFocus }
    const { rerender } = render(<MasterDataSearch {...props} value={{ rows: [{ id: 'target' }, { id: 'other' }] }} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: 'target' } })
    expect(screen.getByRole('button', { name: '/rows/0/id に移動' })).toBeInTheDocument()
    rerender(<MasterDataSearch {...props} value={{ rows: [{ id: 'other' }, { id: 'target' }] }} />)
    expect(screen.queryByRole('button', { name: '/rows/0/id に移動' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '/rows/1/id に移動' }))
    expect(onFocus).toHaveBeenCalledWith('/rows/1/id')
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: 'target' } })
    rerender(<MasterDataSearch {...props} value={{ rows: [{ id: 'other' }] }} />)
    expect(screen.getByRole('status')).toHaveTextContent('0件')
  })

  it('locks page movement and item navigation while numeric inputs are invalid', () => {
    const onFocus = vi.fn()
    render(<MasterDataSearch value={many} fields={fields} schema={schema} disabled onSelect={vi.fn()} onFocus={onFocus} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: 'needle' } })
    expect(screen.getByRole('button', { name: '次の検索結果' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '次の検索結果' }))
    expect(screen.getByRole('status')).toHaveTextContent('1〜100件')
    expect(resultButtons().every((button) => (button as HTMLButtonElement).disabled)).toBe(true)
    expect(onFocus).not.toHaveBeenCalled()
  })

  it('keeps results inside the viewport and follows ancestor scrolling and resizing', () => {
    render(<MasterDataSearch value={many} fields={fields} schema={schema} onSelect={vi.fn()} onFocus={vi.fn()} />)
    const input = screen.getByRole('textbox', { name: 'フォーム内検索' })
    let top = window.innerHeight - 100
    const rect = vi.spyOn(input, 'getBoundingClientRect').mockImplementation(() => ({ x: 500, y: top, top, bottom: top + 32, left: 500, right: 700, width: 200, height: 32, toJSON: () => ({}) }))
    try {
      fireEvent.change(input, { target: { value: 'needle' } })
      const popup = screen.getByRole('status').parentElement!
      expect(popup.style.bottom).toBe('100px')
      expect(popup.style.top).toBe('')
      expect(parseFloat(popup.style.maxHeight)).toBeLessThan(top)
      top = 80
      fireEvent.resize(window)
      expect(popup.style.top).toBe('112px')
      expect(popup.style.bottom).toBe('')
      const reads = rect.mock.calls.length
      fireEvent.scroll(screen.getByRole('list'))
      expect(rect).toHaveBeenCalledTimes(reads)
      top = 100
      fireEvent.scroll(input.parentElement!)
      expect(popup.style.top).toBe('132px')
      fireEvent.change(input, { target: { value: '' } })
      const closedReads = rect.mock.calls.length
      fireEvent.resize(window)
      expect(rect).toHaveBeenCalledTimes(closedReads)
    } finally { rect.mockRestore() }
  })

  it('updates schema branch titles when the discriminating value changes', () => {
    const schema: JsonObject = { properties: { action: { oneOf: [
      { properties: { type: { const: 'attack' }, amount: { title: '攻撃量' } } },
      { properties: { type: { const: 'heal' }, amount: { title: '回復量' } } },
    ] } } }
    const props = { fields, schema, onSelect: vi.fn(), onFocus: vi.fn() }
    const { rerender } = render(<MasterDataSearch {...props} value={{ action: { type: 'attack', amount: 10 } }} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'フォーム内検索' }), { target: { value: '回復量' } })
    expect(screen.getByRole('status')).toHaveTextContent('0件')
    rerender(<MasterDataSearch {...props} value={{ action: { type: 'heal', amount: 10 } }} />)
    expect(screen.getByRole('button', { name: '/action/amount に移動' })).toHaveTextContent('回復量')
  })

  it('repositions after parent layout changes or restoring a hidden editor', () => {
    const props = { value: many, fields, schema, onSelect: vi.fn(), onFocus: vi.fn() }
    const { rerender } = render(<div hidden={false}><MasterDataSearch {...props} /></div>)
    const input = screen.getByRole('textbox', { name: 'フォーム内検索' })
    let top = 80
    let visible = true
    const rect = vi.spyOn(input, 'getBoundingClientRect').mockImplementation(() => visible
      ? { x: 500, y: top, top, bottom: top + 32, left: 500, right: 700, width: 200, height: 32, toJSON: () => ({}) }
      : { x: 0, y: 0, top: 0, bottom: 0, left: 0, right: 0, width: 0, height: 0, toJSON: () => ({}) })
    try {
      fireEvent.change(input, { target: { value: 'needle' } })
      const popup = screen.getByRole('status').parentElement!
      expect(popup.style.top).toBe('112px')
      top = 120
      rerender(<div hidden={false}><MasterDataSearch {...props} /></div>)
      expect(popup.style.top).toBe('152px')
      visible = false
      rerender(<div hidden><MasterDataSearch {...props} /></div>)
      fireEvent.resize(window)
      expect(popup.style.top).toBe('152px')
      visible = true
      top = 160
      rerender(<div hidden={false}><MasterDataSearch {...props} /></div>)
      expect(screen.getByRole('status').parentElement).toBe(popup)
      expect(popup.style.top).toBe('192px')
      expect(input).toHaveValue('needle')
    } finally { rect.mockRestore() }
  })
})
