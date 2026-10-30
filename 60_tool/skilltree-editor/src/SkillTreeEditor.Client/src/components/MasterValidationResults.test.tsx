import { createRef } from 'react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ValidationReport } from '../types/editor'
import { MasterValidationResults } from './MasterValidationResults'

const emptyInputs = {}
const report: ValidationReport = { isValid: false, issues: [
  { code: 'missing-id', path: '/id', severity: 'error', message: 'IDを入力してください。', file: 'classes/warrior.yml' },
  { code: 'duplicate', path: '/levels/2/mob', severity: 'error', message: '森のウルフが重複しています。' },
  { code: 'unused', path: '/levels/5', severity: 'warning', message: '森のウルフを確認してください。' },
] }
const props = { report, invalidInputs: emptyInputs, headingRef: createRef<HTMLButtonElement>(), active: true, onSelect: vi.fn() }
const search = () => screen.getByRole('textbox', { name: '検証結果を検索' })
const list = () => within(screen.getByRole('list', { name: '検証結果の一覧' }))

describe('master validation results', () => {
  it('counts input errors and server issues, filters severity and supports AND search across fields', () => {
    render(<MasterValidationResults {...props} invalidInputs={{ '/amount': '整数を入力してください。' }} />)
    expect(screen.getByRole('button', { name: /検証結果.*エラー 3 件.*警告 1 件/ })).toBeInTheDocument()
    expect(list().getAllByRole('listitem')).toHaveLength(4)
    fireEvent.change(search(), { target: { value: '森 ウルフ' } })
    expect(list().getAllByRole('listitem')).toHaveLength(2)
    fireEvent.change(screen.getByRole('combobox', { name: '検証結果の種別' }), { target: { value: 'warning' } })
    expect(list().getAllByRole('listitem')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent('1 / 4 件')
    fireEvent.click(screen.getByRole('button', { name: '条件を解除' }))
    expect(search()).toHaveFocus()
    fireEvent.change(search(), { target: { value: 'MISSING warrior.yml' } })
    expect(list().getByRole('button', { name: '/id' })).toBeInTheDocument()
    fireEvent.change(search(), { target: { value: '入力エラー 整数' } })
    expect(list().getByRole('button', { name: '/amount' })).toBeInTheDocument()
    fireEvent.change(search(), { target: { value: '存在しない' } })
    expect(list().queryByRole('listitem')).not.toBeInTheDocument()
    expect(screen.getByText(/条件に一致する検証結果はありません/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /エラー 3 件.*警告 1 件/ })).toBeInTheDocument()
  })

  it('renders no more than 50 of 5000 issues and can reach the last result through search', () => {
    const large: ValidationReport = { isValid: false, issues: Array.from({ length: 5000 }, (_, index) => ({ code: 'required', path: `/levels/${index}/name`, severity: 'error', message: `必須の名前 ${index}` })) }
    render(<MasterValidationResults {...props} report={large} />)
    expect(list().getAllByRole('listitem')).toHaveLength(50)
    expect(screen.getByRole('status')).toHaveTextContent('5000 / 5000 件 · 1–50 件を表示')
    fireEvent.click(screen.getByRole('button', { name: '次の検証結果' }))
    expect(list().getAllByRole('listitem')).toHaveLength(50)
    expect(list().getByRole('button', { name: '/levels/50/name' })).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent('51–100 件を表示')
    fireEvent.change(search(), { target: { value: '/4999/ 必須' } })
    expect(list().getAllByRole('listitem')).toHaveLength(1)
    expect(list().getByRole('button', { name: '/levels/4999/name' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('1 / 5000 件 · 1–1 件を表示')
  })

  it('opens and resets filters for a new report while preserving them across inactive tabs', () => {
    const view = render(<MasterValidationResults {...props} />)
    fireEvent.change(search(), { target: { value: 'ウルフ' } })
    fireEvent.change(screen.getByRole('combobox', { name: '検証結果の種別' }), { target: { value: 'warning' } })
    view.rerender(<MasterValidationResults {...props} active={false} />)
    view.rerender(<MasterValidationResults {...props} />)
    expect(search()).toHaveValue('ウルフ')
    expect(list().getAllByRole('listitem')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: /検証結果.*エラー/ }))
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
    view.rerender(<MasterValidationResults {...props} report={{ isValid: false, issues: [{ code: 'new', path: '/name', severity: 'error', message: '新しい問題' }] }} />)
    expect(search()).toHaveValue('')
    expect(screen.getByRole('combobox')).toHaveValue('all')
    expect(list().getByRole('button', { name: '/name' })).toBeInTheDocument()
  })

  it('supports keyboard navigation and ignores Japanese composition', () => {
    const onSelect = vi.fn()
    render(<MasterValidationResults {...props} onSelect={onSelect} />)
    search().focus()
    fireEvent.keyDown(search(), { key: 'Enter', isComposing: true })
    fireEvent.keyDown(search(), { key: 'Enter', keyCode: 229 })
    expect(search()).toHaveFocus()
    fireEvent.keyDown(search(), { key: 'ArrowDown' })
    const buttons = list().getAllByRole('button')
    expect(buttons[0]).toHaveFocus()
    fireEvent.keyDown(buttons[0], { key: 'End' })
    expect(buttons[2]).toHaveFocus()
    fireEvent.keyDown(buttons[2], { key: 'ArrowUp' })
    expect(buttons[1]).toHaveFocus()
    fireEvent.click(buttons[1])
    expect(onSelect).toHaveBeenCalledWith('/levels/2/mob')
    fireEvent.keyDown(buttons[1], { key: 'Escape' })
    expect(search()).toHaveFocus()
  })

  it('restores focus when an input error disappears but does not focus an inactive pane', () => {
    const view = render(<MasterValidationResults {...props} invalidInputs={{ '/amount': '数値エラー' }} />)
    list().getByRole('button', { name: '/amount' }).focus()
    view.rerender(<MasterValidationResults {...props} />)
    expect(search()).toHaveFocus()
    view.rerender(<MasterValidationResults {...props} invalidInputs={{ '/amount': '数値エラー' }} />)
    list().getByRole('button', { name: '/amount' }).focus()
    view.rerender(<MasterValidationResults {...props} active={false} />)
    expect(search()).not.toHaveFocus()
  })

  it('handles root errors, successful validation with warnings and empty results', () => {
    const onSelect = vi.fn()
    const view = render(<MasterValidationResults {...props} onSelect={onSelect} report={{ isValid: true, issues: [{ code: 'root', message: '原稿を確認', severity: 'warning' }] }} />)
    fireEvent.click(list().getByRole('button', { name: '/' }))
    expect(onSelect).toHaveBeenCalledWith(undefined)
    expect(screen.getByRole('button', { name: /検証結果.*成功.*警告 1 件/ })).toBeInTheDocument()
    view.rerender(<MasterValidationResults {...props} report={{ isValid: true, issues: [] }} />)
    expect(screen.getByText('検証エラーはありません。')).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })
})
