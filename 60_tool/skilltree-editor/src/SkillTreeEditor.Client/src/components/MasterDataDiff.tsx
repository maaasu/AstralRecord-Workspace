import { useId, useMemo, useState } from 'react'
import { Modal } from './Modal'

export interface DiffLine { kind: 'same' | 'added' | 'removed'; text: string; before?: number; after?: number }

export function masterLineDiff(before: string, after: string): DiffLine[] {
  const left = before.replaceAll('\r\n', '\n').split('\n')
  const right = after.replaceAll('\r\n', '\n').split('\n')
  // Bound memory for large files. An exact common prefix/suffix remains useful
  // without allocating a quadratic matrix for multi-megabyte master documents.
  if (left.length * right.length > 1_000_000) {
    let prefix = 0
    while (prefix < left.length && prefix < right.length && left[prefix] === right[prefix]) prefix++
    let suffix = 0
    while (suffix < left.length - prefix && suffix < right.length - prefix && left[left.length - suffix - 1] === right[right.length - suffix - 1]) suffix++
    return [
      ...left.slice(0, prefix).map((text, index) => ({ kind: 'same' as const, text, before: index + 1, after: index + 1 })),
      ...left.slice(prefix, left.length - suffix).map((text, index) => ({ kind: 'removed' as const, text, before: prefix + index + 1 })),
      ...right.slice(prefix, right.length - suffix).map((text, index) => ({ kind: 'added' as const, text, after: prefix + index + 1 })),
      ...left.slice(left.length - suffix).map((text, index) => ({ kind: 'same' as const, text, before: left.length - suffix + index + 1, after: right.length - suffix + index + 1 })),
    ]
  }
  const lengths = Array.from({ length: left.length + 1 }, () => new Uint32Array(right.length + 1))
  for (let i = left.length - 1; i >= 0; i--) for (let j = right.length - 1; j >= 0; j--) {
    lengths[i][j] = left[i] === right[j] ? lengths[i + 1][j + 1] + 1 : Math.max(lengths[i + 1][j], lengths[i][j + 1])
  }
  const result: DiffLine[] = []
  let i = 0; let j = 0
  while (i < left.length || j < right.length) {
    if (i < left.length && j < right.length && left[i] === right[j]) { result.push({ kind: 'same', text: left[i], before: ++i, after: ++j }) }
    else if (i < left.length && (j === right.length || lengths[i + 1][j] >= lengths[i][j + 1])) { result.push({ kind: 'removed', text: left[i], before: ++i }) }
    else { result.push({ kind: 'added', text: right[j], after: ++j }) }
  }
  return result
}

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

function DiffRow({ line }: { line: DiffLine }) {
  return <div className={`master-diff-line ${line.kind}`}><span>{line.before ?? ''}</span><span>{line.after ?? ''}</span><b>{line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' '}</b><pre>{line.text || ' '}</pre></div>
}

export function MasterDataDiff({ before, after, onClose }: { before: string; after: string; onClose: () => void }) {
  const comparison = useMemo(() => {
    const lines = masterLineDiff(before, after)
    return {
      lines, sections: diffSections(lines),
      added: lines.filter(line => line.kind === 'added').length,
      removed: lines.filter(line => line.kind === 'removed').length,
    }
  }, [before, after])
  const { lines, sections, added, removed } = comparison
  const [view, setView] = useState({ comparison, showAll: false, expanded: new Set<number>() })
  // Reset during render so a reused dialog never shows an earlier comparison's expansion.
  const changedComparison = view.comparison !== comparison
  const currentView = changedComparison ? { comparison, showAll: false, expanded: new Set<number>() } : view
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
  return <Modal className="master-diff-dialog" ariaLabel="保存前の差分" onClose={onClose}>
    <header className="modal-header"><div><h2>保存前の差分</h2><p className="master-muted">保存済み → 現在の原稿。{added} 行追加 / {removed} 行削除</p></div><div className="master-diff-actions"><button type="button" className="button" onClick={onClose}>閉じる</button><button type="button" className="button" aria-pressed={currentView.showAll} onClick={() => setView({ ...currentView, showAll: !currentView.showAll })}>{currentView.showAll ? '変更箇所のみ表示' : '全行を表示'}</button></div></header>
    <div className="master-diff-lines">
      {added === 0 && removed === 0 && <p className="master-diff-empty">変更はありません。全行を表示して原稿を確認できます。</p>}
      {currentView.showAll ? lines.map((line, index) => <DiffRow key={index} line={line} />) : sections.map(section => section.omitted ? <div key={section.start} className="master-diff-omission">
        <button type="button" className="button" aria-expanded={currentView.expanded.has(section.start)} aria-controls={`${sectionId}-${section.start}`} onClick={() => toggleOmission(section.start)}>{`変更のない${section.end - section.start}行を${currentView.expanded.has(section.start) ? '折りたたむ' : '表示'}`}</button>
        <div id={`${sectionId}-${section.start}`}>{currentView.expanded.has(section.start) && lines.slice(section.start, section.end).map((line, offset) => <DiffRow key={section.start + offset} line={line} />)}</div>
      </div> : <div key={section.start}>{lines.slice(section.start, section.end).map((line, offset) => <DiffRow key={section.start + offset} line={line} />)}</div>)}
    </div>
  </Modal>
}
