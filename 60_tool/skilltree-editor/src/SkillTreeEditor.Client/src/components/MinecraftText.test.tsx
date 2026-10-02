import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { parseMinecraftText, stripMinecraftFormatting } from '../utils/minecraft'
import { MinecraftText } from './MinecraftText'

describe('Minecraft text', () => {
  it.each(Object.entries({ '0': '#000000', '1': '#0000aa', '2': '#00aa00', '3': '#00aaaa', '4': '#aa0000', '5': '#aa00aa', '6': '#ffaa00', '7': '#aaaaaa', '8': '#555555', '9': '#5555ff', a: '#55ff55', b: '#55ffff', c: '#ff5555', d: '#ff55ff', e: '#ffff55', f: '#ffffff' }))('renders legacy color %s using its actual color', (code, color) => {
    render(<MinecraftText value={`&${code.toUpperCase()}日本語 / Français 🗡`} />)
    expect(screen.getByText('日本語 / Français 🗡')).toHaveStyle({ color })
  })

  it('switches colors, clears decorations on color changes, and resets to inherited text', () => {
    render(<MinecraftText value="&6&l太字§o斜体&n下線&m取消&b水色&r通常" />)
    expect(screen.getByText('太字')).toHaveStyle({ color: '#ffaa00', fontWeight: '700' })
    expect(screen.getByText('斜体')).toHaveStyle({ fontStyle: 'italic', fontWeight: '700' })
    expect(screen.getByText('下線')).toHaveStyle({ textDecoration: 'underline' })
    expect(screen.getByText('取消')).toHaveStyle({ textDecoration: 'underline line-through' })
    expect(screen.getByText('水色')).toHaveStyle({ color: '#55ffff' })
    expect(screen.getByText('水色').style.fontWeight).toBe('')
    expect(screen.getByText('通常').getAttribute('style')).toBeNull()
  })

  it.each(['&#12AbEF', '§#12AbEF', '&x&1&2&A&b&E&F', '§x§1§2§A§b§E§F', '&x§1&2§A&b§E&F'])('accepts hex notation %s', (code) => {
    expect(parseMinecraftText(`${code}色`)).toEqual([{ text: '色', color: '#12abef' }])
  })

  it.each(['不正 &z / §p / &', '&#12345?', '§#GGFFFF', '&x&1&2短い', '§x§1§2§3§4§5'])('preserves malformed codes in %s', (text) => {
    expect(stripMinecraftFormatting(text)).toBe(text)
    render(<MinecraftText value={text} />)
    expect(screen.getByText(text)).toBeInTheDocument()
  })

  it('keeps HTML-like text and entities literal without creating elements', () => {
    const text = '<img src=x onerror=alert(1)> &amp; <script>日本語</script>'
    const { container } = render(<MinecraftText value={`&a${text}`} />)
    expect(container.textContent).toBe(text)
    expect(container.querySelector('img, script')).toBeNull()
    expect(screen.getByText(text)).toHaveStyle({ color: '#55ff55' })
  })

  it('preserves plain multilingual text, spacing and newlines', () => {
    const text = '日本語  Français 🗡\n  English'
    expect(parseMinecraftText(text)).toEqual([{ text }])
    const { container } = render(<MinecraftText value={text} />)
    expect(container.textContent).toBe(text)
    expect(stripMinecraftFormatting('&r&l')).toBe('')
  })

  it('outlines dark colors and exposes obfuscated text to assistive technology', () => {
    render(<MinecraftText value="&0黒&k秘密&r公開" />)
    expect(screen.getByText('黒')).toHaveClass('minecraft-dark')
    expect(screen.getByLabelText('秘密')).toHaveTextContent('▓▓')
    expect(screen.getByText('▓▓')).toHaveAttribute('aria-hidden', 'true')
    expect(screen.getByText('公開')).not.toHaveAttribute('aria-label')
  })
})
