import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { createServer, request } from 'node:http'
import { join } from 'node:path'
import process from 'node:process'
import { test } from 'node:test'
import type { TestContext } from 'node:test'
import { fileURLToPath } from 'node:url'
import { createWorkLiveServer, listenLocally } from '../server.ts'
import type { boardFor, detailFor } from '../src/board.ts'
import { assistantMessage, call, claude, jsonl, temporaryHome, userMessage, writeFixture } from './fixtures/records.ts'
import { parseSvg } from './fixtures/svg.ts'

const CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'"
const startupScript = fileURLToPath(new URL('../start.ps1', import.meta.url))
const serverScript = fileURLToPath(new URL('../server.ts', import.meta.url))

function get(url: string, method = 'GET', host?: string, origin?: string) {
  return new Promise<{ status: number | undefined; headers: Record<string, unknown>; body: string }>((resolve, reject) => {
    const response = request(url, { method, headers: { ...(host ? { Host: host } : {}), ...(origin ? { Origin: origin } : {}) } }, message => {
      let body = ''
      message.setEncoding('utf8').on('data', chunk => { body += chunk }).once('end', () => {
        resolve({ status: message.statusCode, headers: message.headers, body })
      })
    })
    response.on('error', reject)
    response.end()
  })
}

async function start(t: TestContext, server: ReturnType<typeof createWorkLiveServer>) {
  await new Promise<void>((resolve, reject) => {
    server.once('listening', resolve).once('error', reject)
    listenLocally(server, 0)
  })
  t.after(() => new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve())))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  assert.equal(address.address, '127.0.0.1')
  assert.equal(address.family, 'IPv4')
  return address.port
}

function run(file: string, args: string[], env = process.env) {
  return new Promise<{ code: number | string; stdout: string; stderr: string }>(resolve => {
    execFile(file, args, { env, windowsHide: true, encoding: 'utf8', timeout: 15_000 }, (error, stdout, stderr) => {
      resolve({ code: error?.code ?? (error ? 'error' : 0), stdout, stderr })
    })
  })
}

function launch(home: string, ...args: string[]) {
  const command = "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); & '" +
    startupScript.replace(/'/gu, "''") + "' " + args.join(' ') + '; exit $LASTEXITCODE'
  return run('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command],
    { ...process.env, LOCALAPPDATA: home })
}

test('health の応答、ループバック待ち受け、Host、GET、CSP と no-store を検証する', async t => {
  const home = await temporaryHome(t)
  const port = await start(t, createWorkLiveServer({ home }))
  const url = 'http://127.0.0.1:' + port
  for (const [method, host, status] of [
    ['GET', '127.0.0.1:' + port, 200],
    ['GET', 'localhost:' + port, 200],
    ['GET', 'localhost', 403],
    ['GET', '127.0.0.1:' + (port === 65535 ? port - 1 : port + 1), 403],
    ['GET', 'example.test:' + port, 403],
    ['POST', '127.0.0.1:' + port, 405],
  ] as const) {
    const received = await get(url + '/api/health', method, host, 'https://example.test')
    assert.equal(received.status, status)
    assert.equal(received.headers['content-security-policy'], CSP)
    assert.equal(received.headers['cache-control'], 'no-store')
    assert.equal(received.headers['x-content-type-options'], 'nosniff')
    assert.equal(received.headers['access-control-allow-origin'], undefined)
    if (status === 200) assert.deepEqual(JSON.parse(received.body), { app: 'work-live', version: '0.1.0' })
    if (status === 405) assert.equal(received.headers.allow, 'GET')
  }
  for (const [path, type] of [['/', 'text/html'], ['/app.js', 'text/javascript'], ['/app.css', 'text/css']]) {
    const asset = await get(url + path)
    assert.equal(asset.status, 200)
    assert.equal(asset.headers['content-type'], type + '; charset=utf-8')
  }
  assert.equal((await get(url + '/server.ts')).status, 404)
  assert.equal((await get(url + '/api/detail')).status, 400)
  assert.equal((await get(url + '/api/detail?id=unknown')).status, 404)
})

test('SVG の依頼文と実行内容はエスケープし、詳細は表示用テキストの JSON で返す', async t => {
  const home = await temporaryHome(t)
  const now = Date.now()
  const prompt = '<skill><name>見本スキル</name></skill>\n表示を確認 <script>"依頼"</script> と & [資料](https://example.test/)'
  const command = 'echo "<script>window.injected = true</script>"'
  const folder = join(home, '.claude', 'projects', 'fixture')
  await writeFixture(join(folder, 'x.jsonl'), jsonl(
    claude(now - 3_000, 'user', 'prompt', null, userMessage(prompt)),
    claude(now - 2_000, 'assistant', 'tool', 'prompt', assistantMessage([call('command', 'Bash', { command })], 'tool_use')),
  ), now)
  await writeFixture(join(folder, 'x', 'custom-title.json'), JSON.stringify({ customTitle: '"><script>確認</script>' }), now)
  const port = await start(t, createWorkLiveServer({ home, now: () => now }))
  const url = 'http://127.0.0.1:' + port
  const boardResponse = await get(url + '/api/board?session=all&scope=all')
  assert.equal(boardResponse.status, 200)
  const board: ReturnType<typeof boardFor> = JSON.parse(boardResponse.body)
  const flow = board.flows[0]!
  assert.match(flow.summary, /^表示を確認/u)
  assert.match(flow.svg, /&lt;script&gt;&quot;依頼&quot;&lt;\/script&gt;/u)
  assert.match(flow.svg, /&quot;&gt;&lt;script&gt;確認&lt;\/script&gt;/u)
  assert.match(flow.svg, /echo &quot;&lt;script&gt;/u)
  assert.doesNotMatch(flow.svg, /<script|\son[a-z]+="/iu)
  const { elements } = parseSvg(flow.svg)
  assert.ok(elements.every(element => !['script', 'foreignObject', 'a', 'image'].includes(element.name)))
  const response = await get(url + '/api/detail?id=' + encodeURIComponent(flow.rows[0]!.id))
  assert.equal(response.status, 200)
  assert.equal(response.headers['content-type'], 'application/json; charset=utf-8')
  assert.doesNotMatch(response.body, /<script>/iu)
  const detail: NonNullable<ReturnType<typeof detailFor>> = JSON.parse(response.body)
  assert.equal(detail.prompt, prompt)
  assert.equal(detail.steps[0]?.full, command)
})

test('別のプログラムが使用中のポートではサーバーを起動せず、別ポートに切り替えない', async t => {
  const home = await temporaryHome(t)
  const port = await start(t, createServer((_request, response) => response.end('別プログラム')))
  const node = await run(process.execPath, [serverScript, '--port', String(port)])
  assert.equal(node.code, 1)
  assert.match(node.stderr, new RegExp('ポート ' + port + ' は使用中'))
  if (process.platform === 'win32') {
    const shell = await launch(home, '-Port', String(port))
    assert.equal(shell.code, 1)
    assert.match(shell.stderr, new RegExp('ポート ' + port + ' は使用中'))
    assert.equal(shell.stdout.trim(), '')
  }
  assert.equal((await get('http://127.0.0.1:' + port + '/api/health')).body, '別プログラム')
})

test('稼働中の作業ライブが返す URL を再利用する', { skip: process.platform !== 'win32' }, async t => {
  const home = await temporaryHome(t)
  const port = await start(t, createWorkLiveServer({ home }))
  const shell = await launch(home, '-Port', String(port))
  assert.equal(shell.code, 0)
  assert.equal(shell.stdout.trim(), 'http://127.0.0.1:' + port)
})
