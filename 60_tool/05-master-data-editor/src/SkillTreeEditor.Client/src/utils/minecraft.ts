import type { JsonValue } from '../types/editor'

export function minecraftIconName(icon: JsonValue): string {
  return typeof icon === 'string' ? icon.trim() : ''
}

export function minecraftIconUrl(icon: JsonValue, revision = 0): string | null {
  const name = minecraftIconName(icon)
  if (!name) return null
  return `/api/minecraft-icons/${encodeURIComponent(name)}?revision=${revision}`
}

export function minecraftItemTextureUrl(icon: JsonValue, modelData: JsonValue | undefined, revision = 0): string | null {
  const name = minecraftIconName(icon).toLowerCase().replace(/^minecraft:/, '')
  if (!/^[a-z0-9_]+$/.test(name) || typeof modelData !== 'number' || !Number.isInteger(modelData)
    || modelData < -2_147_483_648 || modelData > 2_147_483_647) return null
  return `/api/minecraft-item-textures/${name}/${modelData}?revision=${revision}`
}

export function minecraftMobHeadUrl(model: JsonValue | undefined, revision = 0): string | null {
  const role = typeof model === 'string' ? model.trim() : ''
  return /^mob_head_[a-z0-9_]+$/.test(role) ? `/api/minecraft-mob-heads/${role}?revision=${revision}` : null
}

export function minecraftHeadTextureUrl(icon: JsonValue, texture: JsonValue | undefined): string | null {
  if (minecraftIconName(icon).toLowerCase().replace(/^minecraft:/, '') !== 'player_head') return null
  const hash = minecraftSkinTextureHash(texture)
  return hash ? `https://textures.minecraft.net/texture/${hash}` : null
}

export function minecraftNpcSkinUrl(texture: JsonValue | undefined, revision = 0): string | null {
  const hash = minecraftSkinTextureHash(texture)
  return hash ? `/api/minecraft-npc-skins/${hash.toLowerCase()}${revision ? `?revision=${revision}` : ''}` : null
}

function minecraftSkinTextureHash(texture: JsonValue | undefined): string | null {
  if (typeof texture !== 'string' || !texture || texture.length > 16_384) return null
  try {
    const url: unknown = JSON.parse(atob(texture)).textures?.SKIN?.url
    const match = typeof url === 'string' && /^https?:\/\/textures\.minecraft\.net\/texture\/([0-9a-f]{1,64})$/i.exec(url)
    return match ? match[1] : null
  } catch {
    return null
  }
}

export function stripMinecraftFormatting(value: string): string {
  return parseMinecraftText(value).map((segment) => segment.text).join('')
}

const minecraftColors: Record<string, string> = {
  '0': '#000000', '1': '#0000aa', '2': '#00aa00', '3': '#00aaaa',
  '4': '#aa0000', '5': '#aa00aa', '6': '#ffaa00', '7': '#aaaaaa',
  '8': '#555555', '9': '#5555ff', a: '#55ff55', b: '#55ffff',
  c: '#ff5555', d: '#ff55ff', e: '#ffff55', f: '#ffffff',
}

export interface MinecraftTextSegment {
  text: string
  color?: string
  bold?: boolean
  italic?: boolean
  underline?: boolean
  strikethrough?: boolean
  obfuscated?: boolean
}

/** Parse only known Minecraft codes; all other input remains literal text. */
export function parseMinecraftText(value: string): MinecraftTextSegment[] {
  const segments: MinecraftTextSegment[] = []
  let style: Omit<MinecraftTextSegment, 'text'> = {}
  let text = ''
  const flush = () => {
    if (text) segments.push({ text, ...style })
    text = ''
  }
  for (let index = 0; index < value.length;) {
    if (value[index] !== '&' && value[index] !== '§') { text += value[index++]; continue }
    const code = value[index + 1]?.toLowerCase()
    const rest = value.slice(index)
    const entity = rest.match(/^&(?:[a-z][a-z0-9]{1,31}|#\d+|#x[0-9a-f]+);/i)
    if (entity) { text += entity[0]; index += entity[0].length; continue }
    const hex = rest.match(/^[&§]#([0-9a-f]{6})/i)
    const expanded = rest.match(/^[&§]x((?:[&§][0-9a-f]){6})/i)
    if (hex || expanded) {
      flush()
      style = { color: `#${hex ? hex[1] : expanded![1].replace(/[&§]/g, '')}`.toLowerCase() }
      index += (hex ?? expanded)![0].length
    } else if (code === 'x') {
      // An incomplete expanded hex sequence must not turn its digits into colors.
      const malformed = rest.match(/^[&§]x(?:[&§][0-9a-f])*/i)![0]
      text += malformed
      index += malformed.length
    } else if (code && Object.hasOwn(minecraftColors, code)) {
      flush(); style = { color: minecraftColors[code] }; index += 2
    } else if (code === 'r') {
      flush(); style = {}; index += 2
    } else if (code && 'klmno'.includes(code)) {
      flush()
      const decoration = { k: 'obfuscated', l: 'bold', m: 'strikethrough', n: 'underline', o: 'italic' } as const
      style = { ...style, [decoration[code as keyof typeof decoration]]: true }
      index += 2
    } else {
      text += value[index++]
    }
  }
  flush()
  return segments
}
