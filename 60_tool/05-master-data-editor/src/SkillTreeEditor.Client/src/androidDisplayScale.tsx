import { useLayoutEffect, useState, type CSSProperties } from 'react'
import './androidDisplayScale.css'

const displayScales = [60, 70, 80, 90, 100, 125] as const
const defaultScale = 80
const storageKey = 'astralrecord.android.display-scale'
type DisplayScale = (typeof displayScales)[number]

function parseScale(value: string | null): DisplayScale {
  return displayScales.find(scale => String(scale) === value) ?? defaultScale
}

function readScale(): DisplayScale {
  try {
    return parseScale(window.localStorage.getItem(storageKey))
  } catch {
    return defaultScale
  }
}

export function useAndroidDisplayScale() {
  const [scale, setScale] = useState(readScale)

  useLayoutEffect(() => {
    const viewport = document.querySelector<HTMLMetaElement>('meta[name="viewport"]')
    if (!viewport) return
    const original = viewport.content
    return () => { viewport.content = original }
  }, [])

  useLayoutEffect(() => {
    const viewport = document.querySelector<HTMLMetaElement>('meta[name="viewport"]')
    if (!viewport) return
    // Let WebView infer the layout width from the selected scale and the actual device viewport.
    const factor = scale / 100
    viewport.content = `initial-scale=${factor}, minimum-scale=${factor}, maximum-scale=${factor}`
  }, [scale])

  return {
    scale,
    onScaleChange(next: DisplayScale) {
      setScale(next)
      try {
        window.localStorage.setItem(storageKey, String(next))
      } catch {
        // The selected scale remains usable for the current session when storage is unavailable.
      }
    },
  }
}

export function AndroidDisplayScale({ scale, onScaleChange }: { scale: DisplayScale; onScaleChange: (scale: DisplayScale) => void }) {
  return <label className="android-display-scale" style={{ '--android-display-factor': scale / 100 } as CSSProperties}>
    <span aria-hidden="true">表示倍率</span>
    <select aria-label="表示倍率" value={scale} onChange={event => onScaleChange(parseScale(event.currentTarget.value))}>
      {displayScales.map(value => <option key={value} value={value}>{value}%</option>)}
    </select>
  </label>
}
