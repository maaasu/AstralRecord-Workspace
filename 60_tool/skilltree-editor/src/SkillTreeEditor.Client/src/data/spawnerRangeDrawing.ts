export interface MapCircle { x: number; z: number; radius: number; color: string }

// Keep circles whose centres are outside the viewport when their circumference can enter it.
// Include a stroke-width margin so clipping does not remove a visible edge.
export function circleIntersectsViewport(x: number, z: number, radius: number, width: number, height: number): boolean {
  const dx = x - Math.max(0, Math.min(width, x)), dz = z - Math.max(0, Math.min(height, z))
  return dx * dx + dz * dz <= (radius + 1) ** 2
}

export function drawSpawnerRanges(context: CanvasRenderingContext2D, circles: MapCircle[]): void {
  const groups = new Map<string, MapCircle[]>()
  for (const circle of circles) {
    const group = groups.get(circle.color)
    if (group) group.push(circle)
    else groups.set(circle.color, [circle])
  }
  context.lineWidth = 1
  for (const [color, group] of groups) {
    context.beginPath()
    for (const { x, z, radius } of group) {
      // A separate subpath prevents Canvas from connecting neighbouring circles.
      context.moveTo(x + radius, z)
      context.arc(x, z, radius, 0, Math.PI * 2)
    }
    context.strokeStyle = color
    context.stroke()
  }
}
