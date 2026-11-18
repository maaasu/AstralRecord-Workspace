import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { masterDataApi } from '../api/masterDataApi'
import { handleAndroidDialogBack } from '../androidEditor'
import type { MasterFileSummary } from '../types/masterData'
import { MasterQuickSearch } from './MasterQuickSearch'

const file = (path: string, name: string, id: string): MasterFileSummary => ({ path, name, id, category: '', format: 'yaml', revision: '1', size: 1, modifiedUtc: '' })
const files = [file('items/forest/sword.yml', '&a森の剣', 'SWORD_01'), file('mobs/forest/wolf.yml', '森のウルフ', 'WOLF_01')]
const searchInput = () => screen.getByRole('combobox', { name: 'マスターをクイック検索' })
afterEach(() => vi.restoreAllMocks())

it('dismisses tablet search with Android Back without opening the selected result', async () => {
  const previousUrl = window.location.href
  window.history.replaceState(null, '', '/?mobile=android')
  try {
    vi.spyOn(masterDataApi, 'files').mockResolvedValue(files)
    const open = vi.fn()
    render(<MasterQuickSearch onOpen={open} />)
    await userEvent.setup().click(searchInput())
    await screen.findAllByRole('option')
    act(() => { expect(handleAndroidDialogBack()).toBe(true) })
    expect(searchInput()).toHaveAttribute('aria-expanded', 'false')
    expect(open).not.toHaveBeenCalled()
    expect(handleAndroidDialogBack()).toBe(false)
  } finally {
    window.history.replaceState(null, '', previousUrl)
  }
})

it('searches formatted Japanese names, IDs and paths with multiple AND terms without requests per keystroke', async () => {
  const api = vi.spyOn(masterDataApi, 'files').mockResolvedValue(files)
  const open = vi.fn()
  const user = userEvent.setup()
  render(<MasterQuickSearch onOpen={open} />)
  await user.click(searchInput())
  expect(await screen.findAllByRole('option')).toHaveLength(2)
  fireEvent.change(searchInput(), { target: { value: '森　SWORD_01 forest' } })
  expect(screen.getAllByRole('option')).toHaveLength(1)
  fireEvent.keyDown(searchInput(), { key: 'Enter' })
  expect(open).toHaveBeenCalledExactlyOnceWith(files[0].path)
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  expect(api).toHaveBeenCalledTimes(1)
})

it('opens from Ctrl+P, selects by arrows, and restores the previous focus with Escape', async () => {
  vi.spyOn(masterDataApi, 'files').mockResolvedValue(files)
  const open = vi.fn()
  const user = userEvent.setup()
  render(<><button>編集に戻る</button><MasterQuickSearch onOpen={open} /></>)
  const previous = screen.getByRole('button', { name: '編集に戻る' })
  previous.focus()
  await user.keyboard('{Control>}p{/Control}')
  expect(searchInput()).toHaveFocus()
  await screen.findAllByRole('option')
  await user.keyboard('{ArrowDown}')
  expect(screen.getAllByRole('option')[1]).toHaveAttribute('aria-selected', 'true')
  expect(searchInput()).toHaveAttribute('aria-activedescendant', screen.getAllByRole('option')[1].id)
  await user.keyboard('{Escape}')
  expect(previous).toHaveFocus()
  expect(searchInput()).toHaveAttribute('aria-expanded', 'false')
  await user.keyboard('{Control>}p{/Control}')
  await screen.findAllByRole('option')
  await user.keyboard('{ArrowDown}{Enter}')
  expect(open).toHaveBeenCalledExactlyOnceWith(files[1].path)
})

it('does not open a file or close the popup while confirming Japanese IME input', async () => {
  vi.spyOn(masterDataApi, 'files').mockResolvedValue(files)
  const open = vi.fn()
  const user = userEvent.setup()
  render(<MasterQuickSearch onOpen={open} />)
  await user.click(searchInput())
  await screen.findAllByRole('option')
  for (const flags of [{ isComposing: true }, { keyCode: 229 }]) {
    for (const key of ['ArrowDown', 'Enter', 'Escape']) fireEvent.keyDown(searchInput(), { key, ...flags })
  }
  for (const flags of [{ ctrlKey: true }, { metaKey: true }, { altKey: true }, { shiftKey: true }]) {
    for (const key of ['ArrowDown', 'Enter']) fireEvent.keyDown(searchInput(), { key, ...flags })
  }
  expect(screen.getAllByRole('option')[0]).toHaveAttribute('aria-selected', 'true')
  expect(searchInput()).toHaveAttribute('aria-expanded', 'true')
  expect(open).not.toHaveBeenCalled()
})

it('refreshes when reopened and ignores an older request arriving after the new one', async () => {
  let resolveOld!: (value: MasterFileSummary[]) => void
  const api = vi.spyOn(masterDataApi, 'files')
    .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    .mockResolvedValueOnce([files[1]])
  const user = userEvent.setup()
  render(<><button>ほかの作業</button><MasterQuickSearch onOpen={vi.fn()} /></>)
  await user.click(searchInput())
  await user.click(screen.getByRole('button', { name: 'ほかの作業' }))
  await user.click(searchInput())
  expect(await screen.findAllByRole('option')).toHaveLength(1)
  await act(async () => resolveOld([files[0]]))
  expect(screen.getByRole('option')).toHaveTextContent('森のウルフ')
  expect(api).toHaveBeenCalledTimes(2)
})

it('shows loading, empty and retry states and allows clicking a result', async () => {
  const api = vi.spyOn(masterDataApi, 'files').mockRejectedValueOnce(new Error('接続を確認してください')).mockResolvedValueOnce(files)
  const open = vi.fn()
  const user = userEvent.setup()
  render(<MasterQuickSearch onOpen={open} />)
  await user.click(searchInput())
  expect(await screen.findByRole('alert')).toHaveTextContent('接続を確認してください')
  await user.click(screen.getByRole('button', { name: '再試行' }))
  await screen.findAllByRole('option')
  fireEvent.change(searchInput(), { target: { value: '存在しないマスター' } })
  expect(screen.getByText(/一致するファイルがありません/)).toBeVisible()
  expect(searchInput()).not.toHaveAttribute('aria-activedescendant')
  fireEvent.change(searchInput(), { target: { value: 'wolf' } })
  await user.click(screen.getByRole('option'))
  expect(open).toHaveBeenCalledExactlyOnceWith(files[1].path)
  expect(api).toHaveBeenCalledTimes(2)
})

it('renders in batches while keyboard navigation can reach files beyond the first batch', async () => {
  vi.spyOn(masterDataApi, 'files').mockResolvedValue(Array.from({ length: 125 }, (_, index) => file(`items/${index}.yml`, `アイテム${index}`, `${index}`)))
  const open = vi.fn()
  const user = userEvent.setup()
  render(<MasterQuickSearch onOpen={open} />)
  await user.click(searchInput())
  expect(await screen.findAllByRole('option')).toHaveLength(50)
  for (let index = 0; index < 50; index++) fireEvent.keyDown(searchInput(), { key: 'ArrowDown' })
  expect(screen.getAllByRole('option')).toHaveLength(51)
  expect(screen.getAllByRole('option')[50]).toHaveAttribute('aria-selected', 'true')
  await user.click(screen.getByRole('button', { name: /さらに50件/ }))
  expect(screen.getAllByRole('option')).toHaveLength(101)
  fireEvent.change(searchInput(), { target: { value: '124.yml' } })
  await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(1))
  fireEvent.keyDown(searchInput(), { key: 'Enter' })
  expect(open).toHaveBeenCalledExactlyOnceWith('items/124.yml')
})

it('closes with Escape from the retry and load-more controls, while ignoring IME Escape', async () => {
  vi.spyOn(masterDataApi, 'files').mockRejectedValueOnce(new Error('接続エラー'))
    .mockResolvedValue(Array.from({ length: 51 }, (_, index) => file(`items/${index}.yml`, `アイテム${index}`, `${index}`)))
  const user = userEvent.setup()
  render(<><button>戻り先</button><MasterQuickSearch onOpen={vi.fn()} /></>)
  const previous = screen.getByRole('button', { name: '戻り先' })
  previous.focus()
  await user.keyboard('{Control>}p{/Control}')
  const retry = await screen.findByRole('button', { name: '再試行' })
  await user.tab()
  expect(retry).toHaveFocus()
  fireEvent.keyDown(retry, { key: 'Escape', isComposing: true })
  expect(searchInput()).toHaveAttribute('aria-expanded', 'true')
  await user.keyboard('{Escape}')
  expect(previous).toHaveFocus()
  expect(searchInput()).toHaveAttribute('aria-expanded', 'false')
  await user.keyboard('{Control>}p{/Control}')
  const more = await screen.findByRole('button', { name: /さらに50件/ })
  await user.tab()
  expect(more).toHaveFocus()
  fireEvent.keyDown(more, { key: 'Escape', keyCode: 229 })
  expect(searchInput()).toHaveAttribute('aria-expanded', 'true')
  await user.keyboard('{Escape}')
  expect(searchInput()).toHaveAttribute('aria-expanded', 'false')
  expect(previous).toHaveFocus()
})
