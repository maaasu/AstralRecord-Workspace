import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { ResizableTable, ResetTableColumns } from './ResizableTable'

const nameColumn = { id: 'name', label: '名前', width: 140 }
const detailsColumn = { id: 'details', label: '詳細', width: 220, minWidth: 100 }
const columns = [nameColumn, detailsColumn]

function Example({ label = '一覧', shownColumns = columns, storageKey = 'test' } = {}) {
  return <ResizableTable label={label} storageKey={storageKey} columns={shownColumns}>
    <tbody><tr>{shownColumns.map(column => <td key={column.id}>{column.label}の値</td>)}</tr></tbody>
  </ResizableTable>
}

const table = (label = '一覧') => within(screen.getByRole('region', { name: label })).getByRole('table')
const grip = (name: string, label = '一覧') => within(table(label)).getByRole('separator', { name: `${name}列の幅を変更` })
const width = (target: HTMLElement) => Number(target.getAttribute('aria-valuenow'))

beforeEach(() => localStorage.clear())

it('applies the dragged width to the column and keeps the remaining columns within the scroll region', () => {
  render(<Example />)
  const handle = grip('名前')
  expect(screen.getByRole('region', { name: '一覧' })).toHaveAttribute('tabindex', '0')
  expect(within(table()).getByRole('columnheader', { name: '詳細' })).toBeInTheDocument()
  fireEvent.pointerDown(handle, { button: 0, clientX: 140, pointerId: 1 })
  fireEvent.pointerMove(window, { clientX: 204, pointerId: 1 })
  fireEvent.pointerUp(window, { pointerId: 1 })
  expect(table().querySelectorAll('col')[0]).toHaveStyle({ width: '204px' })
  expect(table()).toHaveStyle({ width: '424px' })
  expect(width(grip('詳細'))).toBe(220)
  fireEvent.doubleClick(handle)
  expect(table()).toHaveStyle({ width: '360px' })
})

it('shares and remembers widths by column ID when columns or table instances change', () => {
  const mounted = render(<><Example /><Example label="同じ種類" /></>)
  fireEvent.keyDown(grip('詳細'), { key: 'ArrowLeft', shiftKey: true })
  expect(width(grip('詳細', '同じ種類'))).toBe(172)
  fireEvent.keyDown(grip('名前', '同じ種類'), { key: 'ArrowRight' })
  expect(width(grip('名前'))).toBe(156)
  mounted.unmount()

  const next = render(<Example shownColumns={[detailsColumn]} />)
  expect(width(grip('詳細'))).toBe(172)
  fireEvent.keyDown(grip('詳細'), { key: 'Home' })
  expect(width(grip('詳細'))).toBe(100)
  fireEvent.keyDown(grip('詳細'), { key: 'Enter' })
  next.rerender(<Example shownColumns={[detailsColumn, nameColumn]} />)
  expect(width(grip('詳細'))).toBe(220)
  expect(width(grip('名前'))).toBe(156)
})

it('resets every saved width for the selected table and keeps keyboard focus', async () => {
  const user = userEvent.setup()
  const tableKey = 'astralrecord.layout.table.test'
  const otherKey = 'astralrecord.layout.table.other'
  localStorage.setItem(tableKey, '{"name":960,"details":100,"hidden":320}')
  localStorage.setItem(otherKey, '{"name":180}')
  const mounted = render(<><ResetTableColumns storageKey="test" /><Example /><Example storageKey="other" label="別の一覧" /></>)
  const reset = screen.getByRole('button', { name: '列幅をリセット' })
  expect(reset).toHaveAttribute('aria-disabled', 'false')
  expect(width(grip('名前'))).toBe(960)
  expect(width(grip('詳細'))).toBe(100)

  await user.tab()
  await user.keyboard('{Enter}')
  expect(reset).toHaveFocus()
  expect(reset).toHaveAttribute('aria-disabled', 'true')
  expect(width(grip('名前'))).toBe(140)
  expect(width(grip('詳細'))).toBe(220)
  expect(width(grip('名前', '別の一覧'))).toBe(180)
  expect(localStorage.getItem(tableKey)).toBeNull()
  expect(localStorage.getItem(otherKey)).toBe('{"name":180}')

  mounted.unmount()
  render(<Example />)
  expect(width(grip('名前'))).toBe(140)
  expect(width(grip('詳細'))).toBe(220)
})

it('falls back to defaults for invalid values and allows adjustment when browser storage is blocked', () => {
  localStorage.setItem('astralrecord.layout.table.test', '{"name":"bad value","details":5000}')
  const mounted = render(<Example />)
  expect(width(grip('名前'))).toBe(140)
  expect(width(grip('詳細'))).toBe(960)
  mounted.unmount()

  const read = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
  const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
  const remove = vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked') })
  try {
    render(<><ResetTableColumns storageKey="blocked" /><Example storageKey="blocked" /></>)
    fireEvent.keyDown(grip('名前'), { key: 'ArrowRight' })
    expect(width(grip('名前'))).toBe(156)
    fireEvent.click(screen.getByRole('button', { name: '列幅をリセット' }))
    expect(width(grip('名前'))).toBe(140)
  } finally {
    read.mockRestore()
    write.mockRestore()
    remove.mockRestore()
  }
})

it('scrolls a newly focused body control below the sticky header', () => {
  render(<ResizableTable label="一覧" storageKey="focus" columns={[nameColumn]}>
    <tbody><tr><td><button type="button">編集</button></td></tr></tbody>
  </ResizableTable>)
  const region = screen.getByRole('region', { name: '一覧' })
  const header = screen.getByRole('columnheader', { name: '名前' })
  const button = screen.getByRole('button', { name: '編集' })
  vi.spyOn(header, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 100, 140, 56))
  const buttonRect = vi.spyOn(button, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 145, 60, 28))
  region.scrollTop = 120
  fireEvent.focus(button)
  expect(region.scrollTop).toBe(107)

  buttonRect.mockReturnValue(new DOMRect(0, 160, 60, 28))
  fireEvent.focus(button)
  expect(region.scrollTop).toBe(107)
})
