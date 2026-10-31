const bucketSize = 64

// World-space index, reused across pan/zoom. The caller keeps the exact screen
// distance test and uses the original order to resolve equal-distance hits.
export function createMapPointIndex<T extends { x: number; z: number }>(items: readonly T[]) {
  const buckets = new Map<string, number[]>()
  let unsafe = false
  items.forEach((item, index) => {
    if (!Number.isFinite(item.x) || !Number.isFinite(item.z)) return
    const x = Math.floor(item.x / bucketSize), z = Math.floor(item.z / bucketSize)
    if (!Number.isSafeInteger(x) || !Number.isSafeInteger(z)) { unsafe = true; return }
    const key = `${x},${z}`, bucket = buckets.get(key)
    if (bucket) bucket.push(index)
    else buckets.set(key, [index])
  })
  return {
    forEachNear(x: number, z: number, radius: number, visit: (item: T, order: number) => void) {
      // Include a neighboring bucket to keep inverse-projection rounding from
      // dropping points at bucket boundaries. Exact distance remains authoritative.
      const minX = Math.floor((x - radius) / bucketSize) - 1, maxX = Math.floor((x + radius) / bucketSize) + 1
      const minZ = Math.floor((z - radius) / bucketSize) - 1, maxZ = Math.floor((z + radius) / bucketSize) + 1
      const count = (maxX - minX + 1) * (maxZ - minZ + 1)
      // Broad/invalid queries never walk an unbounded range of empty buckets.
      if (unsafe || radius < 0 || ![minX, maxX, minZ, maxZ].every(Number.isSafeInteger) || count >= buckets.size) {
        for (let order = items.length - 1; order >= 0; order--) visit(items[order], order)
        return
      }
      for (let bx = minX; bx <= maxX; bx++) for (let bz = minZ; bz <= maxZ; bz++) {
        const bucket = buckets.get(`${bx},${bz}`)
        if (bucket) for (let i = bucket.length - 1; i >= 0; i--) {
          const order = bucket[i]
          visit(items[order], order)
        }
      }
    },
  }
}
