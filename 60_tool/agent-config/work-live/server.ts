import { readFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import type { ServerResponse } from 'node:http'
import { homedir } from 'node:os'
import { resolve } from 'node:path'
import process from 'node:process'
import { pathToFileURL } from 'node:url'
import manifest from './package.json' with { type: 'json' }
import { boardFor, detailFor, displayScope } from './src/board.ts'
import { iconAssets } from './src/icons.ts'
import { recordOf } from './src/model.ts'
import { createMonitor } from './src/monitor.ts'

const HOST = '127.0.0.1'
const DEFAULT_PORT = 5490
const HEALTH = { app: 'work-live', version: manifest.version }
const CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'"
const STATIC = new Map([
  ['/', { file: 'index.html', contentType: 'text/html' }],
  ['/app.js', { file: 'app.js', contentType: 'text/javascript' }],
  ['/app.css', { file: 'app.css', contentType: 'text/css' }],
])

function send(response: ServerResponse, status: number, contentType: string, body: string) {
  response.writeHead(status, { 'Content-Type': contentType + '; charset=utf-8' })
  response.end(body)
}

function json(response: ServerResponse, status: number, value: unknown) {
  send(response, status, 'application/json', JSON.stringify(value)
    .replace(/</gu, '\\u003c').replace(/>/gu, '\\u003e').replace(/&/gu, '\\u0026'))
}

export function createWorkLiveServer({ home = homedir(), now = Date.now } = {}) {
  const monitor = createMonitor(home)
  const contents = new Map<string, string>()
  let ready = Promise.resolve()
  let timer: ReturnType<typeof setInterval> | undefined
  function poll() {
    return monitor.poll(now()).catch(() => {
      console.error('作業ライブの記録を更新できませんでした。次の周期で再試行します。')
    })
  }

  const server = createServer(async (request, response) => {
    response.setHeader('Content-Security-Policy', CSP)
    response.setHeader('X-Content-Type-Options', 'nosniff')
    response.setHeader('Cache-Control', 'no-store')
    const address = server.address()
    const port = typeof address === 'object' && address ? address.port : undefined
    if (port === undefined || ![HOST + ':' + port, 'localhost:' + port].includes(request.headers.host ?? '')) {
      return json(response, 403, { error: 'このホストからは接続できません。' })
    }
    if (request.method !== 'GET') {
      response.setHeader('Allow', 'GET')
      return json(response, 405, { error: 'GET でアクセスしてください。' })
    }
    try {
      const target = request.url ?? ''
      if (!target.startsWith('/') || target.startsWith('//')) return json(response, 404, { error: 'ページが見つかりません。' })
      const url = new URL(target, 'http://' + HOST)
      if (url.pathname === '/api/health') {
        response.setHeader('X-Work-Live-Pid', String(process.pid))
        return json(response, 200, HEALTH)
      }
      if (url.pathname === '/api/board') {
        await ready
        return json(response, 200, boardFor(monitor.draft, monitor.sessions,
          url.searchParams.get('session'), displayScope(url.searchParams.get('scope')), now()))
      }
      if (url.pathname === '/api/detail') {
        const id = url.searchParams.get('id')
        if (!id) return json(response, 400, { error: '依頼 ID を指定してください。' })
        await ready
        const detail = detailFor(monitor.draft, id)
        return detail ? json(response, 200, detail) : json(response, 404, { error: '依頼が見つかりません。' })
      }
      const asset = STATIC.get(url.pathname)
      if (!asset) return json(response, 404, { error: 'ページが見つかりません。' })
      let content = contents.get(asset.file)
      if (content === undefined) {
        content = await readFile(new URL('./public/' + asset.file, import.meta.url), 'utf8')
        if (asset.file === 'app.js') content = 'const icons = ' + JSON.stringify(iconAssets()) + '\n' + content
        contents.set(asset.file, content)
      }
      return send(response, 200, asset.contentType, content)
    } catch {
      return json(response, 500, { error: '作業ライブの応答を作れませんでした。' })
    }
  })
  server.once('listening', () => {
    ready = poll()
    timer = setInterval(() => { void poll() }, 2_000)
  })
  server.once('close', () => { clearInterval(timer) })
  return server
}

export function listenLocally(server: ReturnType<typeof createWorkLiveServer>, port: number) {
  return server.listen(port, HOST)
}

function main() {
  const args = process.argv.slice(2)
  const portText = args[0] === '--port' && args.length === 2 ? args[1] :
    args.length === 0 ? process.env.WORK_LIVE_PORT ?? String(DEFAULT_PORT) : ''
  const port = Number(portText)
  if (!portText || !/^\d+$/u.test(portText) || !Number.isInteger(port) || port < 1 || port > 65535) {
    console.error('ポートは 1〜65535 の整数で指定してください。')
    process.exitCode = 1
    return
  }
  const server = createWorkLiveServer()
  server.once('error', (error: Error) => {
    console.error(recordOf(error).code === 'EADDRINUSE' ? 'ポート ' + port + ' は使用中です。'
      : 'ポート ' + port + ' で作業ライブを起動できませんでした。')
    process.exitCode = 1
  })
  listenLocally(server, port)
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) main()
