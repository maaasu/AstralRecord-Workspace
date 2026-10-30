export interface DiffLine { kind: 'same' | 'added' | 'removed'; text: string; before?: number; after?: number }
export interface MasterLineDiff { lines: DiffLine[]; simplified: boolean }
interface Range { leftStart: number; leftEnd: number; rightStart: number; rightEnd: number }

const maxMatrixCells = 1_000_000
const maxEditDistance = 256
const maxSearchWork = 2_000_000

function smallDiff(left: string[], right: string[], range: Range): DiffLine[] {
  const { leftStart, leftEnd, rightStart, rightEnd } = range
  const n = leftEnd - leftStart; const m = rightEnd - rightStart
  const width = m + 1
  const lengths = new Uint32Array((n + 1) * width)
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) {
    lengths[i * width + j] = left[leftStart + i] === right[rightStart + j]
      ? lengths[(i + 1) * width + j + 1] + 1
      : Math.max(lengths[(i + 1) * width + j], lengths[i * width + j + 1])
  }
  const result: DiffLine[] = []
  let i = 0; let j = 0
  while (i < n || j < m) {
    if (i < n && j < m && left[leftStart + i] === right[rightStart + j]) {
      result.push({ kind: 'same', text: left[leftStart + i], before: leftStart + ++i, after: rightStart + ++j })
    } else if (i < n && (j === m || lengths[(i + 1) * width + j] >= lengths[i * width + j + 1])) {
      result.push({ kind: 'removed', text: left[leftStart + i], before: leftStart + ++i })
    } else result.push({ kind: 'added', text: right[rightStart + j], after: rightStart + ++j })
  }
  return result
}

// Myers' frontier follows matching runs, so sparse edits in long, repetitive YAML
// do not need a file-sized quadratic matrix. Both search work and trace are capped.
function sparseDiff(left: string[], right: string[], range: Range): DiffLine[] | null {
  const { leftStart, leftEnd, rightStart, rightEnd } = range
  const n = leftEnd - leftStart; const m = rightEnd - rightStart
  if (Math.abs(n - m) > maxEditDistance) return null
  const offset = maxEditDistance + 1
  const frontier = new Int32Array(maxEditDistance * 2 + 3).fill(-1)
  frontier[offset + 1] = 0
  const trace: Int32Array[] = []
  let work = 0
  for (let distance = 0; distance <= maxEditDistance; distance++) {
    for (let diagonal = -distance; diagonal <= distance; diagonal += 2) {
      if (++work > maxSearchWork) return null
      const slot = offset + diagonal
      let x = diagonal === -distance || (diagonal !== distance && frontier[slot - 1] < frontier[slot + 1])
        ? frontier[slot + 1] : frontier[slot - 1] + 1
      let y = x - diagonal
      while (x < n && y < m) {
        if (++work > maxSearchWork) return null
        if (left[leftStart + x] !== right[rightStart + y]) break
        x++; y++
      }
      frontier[slot] = x
      if (x === n && y === m) {
        const reversed: DiffLine[] = []
        for (let step = distance; step > 0; step--) {
          const previous = trace[step - 1]
          const k = x - y
          const previousK = k === -step || (k !== step && previous[offset + k - 1] < previous[offset + k + 1]) ? k + 1 : k - 1
          const previousX = previous[offset + previousK]
          const previousY = previousX - previousK
          while (x > previousX && y > previousY) {
            x--; y--
            reversed.push({ kind: 'same', text: left[leftStart + x], before: leftStart + x + 1, after: rightStart + y + 1 })
          }
          if (x === previousX) {
            y--
            reversed.push({ kind: 'added', text: right[rightStart + y], after: rightStart + y + 1 })
          } else {
            x--
            reversed.push({ kind: 'removed', text: left[leftStart + x], before: leftStart + x + 1 })
          }
        }
        while (x > 0 && y > 0) {
          x--; y--
          reversed.push({ kind: 'same', text: left[leftStart + x], before: leftStart + x + 1, after: rightStart + y + 1 })
        }
        return reversed.reverse()
      }
    }
    trace.push(frontier.slice())
  }
  return null
}

export function masterLineDiff(before: string, after: string): MasterLineDiff {
  const left = before.replaceAll('\r\n', '\n').split('\n')
  const right = after.replaceAll('\r\n', '\n').split('\n')
  const lines: DiffLine[] = []
  let prefix = 0
  while (prefix < left.length && prefix < right.length && left[prefix] === right[prefix]) {
    lines.push({ kind: 'same', text: left[prefix], before: prefix + 1, after: prefix + 1 })
    prefix++
  }
  let leftEnd = left.length; let rightEnd = right.length
  while (leftEnd > prefix && rightEnd > prefix && left[leftEnd - 1] === right[rightEnd - 1]) { leftEnd--; rightEnd-- }
  const range = { leftStart: prefix, leftEnd, rightStart: prefix, rightEnd }
  let simplified = false
  if (leftEnd > prefix && rightEnd > prefix) {
    const middle = (leftEnd - prefix + 1) * (rightEnd - prefix + 1) <= maxMatrixCells
      ? smallDiff(left, right, range) : sparseDiff(left, right, range)
    if (middle) for (const line of middle) lines.push(line)
    else simplified = true
  }
  if (simplified || leftEnd === prefix || rightEnd === prefix) {
    for (let i = prefix; i < leftEnd; i++) lines.push({ kind: 'removed', text: left[i], before: i + 1 })
    for (let j = prefix; j < rightEnd; j++) lines.push({ kind: 'added', text: right[j], after: j + 1 })
  }
  for (let i = leftEnd, j = rightEnd; i < left.length; i++, j++) lines.push({ kind: 'same', text: left[i], before: i + 1, after: j + 1 })
  return { lines, simplified }
}
