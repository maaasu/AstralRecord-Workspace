import { useState, type ReactNode } from 'react'
import type { JsonValue } from '../types/editor'
import { minecraftHeadTextureUrl, minecraftIconName, minecraftIconUrl, minecraftItemTextureUrl, minecraftMobHeadUrl, minecraftNpcSkinUrl } from '../utils/minecraft'

interface MinecraftIconProps {
  icon: JsonValue
  customModelData?: JsonValue
  iconTexture?: JsonValue
  iconModel?: JsonValue
  revision?: number
  className?: string
}

interface IconSource { url: string; head: boolean }

export function MinecraftIcon({ icon, customModelData, iconTexture, iconModel, revision = 0, className = '' }: MinecraftIconProps) {
  const name = minecraftIconName(icon)
  const sources: IconSource[] = []
  const custom = minecraftItemTextureUrl(icon, customModelData, revision)
  const head = minecraftHeadTextureUrl(icon, iconTexture)
  const mobHead = minecraftMobHeadUrl(iconModel, revision)
  const vanilla = minecraftIconUrl(icon, revision)
  if (custom) sources.push({ url: custom, head: false })
  if (head) sources.push({ url: head, head: true })
  if (mobHead) sources.push({ url: mobHead, head: false })
  if (vanilla) sources.push({ url: vanilla, head: false })
  return <IconPreview key={JSON.stringify(sources)} sources={sources} name={name} className={className} />
}

export function MinecraftSkinIcon({ texture, revision, fallback, className }: {
  texture?: string | null; revision?: number; fallback: ReactNode; className: string
}) {
  const url = minecraftNpcSkinUrl(texture, revision)
  if (!url) return fallback
  return <IconPreview key={url} sources={[{ url, head: true }]} className={className} fallback={fallback} />
}

function IconPreview({ sources, name, className, fallback }: {
  sources: IconSource[]; name?: string; className: string; fallback?: ReactNode
}) {
  const [index, setIndex] = useState(0)
  const source = sources[index]
  const onError = () => setIndex(index + 1)
  if (!source && fallback !== undefined) return fallback

  return (
    <span className={`minecraft-icon ${className} ${source ? '' : 'missing'}`} title={name === '' ? 'アイコン未設定' : name}>
      {source
        ? source.head
          ? <span className="minecraft-head-face" key={source.url}>
            <img src={source.url} alt="" loading="lazy" decoding="async" draggable={false} referrerPolicy="no-referrer" onError={onError} onLoad={(event) => {
              const { naturalWidth: width, naturalHeight: height } = event.currentTarget
              if (width < 64 || width > 1024 || width % 64 !== 0 || (height !== width && height !== width / 2)) onError()
            }} />
            <img src={source.url} alt="" loading="lazy" decoding="async" draggable={false} referrerPolicy="no-referrer"
              onError={fallback !== undefined ? onError : undefined} />
          </span>
          : <img key={source.url} src={source.url} alt="" loading="lazy" decoding="async" draggable={false} onError={onError} />
        : <span aria-label="アイコンを取得できません">?</span>}
    </span>
  )
}
