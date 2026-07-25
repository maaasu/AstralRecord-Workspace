import { describe, expect, it } from 'vitest'
import { minecraftIconName, minecraftIconUrl, stripMinecraftFormatting } from './minecraft'

describe('minecraft utilities', () => {
  it('builds an encoded icon proxy URL for string icons', () => {
    expect(minecraftIconName(' NETHER_STAR ')).toBe('NETHER_STAR')
    expect(minecraftIconUrl('NETHER_STAR', 3)).toBe('/api/minecraft-icons/NETHER_STAR?revision=3')
  })

  it('does not request an icon for non-string schema values', () => {
    expect(minecraftIconUrl({ material: 'STONE' })).toBeNull()
  })

  it('removes legacy Minecraft formatting from editor labels', () => {
    expect(stripMinecraftFormatting('&d&l旅立ちの§b記録')).toBe('旅立ちの記録')
  })
})
