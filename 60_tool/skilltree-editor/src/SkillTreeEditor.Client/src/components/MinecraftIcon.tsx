import { useEffect, useState } from 'react'
import type { JsonValue } from '../types/editor'
import { minecraftIconName, minecraftIconUrl } from '../utils/minecraft'

interface MinecraftIconProps {
  icon: JsonValue
  revision?: number
  className?: string
}

export function MinecraftIcon({ icon, revision = 0, className = '' }: MinecraftIconProps) {
  const [failed, setFailed] = useState(false)
  const name = minecraftIconName(icon)
  const source = minecraftIconUrl(icon, revision)

  useEffect(() => setFailed(false), [name, revision])

  return (
    <span className={`minecraft-icon ${className} ${failed || !source ? 'missing' : ''}`} title={name || 'アイコン未設定'}>
      {source && !failed
        ? <img src={source} alt="" loading="lazy" decoding="async" draggable={false} onError={() => setFailed(true)} />
        : <span aria-label="アイコンを取得できません">?</span>}
    </span>
  )
}
