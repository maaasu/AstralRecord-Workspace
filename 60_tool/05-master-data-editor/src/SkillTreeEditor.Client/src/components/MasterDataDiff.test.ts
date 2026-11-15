import { describe, expect, it } from 'vitest'
import { masterLineDiff, type DiffLine } from '../data/masterLineDiff'

function assertReconstruction(before: string, after: string, lines: DiffLine[]) {
  const left = lines.filter(line => line.kind !== 'added')
  const right = lines.filter(line => line.kind !== 'removed')
  expect(left.map(line => line.text)).toEqual(before.replaceAll('\r\n', '\n').split('\n'))
  expect(right.map(line => line.text)).toEqual(after.replaceAll('\r\n', '\n').split('\n'))
  expect(left.every((line, index) => line.before === index + 1)).toBe(true)
  expect(right.every((line, index) => line.after === index + 1)).toBe(true)
  expect(lines.every(line => (line.kind !== 'added' || line.before === undefined) && (line.kind !== 'removed' || line.after === undefined))).toBe(true)
}

function minimumEdits(left: string[], right: string[]) {
  const lengths = new Uint32Array(right.length + 1)
  for (const value of left) {
    let diagonal = 0
    for (let j = 1; j <= right.length; j++) {
      const previous = lengths[j]
      lengths[j] = value === right[j - 1] ? diagonal + 1 : Math.max(lengths[j], lengths[j - 1])
      diagonal = previous
    }
  }
  return left.length + right.length - 2 * lengths[right.length]
}

describe('master raw diff', () => {
  it('normalizes line endings and preserves unchanged comments', () => {
    expect(masterLineDiff('# retained\r\nname: before\r\n', '# retained\nname: after\n').lines).toEqual([
      { kind: 'same', text: '# retained', before: 1, after: 1 },
      { kind: 'removed', text: 'name: before', before: 2 },
      { kind: 'added', text: 'name: after', after: 2 },
      { kind: 'same', text: '', before: 3, after: 3 },
    ])
  })

  it('bounds large-file diff memory while keeping a common prefix and suffix', () => {
    const before = Array.from({ length: 1100 }, (_, index) => `value ${index}`).join('\n')
    const after = before.replace('value 500', 'changed 500')
    const { lines } = masterLineDiff(before, after)
    expect(lines.filter((line) => line.kind === 'removed').map((line) => line.text)).toEqual(['value 500'])
    expect(lines.filter((line) => line.kind === 'added').map((line) => line.text)).toEqual(['changed 500'])
  })

  it('finds separate edits beyond the quadratic limit without treating their intervening lines as changed', () => {
    const before = Array.from({ length: 5000 }, (_, index) => `value ${index}`).join('\n')
    const after = before.replace('value 100\n', 'changed 100\n').replace('value 4900\n', 'changed 4900\n')
    const { lines, simplified } = masterLineDiff(before, after)
    expect(simplified).toBe(false)
    expect(lines.filter(line => line.kind === 'removed')).toEqual([{ kind: 'removed', text: 'value 100', before: 101 }, { kind: 'removed', text: 'value 4900', before: 4901 }])
    expect(lines.filter(line => line.kind === 'added')).toEqual([{ kind: 'added', text: 'changed 100', after: 101 }, { kind: 'added', text: 'changed 4900', after: 4901 }])
    assertReconstruction(before, after, lines)
  })

  it('keeps insertions, deletions, moves, repeated YAML lines, blanks and comments in order', () => {
    const left = ['# original', ...Array.from({ length: 1400 }, (_, index) => index % 4 === 0 ? `  id: ${index}` : ['  enabled: true', '', '# retained'][index % 3]), '# tail']
    const right = [...left]
    right[0] = '# edited'
    right.splice(100, 0, '  name: 新しい名前', '')
    right.splice(900, 3)
    right.splice(1200, 0, ...right.splice(200, 6))
    right[right.length - 1] = '# changed tail'
    const before = left.join('\r\n'); const after = right.join('\n')
    const { lines, simplified } = masterLineDiff(before, after)
    expect(simplified).toBe(false)
    assertReconstruction(before, after, lines)
    expect(lines.filter(line => line.kind !== 'same')).toHaveLength(minimumEdits(left, right))
  })

  it('matches the minimum edit count and both texts for deterministic sparse edits on repetitive large inputs', () => {
    let seed = 1729
    const random = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed }
    for (let sample = 0; sample < 12; sample++) {
      const left = ['left boundary', ...Array.from({ length: 1100 }, () => `value: ${random() % 7}`), 'left tail']
      const right = [...left]
      right[0] = 'right boundary'; right[right.length - 1] = 'right tail'
      for (let edit = 0; edit < 20; edit++) {
        const index = 1 + random() % (right.length - 2)
        if (edit % 3 === 0) right.splice(index, 1)
        else if (edit % 3 === 1) right.splice(index, 0, `inserted: ${edit}`)
        else right[index] = `changed: ${edit}`
      }
      const before = left.join('\n'); const after = right.join('\n')
      const { lines, simplified } = masterLineDiff(before, after)
      expect(simplified).toBe(false)
      assertReconstruction(before, after, lines)
      expect(lines.filter(line => line.kind !== 'same')).toHaveLength(minimumEdits(left, right))
    }
  })

  it('is exact at the sparse edit limit and marks the bounded fallback above it', () => {
    const left = Array.from({ length: 1400 }, (_, index) => `line ${index}`)
    for (const changed of [128, 129]) {
      const right = [...left]
      for (let index = 0; index < changed; index++) {
        const position = index % 2 === 0 ? index / 2 : left.length - 1 - Math.floor(index / 2)
        right[position] = `replacement ${index}`
      }
      const { lines, simplified } = masterLineDiff(left.join('\n'), right.join('\n'))
      expect(simplified).toBe(changed === 129)
      if (!simplified) expect(lines.filter(line => line.kind !== 'same')).toHaveLength(256)
      assertReconstruction(left.join('\n'), right.join('\n'), lines)
    }
  })

  it('preserves common boundaries and all text when a large interval needs simplified comparison', () => {
    const before = ['# header', ...Array.from({ length: 1500 }, (_, index) => `before ${index}`), '# footer', ''].join('\r\n')
    const after = ['# header', ...Array.from({ length: 1500 }, (_, index) => `after ${index}`), '# footer', ''].join('\n')
    const { lines, simplified } = masterLineDiff(before, after)
    expect(simplified).toBe(true)
    expect(lines.filter(line => line.kind === 'same').map(line => line.text)).toEqual(['# header', '# footer', ''])
    assertReconstruction(before, after, lines)
  })

  it('handles long identical and one-sided content without unnecessary simplified comparison', () => {
    const text = Array.from({ length: 20_000 }, (_, index) => `line ${index}`).join('\n')
    for (const [before, after] of [[text, text], ['', text], [text, ''], ['header\nfooter', `header\n${text}\nfooter`]]) {
      const result = masterLineDiff(before, after)
      expect(result.simplified).toBe(false)
      assertReconstruction(before, after, result.lines)
    }
  })
})
