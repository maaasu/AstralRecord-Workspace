import { describe, expect, it } from 'vitest'
import { masterLineDiff } from './MasterDataDiff'

describe('master raw diff', () => {
  it('normalizes line endings and preserves unchanged comments', () => {
    expect(masterLineDiff('# retained\r\nname: before\r\n', '# retained\nname: after\n')).toEqual([
      { kind: 'same', text: '# retained', before: 1, after: 1 },
      { kind: 'removed', text: 'name: before', before: 2 },
      { kind: 'added', text: 'name: after', after: 2 },
      { kind: 'same', text: '', before: 3, after: 3 },
    ])
  })

  it('bounds large-file diff memory while keeping a common prefix and suffix', () => {
    const before = Array.from({ length: 1100 }, (_, index) => `value ${index}`).join('\n')
    const after = before.replace('value 500', 'changed 500')
    const lines = masterLineDiff(before, after)
    expect(lines.filter((line) => line.kind === 'removed').map((line) => line.text)).toEqual(['value 500'])
    expect(lines.filter((line) => line.kind === 'added').map((line) => line.text)).toEqual(['changed 500'])
  })
})
