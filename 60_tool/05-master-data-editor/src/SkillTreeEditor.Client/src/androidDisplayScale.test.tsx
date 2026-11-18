import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { AndroidDisplayScale, useAndroidDisplayScale } from './androidDisplayScale'

const storageKey = 'astralrecord.android.display-scale'
const standardViewport = 'width=device-width, initial-scale=1.0'
let viewport: HTMLMetaElement

function Harness() {
  const display = useAndroidDisplayScale()
  return <>
    <AndroidDisplayScale {...display} />
    <input aria-label="下書き" />
  </>
}

beforeEach(() => {
  window.localStorage.removeItem(storageKey)
  viewport = document.createElement('meta')
  viewport.name = 'viewport'
  viewport.content = standardViewport
  document.head.append(viewport)
})

afterEach(() => {
  cleanup()
  viewport.remove()
  window.localStorage.removeItem(storageKey)
})

describe('Android tablet display scale', () => {
  it('applies the default 80% viewport and restores the original metadata on exit', () => {
    const { unmount } = render(<Harness />)
    expect(screen.getByRole('combobox', { name: '表示倍率' })).toHaveValue('80')
    expect(viewport.content).toBe('initial-scale=0.8, minimum-scale=0.8, maximum-scale=0.8')
    unmount()
    expect(viewport.content).toBe(standardViewport)
  })

  it('changes the viewport in place without replacing the editor and remembers the selection for the next launch', () => {
    const { unmount } = render(<Harness />)
    const draft = screen.getByRole('textbox', { name: '下書き' })
    fireEvent.change(draft, { target: { value: '編集中の値' } })
    fireEvent.change(screen.getByRole('combobox', { name: '表示倍率' }), { target: { value: '60' } })
    expect(window.localStorage.getItem(storageKey)).toBe('60')
    expect(viewport.content).toBe('initial-scale=0.6, minimum-scale=0.6, maximum-scale=0.6')
    expect(screen.getByRole('textbox', { name: '下書き' })).toBe(draft)
    expect(draft).toHaveValue('編集中の値')

    unmount()
    render(<Harness />)
    expect(screen.getByRole('combobox', { name: '表示倍率' })).toHaveValue('60')
    expect(viewport.content).toContain('initial-scale=0.6')
  })

  it('ignores an invalid saved preference', () => {
    window.localStorage.setItem(storageKey, '99')
    render(<Harness />)
    expect(screen.getByRole('combobox', { name: '表示倍率' })).toHaveValue('80')
  })
})
