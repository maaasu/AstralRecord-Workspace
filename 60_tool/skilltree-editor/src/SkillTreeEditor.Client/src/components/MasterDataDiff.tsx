import { useId, useMemo, useRef, useState } from 'react'
import { Modal } from './Modal'
import { masterLineDiff, type DiffLine } from '../data/masterLineDiff'

interface DiffSection { start: number; end: number; omitted: boolean }

function diffSections(lines: DiffLine[]): DiffSection[] {
  const visible: { start: number; end: number }[] = []
  for (let index = 0; index < lines.length; index++) {
    if (lines[index].kind === 'same') continue
    const start = Math.max(0, index - 3)
    const end = Math.min(lines.length, index + 4)
    const last = visible[visible.length - 1]
    if (last && start <= last.end) last.end = Math.max(last.end, end)
    else visible.push({ start, end })
  }
  const sections: DiffSection[] = []
  let cursor = 0
  for (const range of visible) {
    if (cursor < range.start) sections.push({ start: cursor, end: range.start, omitted: true })
    sections.push({ ...range, omitted: false })
    cursor = range.end
  }
  if (cursor < lines.length) sections.push({ start: cursor, end: lines.length, omitted: true })
  return sections
}

function DiffRow({ line, index, selected }: { line: DiffLine; index: number; selected: boolean }) {
  return <div data-diff-index={index} className={`master-diff-line ${line.kind}${selected ? ' current-change' : ''}`}><span>{line.before ?? ''}</span><span>{line.after ?? ''}</span><b>{line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' '}</b><pre>{line.text || ' '}</pre></div>
}

export function MasterDataDiff({ before, after, onClose }: { before: string; after: string; onClose: () => void }) {
  const comparison = useMemo(() => {
    const { lines, simplified } = masterLineDiff(before, after)
    const changes: { start: number; end: number }[] = []
    for (let index = 0; index < lines.length; index++) {
      if (lines[index].kind === 'same') continue
      const previous = changes[changes.length - 1]
      if (previous?.end === index) previous.end++
      else changes.push({ start: index, end: index + 1 })
    }
    return {
      lines, simplified, changes, sections: diffSections(lines),
      added: lines.filter(line => line.kind === 'added').length,
      removed: lines.filter(line => line.kind === 'removed').length,
    }
  }, [before, after])
  const { lines, sections, changes, added, removed } = comparison
  const [view, setView] = useState({ comparison, showAll: false, activeChange: -1, expanded: new Set<number>() })
  // Reset during render so a reused dialog never shows an earlier comparison's expansion.
  const changedComparison = view.comparison !== comparison
  const currentView = changedComparison ? { comparison, showAll: false, activeChange: -1, expanded: new Set<number>() } : view
  if (changedComparison) setView(currentView)
  const toggleOmission = (start: number) => {
    setView(previous => {
      const expanded = new Set(previous.expanded)
      if (expanded.has(start)) expanded.delete(start)
      else expanded.add(start)
      return { ...previous, expanded }
    })
  }
  const sectionId = useId()
  const scrollRef = useRef<HTMLDivElement>(null)
  const selected = changes[currentView.activeChange]
  const renderRow = (line: DiffLine, index: number) => <DiffRow key={index} line={line} index={index} selected={!!selected && index >= selected.start && index < selected.end} />
  const jumpToChange = (index: number) => {
    const change = changes[index]
    const container = scrollRef.current
    const target = change && container?.querySelector<HTMLElement>(`[data-diff-index="${change.start}"]`)
    if (!container || !target) return
    setView({ ...currentView, activeChange: index })
    // Keep repeated keyboard activation on the button; scroll only this pane.
    container.scrollTop = Math.max(0, container.scrollTop + target.getBoundingClientRect().top - container.getBoundingClientRect().top - container.clientHeight / 2 + target.offsetHeight / 2)
  }
  return <Modal className="master-diff-dialog" ariaLabel="保存前の差分" onClose={onClose}>
    <header className="modal-header">
      <div><h2>保存前の差分</h2><p className="master-muted">保存済み → 現在の原稿。{added} 行追加 / {removed} 行削除</p><p className="master-diff-position" aria-live="polite" aria-atomic="true">{comparison.simplified ? '表示上の変更箇所' : '変更箇所'}: {currentView.activeChange >= 0 ? `${currentView.activeChange + 1} / ` : ''}{changes.length}</p></div>
      <div className="master-diff-actions">
        <button type="button" className="button" onClick={onClose}>閉じる</button>
        <button type="button" className="button" aria-pressed={currentView.showAll} onClick={() => setView({ ...currentView, showAll: !currentView.showAll })}>{currentView.showAll ? '変更箇所のみ表示' : '全行を表示'}</button>
        <button type="button" className="button" disabled={changes.length === 0} aria-disabled={currentView.activeChange <= 0} aria-controls={`${sectionId}-lines`} onClick={() => { if (currentView.activeChange > 0) jumpToChange(currentView.activeChange - 1) }}>前の変更</button>
        <button type="button" className="button" disabled={changes.length === 0} aria-disabled={currentView.activeChange >= changes.length - 1} aria-controls={`${sectionId}-lines`} onClick={() => jumpToChange(currentView.activeChange + 1)}>次の変更</button>
      </div>
    </header>
    <div className="master-diff-lines" id={`${sectionId}-lines`} ref={scrollRef}>
      {comparison.simplified && <p role="status" className="master-message warning">計算量を抑えるため、一部をまとめて差分表示しています。変更のない行が追加・削除に含まれる場合があります。</p>}
      {added === 0 && removed === 0 && <p className="master-diff-empty">変更はありません。全行を表示して原稿を確認できます。</p>}
      {currentView.showAll ? lines.map(renderRow) : sections.map(section => section.omitted ? <div key={section.start} className="master-diff-omission">
        <button type="button" className="button" aria-expanded={currentView.expanded.has(section.start)} aria-controls={`${sectionId}-${section.start}`} onClick={() => toggleOmission(section.start)}>{`変更のない${section.end - section.start}行を${currentView.expanded.has(section.start) ? '折りたたむ' : '表示'}`}</button>
        <div id={`${sectionId}-${section.start}`}>{currentView.expanded.has(section.start) && lines.slice(section.start, section.end).map((line, offset) => renderRow(line, section.start + offset))}</div>
      </div> : <div key={section.start}>{lines.slice(section.start, section.end).map((line, offset) => renderRow(line, section.start + offset))}</div>)}
    </div>
  </Modal>
}
