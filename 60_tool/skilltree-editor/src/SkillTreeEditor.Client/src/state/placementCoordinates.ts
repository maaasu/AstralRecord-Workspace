export function parsePlacementCoordinate(raw: string): number | null {
  if (!/^[+-]?(?:\d+(?:\.\d+)?|\.\d+)$/.test(raw)) return null
  const value = Number(raw)
  if (!Number.isFinite(value) || value < -2147483648 || value > 2147483647 || Math.abs(value * 10 - Math.round(value * 10)) >= 0.0000001) return null
  return Math.round(value * 10) / 10
}
