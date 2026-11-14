export interface DesignField { path: string; pointer: string; value: number | string }
export interface DesignLocation { path: string; pointer: string }
export interface DesignClass { id: string; name: string; path: string; prerequisiteClassIds: string[] }
export interface DesignEquipment { slot: string; itemId: string; pointer: string; enhanceLevel: number }
export interface DesignCheckpoint { level: number; classLevel: number; pointer: string; equipment: DesignEquipment[] }
export interface DesignPreset { id: string; name: string; path: string; classId: string; buildId: string; checkpoints: DesignCheckpoint[] }
export interface DesignItem {
  id: string; name: string; path: string; category: string | null; rarity: string | null
  icon: string | null; customModelData: number | null; iconTexture: string | null
  slot: string | null; handType: string | null; requiredLevel: number; requiredClasses: string[]
  compatibleSlots: string[]; maxEnhanceLevel: number
}
export interface DesignIngredient extends DesignLocation { itemId: string; itemPointer: string; quantity: DesignField[] }
export interface DesignContext extends DesignLocation { kind: string; id: string; name: string }
export interface DesignSource extends DesignLocation {
  id: string; kind: string; name: string; itemId: string; itemPath: string; itemPointer: string
  quantity: DesignField[]; rates: DesignField[]; costs: DesignField[]; ingredients: DesignIngredient[]
  ingredientContainer: DesignLocation | null; context: DesignContext[]; level: number | null; mode: string | null
  availability: 'configured' | 'definitionOnly'; warnings: string[]
}
export interface DesignUsage extends DesignLocation {
  itemId: string; kind: string; name: string; outputItemId: string | null; quantity: DesignField[]
}
export interface DesignAddTarget extends DesignLocation {
  id: string; kind: 'mob' | 'gathering' | 'dungeon'; name: string
  icon?: string | null; customModelData?: number | null; iconTexture?: string | null
  level?: number | null
}
export interface ProgressionDesignCatalog {
  revisions?: Record<string, string>
  classes: DesignClass[]; presets: DesignPreset[]; items: DesignItem[]; sources: DesignSource[]
  usages: DesignUsage[]; addTargets: DesignAddTarget[]
}
