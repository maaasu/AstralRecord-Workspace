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

export function MasterDataDiff({ before, after, onClose }: { before: string; after: string; onClose: () => void }) {
  const lines = masterLineDiff(before, after)
  return <div className="modal-backdrop" role="presentation"><section className="modal master-diff-dialog" role="dialog" aria-modal="true" aria-label="保存前の差分">
    <header className="modal-header"><div><h2>保存前の差分</h2><p className="master-muted">保存済み → 現在の原稿。{lines.filter((line) => line.kind === 'added').length} 行追加 / {lines.filter((line) => line.kind === 'removed').length} 行削除</p></div><button className="button" onClick={onClose}>閉じる</button></header>
    <div className="master-diff-lines">{lines.map((line, index) => <div key={index} className={`master-diff-line ${line.kind}`}><span>{line.before ?? ''}</span><span>{line.after ?? ''}</span><b>{line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' '}</b><pre>{line.text || ' '}</pre></div>)}</div>
  </section></div>
}
