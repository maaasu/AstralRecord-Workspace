export interface MapLabelBox { x: number; z: number; width: number }

const rowHeight = 22

// Screen-space label tops less than 22px apart can only occupy neighboring rows.
// Create an index per scene and add only labels that were actually drawn.
export function createMapLabelIndex() {
  const rows = new Map<number, MapLabelBox[]>()
  return {
    overlaps(candidate: MapLabelBox) {
      const row = Math.floor(candidate.z / rowHeight)
      for (let offset = -1; offset <= 1; offset++) {
        for (const label of rows.get(row + offset) ?? []) {
          if (candidate.x < label.x + label.width + 4 && candidate.x + candidate.width + 4 > label.x && Math.abs(candidate.z - label.z) < rowHeight) return true
        }
      }
      return false
    },
    add(label: MapLabelBox) {
      const row = Math.floor(label.z / rowHeight)
      const labels = rows.get(row)
      if (labels) labels.push(label)
      else rows.set(row, [label])
    },
  }
}
