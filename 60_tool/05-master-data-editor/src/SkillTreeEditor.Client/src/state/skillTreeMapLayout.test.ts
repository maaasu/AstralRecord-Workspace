import { describe, expect, it } from 'vitest'
import { BLOCK_SCALE, clippedEdgePath, measureLabelZoom, measureNodeSpacing, nodeDiameter } from './skillTreeMapLayout'

describe('スキルツリーの地図座標', () => {
  it('正確な最近傍距離を測り、種類の倍率を距離上限の範囲で適用する', () => {
    const spacing = measureNodeSpacing([
      { nodeId: 'a', x: 0, z: 0 },
      { nodeId: 'b', x: 1, z: 0 },
      { nodeId: 'c', x: 10, z: 0 },
    ])
    expect(spacing.get('a')?.nearest).toBe(BLOCK_SCALE)
    expect(spacing.get('c')?.nearest).toBe(9 * BLOCK_SCALE)
    expect(nodeDiameter(56, spacing.get('a')!, 1, false)).toBeCloseTo(BLOCK_SCALE * .78)
    expect(nodeDiameter(56, spacing.get('a')!, 1.35, false)).toBeCloseTo(BLOCK_SCALE * .9)
    expect(nodeDiameter(56, spacing.get('c')!, 1.2, true)).toBeCloseTo(56 * 1.2)
    expect(nodeDiameter(56, spacing.get('c')!, 1.35, true)).toBeCloseTo(56 * 1.35)
  })

  it('14px未満の通常PPには基本の間隔比率を使う', () => {
    const spacing = measureNodeSpacing([{ nodeId: 'a', x: 0, z: 0 }, { nodeId: 'b', x: 0.2, z: 0 }]).get('a')!
    expect(nodeDiameter(140, spacing, 1, false)).toBeCloseTo(5.616)
    expect(nodeDiameter(140, spacing, 1.2, false)).toBeCloseTo(6.48)
  })

  it('同じX/Zの配置を1地点として測り、別地点がなければ既定の大きさに収める', () => {
    const stacked = [{ nodeId: 'a', x: 0, z: 0 }, { nodeId: 'b', x: 0, z: 0 }]
    const measurements = measureNodeSpacing([...stacked, { nodeId: 'c', x: 1, z: 0 }])
    expect(measurements.get('a')).toBe(measurements.get('b'))
    expect(measurements.get('a')).toMatchObject({ nearest: BLOCK_SCALE, coincidentIds: ['a', 'b'] })
    expect(measurements.get('c')?.coincidentIds).toEqual(['c'])
    expect(nodeDiameter(140, measureNodeSpacing(stacked).get('a')!, 1, false)).toBe(56)
    expect(nodeDiameter(32, measureNodeSpacing(stacked).get('a')!, 1, false)).toBe(32)
  })

  it('中心が90px以上離れていても、下の枠にラベルが重なる倍率では表示しない', () => {
    const points = [{ nodeId: 'a', x: 0, z: 0 }, { nodeId: 'b', x: 0, z: 4 }]
    const spacing = measureNodeSpacing(points)
    const frames = points.map(({ nodeId, x, z }) => ({
      id: nodeId, x: x * BLOCK_SCALE, y: z * BLOCK_SCALE, diameter: nodeDiameter(140, spacing.get(nodeId)!, 1, false),
    }))
    const thresholds = measureLabelZoom(frames, spacing, 0.45, 2.5)
    expect(thresholds.get('a')).toBeCloseTo(35 / (144 - 144 * 0.78))
    expect(thresholds.get('a')).toBeGreaterThan(1)
    expect(thresholds.get('b')).toBeCloseTo(90 / 144)
  })

  it('密集・斜め・疎な配置のすべての組で枠の半径の和が中心間の距離を超えない', () => {
    const nodes = Array.from({ length: 96 }, (_, index) => ({
      nodeId: String(index),
      x: (index % 12) ** 2 * .1 + (index % 3) * .01,
      z: Math.floor(index / 12) ** 2 * .1 + (index % 4) * .02,
      square: index % 2 === 0,
      scale: [1, 1.2, 1.35][index % 3],
    }))
    const spacing = measureNodeSpacing(nodes)
    const distances = nodes.map((node, index) => nodes.filter((_, other) => other !== index).map(other => (
      Math.hypot(other.x - node.x, other.z - node.z) * BLOCK_SCALE
    )))
    for (const [index, node] of nodes.entries()) {
      expect(spacing.get(node.nodeId)?.nearest).toBeCloseTo(Math.min(...distances[index]))
    }
    for (const maximum of [32, 56, 140]) {
      const sizes = nodes.map(node => nodeDiameter(maximum, spacing.get(node.nodeId)!, node.scale, node.square))
      expect(Math.min(...sizes)).toBeLessThan(14)
      for (const [i, a] of nodes.entries()) {
        for (let j = i + 1; j < nodes.length; j++) {
          const b = nodes[j]
          const dx = Math.abs(a.x - b.x), dz = Math.abs(a.z - b.z)
          const distance = Math.hypot(dx, dz)
          const squareProjection = (dx + dz) / distance
          const extent = (sizes[i] * (a.square ? squareProjection : 1) + sizes[j] * (b.square ? squareProjection : 1)) / 2
          expect(extent).toBeLessThanOrEqual(distance * BLOCK_SCALE)
        }
      }
    }
  })

  it('接続線を丸と角丸四角の枠で切り、両端を結ぶ方向を維持する', () => {
    const origin = { x: 0, y: 0, diameter: 20, square: false }
    expect(clippedEdgePath(origin, { x: 100, y: 0, diameter: 24, square: true })).toBe('M 10,0 L 88,0')
    const diagonal = clippedEdgePath(origin, { x: 40, y: 40, diameter: 20, square: true })
    const [sx, sy, ex, ey] = diagonal.match(/[\d.]+/g)!.map(Number)
    expect(sx).toBeCloseTo(sy)
    expect(ex).toBeCloseTo(ey)
    expect(Math.hypot(sx, sy)).toBeCloseTo(10)
    expect(ex).toBeCloseTo(40 - (6.4 + 3.6 / Math.SQRT2))
    expect(clippedEdgePath(origin, origin)).toBe('')
  })
})
