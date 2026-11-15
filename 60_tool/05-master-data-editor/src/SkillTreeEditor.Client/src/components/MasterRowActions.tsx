import { useEffect, useRef } from 'react'
import type { MouseEvent } from 'react'
import type { JsonValue } from '../types/editor'

interface Props {
  path: string
  row: JsonValue
  disabled?: boolean
  onUp?: () => void
  onDown?: () => void
  onDuplicate?: () => void
  onReplace?: (opener: HTMLElement) => void
  replaceLabel?: string
  onDelete: () => void
}

export function MasterRowActions({ path, row, disabled, onUp, onDown, onDuplicate, onReplace, replaceLabel, onDelete }: Props) {
  const details = useRef<HTMLDetailsElement>(null)
  useEffect(() => { if (details.current) details.current.open = false }, [row, disabled])
  const run = (event: MouseEvent<HTMLButtonElement>, action: (opener: HTMLElement) => void) => {
    const menu = event.currentTarget.closest('details')
    const summary = menu?.querySelector('summary')
    if (!menu || !summary || disabled) return
    menu.open = false
    summary.focus()
    action(summary)
  }
  return <details ref={details} className="master-row-actions" onKeyDown={event => {
    if (event.key !== 'Escape' || !event.currentTarget.open) return
    event.stopPropagation()
    event.currentTarget.open = false
    event.currentTarget.querySelector('summary')?.focus()
  }}>
    <summary aria-label={path + ' の操作'} title="行の操作" aria-disabled={disabled || undefined} onClick={event => {
      if (disabled || event.currentTarget.closest('fieldset:disabled')) event.preventDefault()
    }}>⋯</summary>
    <div className="master-row-actions-body">
      {onReplace && <button type="button" disabled={disabled} className="button compact subtle" aria-label={replaceLabel}
        onClick={event => run(event, onReplace)}>変更</button>}
      {onUp && <button type="button" disabled={disabled} className="button compact subtle" aria-label={path + ' 上へ'}
        onClick={event => run(event, onUp)}>上へ</button>}
      {onDown && <button type="button" disabled={disabled} className="button compact subtle" aria-label={path + ' 下へ'}
        onClick={event => run(event, onDown)}>下へ</button>}
      {onDuplicate && <button type="button" disabled={disabled} className="button compact subtle" aria-label={path + ' を複製'}
        onClick={event => run(event, onDuplicate)}>複製</button>}
      <button type="button" disabled={disabled} className="button compact danger subtle" aria-label={path + ' を削除'}
        onClick={event => run(event, onDelete)}>削除</button>
    </div>
  </details>
}
