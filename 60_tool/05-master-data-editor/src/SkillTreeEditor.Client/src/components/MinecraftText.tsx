import type { CSSProperties } from 'react'
import { parseMinecraftText } from '../utils/minecraft'
import './minecraftText.css'

export function MinecraftText({ value }: { value: string }) {
  return <span className="minecraft-text">{parseMinecraftText(value).map((segment, index) => {
    const decoration = [segment.underline && 'underline', segment.strikethrough && 'line-through'].filter(Boolean).join(' ')
    const rgb = segment.color?.slice(1).match(/../g)?.map((part) => parseInt(part, 16))
    const dark = rgb && (rgb[0] * .299 + rgb[1] * .587 + rgb[2] * .114) < 100
    const style: CSSProperties = {
      color: segment.color,
      fontWeight: segment.bold ? 700 : undefined,
      fontStyle: segment.italic ? 'italic' : undefined,
      textDecoration: decoration || undefined,
    }
    return <span key={index} className={dark ? 'minecraft-dark' : undefined} style={style} aria-label={segment.obfuscated ? segment.text : undefined}>
      {segment.obfuscated ? <span aria-hidden="true">{Array.from(segment.text, (character) => /\s/.test(character) ? character : '▓').join('')}</span> : segment.text}
    </span>
  })}</span>
}
