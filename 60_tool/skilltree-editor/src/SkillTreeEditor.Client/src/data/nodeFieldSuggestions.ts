import minecraftMaterials from './minecraft-materials.1.21.11.json'

export const MINECRAFT_MATERIAL_VERSION = '1.21.11'
export const minecraftMaterialSuggestions: readonly string[] = minecraftMaterials

export function buildNodeFieldSuggestions(tags: readonly string[]) {
  return {
    '/icon': minecraftMaterialSuggestions,
    '/tags/*': tags,
  } satisfies Readonly<Record<string, readonly string[]>>
}
