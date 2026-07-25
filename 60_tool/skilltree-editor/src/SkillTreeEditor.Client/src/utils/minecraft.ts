import type { JsonValue } from '../types/editor'

export function minecraftIconName(icon: JsonValue): string {
  return typeof icon === 'string' ? icon.trim() : ''
}

export function minecraftIconUrl(icon: JsonValue, revision = 0): string | null {
  const name = minecraftIconName(icon)
  if (!name) return null
  return `/api/minecraft-icons/${encodeURIComponent(name)}?revision=${revision}`
}

export function stripMinecraftFormatting(value: string): string {
  return value
    .replace(/(?:§|&)#[0-9a-f]{6}/gi, '')
    .replace(/(?:§|&)[0-9a-fk-orx]/gi, '')
}
