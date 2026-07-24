import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(cleanup)

class ResizeObserverMock implements ResizeObserver {
  constructor(private readonly callback: ResizeObserverCallback) {}

  observe(target: Element) {
    this.callback([{
      target,
      contentRect: target.getBoundingClientRect(),
      borderBoxSize: [],
      contentBoxSize: [],
      devicePixelContentBoxSize: [],
    } as unknown as ResizeObserverEntry], this)
  }

  unobserve() {}
  disconnect() {}
}

Object.defineProperty(globalThis, 'ResizeObserver', {
  configurable: true,
  writable: true,
  value: ResizeObserverMock,
})

class DOMMatrixReadOnlyMock {
  readonly m22: number

  constructor(transform?: string) {
    const matrix = transform?.match(/^matrix\(([^)]+)\)$/)
    const values = matrix?.[1].split(',').map(Number)
    this.m22 = values?.[3] && Number.isFinite(values[3]) ? values[3] : 1
  }
}

Object.defineProperty(globalThis, 'DOMMatrixReadOnly', {
  configurable: true,
  writable: true,
  value: DOMMatrixReadOnlyMock,
})

Object.defineProperty(HTMLElement.prototype, 'getBoundingClientRect', {
  configurable: true,
  value: () => ({
    x: 0,
    y: 0,
    top: 0,
    left: 0,
    right: 800,
    bottom: 600,
    width: 800,
    height: 600,
    toJSON: () => ({}),
  }),
})

Object.defineProperties(HTMLElement.prototype, {
  offsetWidth: {
    configurable: true,
    get(this: HTMLElement) {
      return this.classList.contains('react-flow__node') ? 180 : 800
    },
  },
  offsetHeight: {
    configurable: true,
    get(this: HTMLElement) {
      return this.classList.contains('react-flow__node') ? 80 : 600
    },
  },
})
