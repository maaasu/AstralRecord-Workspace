import type { ClassMasterSummary, NodeMaster } from '../types/editor'

export interface SkillTreeSimulationContext {
  currentClassId: string
  playerLevel: number
}

export function isNodeVisibleInSimulation(
  node: NodeMaster,
  classes: ClassMasterSummary[],
  context: SkillTreeSimulationContext,
): boolean {
  const rawCondition = node.unlockCondition
  if (!rawCondition || typeof rawCondition !== 'object' || Array.isArray(rawCondition)) return true

  const requiredPlayerLevel = typeof rawCondition.playerLevel === 'number'
    ? rawCondition.playerLevel
    : 0
  if (Math.max(1, Math.floor(context.playerLevel)) < requiredPlayerLevel) return false

  const requiredClassId = typeof rawCondition.classId === 'string'
    ? rawCondition.classId.trim().toLowerCase()
    : ''
  return !requiredClassId || isClassOrAncestor(classes, context.currentClassId, requiredClassId)
}

export function isClassOrAncestor(
  classes: ClassMasterSummary[],
  currentClassId: string,
  requiredClassId: string,
): boolean {
  const required = requiredClassId.trim().toLowerCase()
  if (!required) return true

  const byId = new Map(classes.map((entry) => [entry.id.toLowerCase(), entry]))
  const queue = [currentClassId.trim().toLowerCase()]
  const visited = new Set<string>()
  while (queue.length) {
    const classId = queue.shift()!
    if (visited.has(classId)) continue
    visited.add(classId)
    if (classId === required) return true
    const master = byId.get(classId)
    if (master) queue.push(...master.parentClassIds.map((parent) => parent.toLowerCase()))
  }
  return false
}
