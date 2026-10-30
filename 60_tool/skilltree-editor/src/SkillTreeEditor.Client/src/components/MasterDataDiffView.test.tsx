import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { MasterDataDiff } from './MasterDataDiff'

function rows(dialog: HTMLElement) {
  return [...dialog.querySelectorAll('.master-diff-line')].map(row => ({
    before: row.children[0].textContent,
    after: row.children[1].textContent,
    sign: row.children[2].textContent,
    text: row.querySelector('pre')?.textContent,
  }))
}

function numbered(length: number) {
  return Array.from({ length }, (_, index) => `line ${index + 1}`).join('\n')
}

describe('save preview context', () => {
  it('merges nearby changes and preserves numbers, signs, comments and blank lines in full view', () => {
    const before = ['# comment', '', ...Array.from({ length: 20 }, (_, index) => `line ${index + 1}`)].join('\n')
    const after = before.replace('line 4', 'updated 4').replace('line 9', 'updated 9')
    render(<MasterDataDiff before={before} after={after} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: '保存前の差分' })
    expect(within(dialog).getByText('保存済み → 現在の原稿。2 行追加 / 2 行削除')).toBeInTheDocument()
    expect(within(dialog).getAllByRole('button', { name: /変更のない\d+行を表示/ })).toHaveLength(2)
    expect(rows(dialog).filter(row => row.text === 'line 6')).toHaveLength(1)
    const preview = rows(dialog)
    fireEvent.click(within(dialog).getByRole('button', { name: '全行を表示' }))
    expect(rows(dialog).filter(row => row.text === 'line 6')).toHaveLength(1)
    expect(rows(dialog)).toContainEqual({ before: '1', after: '1', sign: ' ', text: '# comment' })
    expect(rows(dialog)).toContainEqual({ before: '2', after: '2', sign: ' ', text: ' ' })
    expect(rows(dialog)).toContainEqual({ before: '6', after: '', sign: '−', text: 'line 4' })
    expect(rows(dialog)).toContainEqual({ before: '', after: '6', sign: '+', text: 'updated 4' })
    expect(within(dialog).getByText('保存済み → 現在の原稿。2 行追加 / 2 行削除')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: '変更箇所のみ表示' }))
    expect(rows(dialog)).toEqual(preview)
  })

  it('keeps the omission button focused while expanding and folding one gap', () => {
    const before = numbered(30)
    render(<MasterDataDiff before={before} after={before.replace('line 15', 'changed 15')} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: '保存前の差分' })
    const button = within(dialog).getByRole('button', { name: '変更のない11行を表示' })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    button.focus()
    fireEvent.click(button)
    expect(button).toHaveFocus()
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(button).toHaveTextContent('変更のない11行を折りたたむ')
    expect(rows(dialog).some(row => row.text === 'line 1')).toBe(true)
    expect(rows(dialog).some(row => row.text === 'line 30')).toBe(false)
    fireEvent.click(button)
    expect(button).toHaveFocus()
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(rows(dialog).some(row => row.text === 'line 1')).toBe(false)
  })

  it('starts the next comparison folded after a prop update', () => {
    const before = numbered(30)
    const { rerender } = render(<MasterDataDiff before={before} after={before.replace('line 15', 'changed 15')} onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '変更のない11行を表示' }))
    fireEvent.click(screen.getByRole('button', { name: '全行を表示' }))
    rerender(<MasterDataDiff before={before} after={before.replace('line 25', 'changed 25')} onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: '全行を表示' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getAllByRole('button', { name: /変更のない\d+行を表示/ })).toHaveLength(2)
    expect(screen.queryByText('line 1')).not.toBeInTheDocument()
    rerender(<MasterDataDiff before={before} after={before.replace('line 15', 'changed 15')} onClose={vi.fn()} />)
    expect(screen.getByRole('button', { name: '全行を表示' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: '変更のない11行を表示' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('shows an explicit unchanged message and permits the complete text', () => {
    render(<MasterDataDiff before={'# same\n\nvalue: 1'} after={'# same\n\nvalue: 1'} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: '保存前の差分' })
    expect(within(dialog).getByText('変更はありません。全行を表示して原稿を確認できます。')).toBeInTheDocument()
    expect(rows(dialog)).toHaveLength(0)
    fireEvent.click(within(dialog).getByRole('button', { name: '全行を表示' }))
    expect(rows(dialog)).toHaveLength(3)
  })

  it('handles edits at both boundaries and wholly added or removed content', () => {
    const cases = [
      { before: numbered(12), after: numbered(12).replace('line 1', 'new first'), changed: 'new first' },
      { before: numbered(12), after: numbered(12).replace('line 12', 'new last'), changed: 'new last' },
      { before: '', after: numbered(12), changed: 'line 12' },
      { before: numbered(12), after: '', changed: 'line 12' },
    ]
    for (const sample of cases) {
      const view = render(<MasterDataDiff {...sample} onClose={vi.fn()} />)
      const dialog = screen.getByRole('dialog', { name: '保存前の差分' })
      expect(rows(dialog).some(row => row.text === sample.changed)).toBe(true)
      fireEvent.click(within(dialog).getByRole('button', { name: '全行を表示' }))
      expect(rows(dialog).some(row => row.text === sample.changed)).toBe(true)
      view.unmount()
    }
  })

  it('omits DOM rows from a large fallback diff and keeps Escape close behavior', () => {
    const before = numbered(1200)
    const after = before.replace('line 600', 'changed 600')
    const onClose = vi.fn()
    render(<MasterDataDiff before={before} after={after} onClose={onClose} />)
    const dialog = screen.getByRole('dialog', { name: '保存前の差分' })
    expect(rows(dialog)).toHaveLength(8)
    expect(dialog.querySelectorAll('.master-diff-line')).toHaveLength(8)
    fireEvent.click(within(dialog).getByRole('button', { name: '全行を表示' }))
    expect(dialog.querySelectorAll('.master-diff-line')).toHaveLength(1201)
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('renders only two small change groups for distant edits in a large document', () => {
    const before = numbered(1400)
    const after = before.replace('line 100\n', 'changed 100\n').replace('line 1300\n', 'changed 1300\n')
    render(<MasterDataDiff before={before} after={after} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: '保存前の差分' })
    expect(within(dialog).getByText('保存済み → 現在の原稿。2 行追加 / 2 行削除')).toBeInTheDocument()
    expect(rows(dialog)).toHaveLength(16)
    expect(within(dialog).queryByRole('status')).not.toBeInTheDocument()
    expect(within(dialog).getAllByRole('button', { name: /変更のない\d+行を表示/ })).toHaveLength(3)
    fireEvent.click(within(dialog).getByRole('button', { name: '全行を表示' }))
    expect(rows(dialog)).toHaveLength(1402)
  })

  it('explains simplified comparison and removes its notice for the next exact comparison', () => {
    const before = numbered(1100)
    const after = before.replaceAll('line', 'changed')
    const view = render(<MasterDataDiff before={before} after={after} onClose={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('変更のない行が追加・削除に含まれる場合があります')
    expect(rows(screen.getByRole('dialog', { name: '保存前の差分' }))).toHaveLength(2200)
    view.rerender(<MasterDataDiff before={before} after={before} onClose={vi.fn()} />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByText('変更はありません。全行を表示して原稿を確認できます。')).toBeInTheDocument()
  })
})
