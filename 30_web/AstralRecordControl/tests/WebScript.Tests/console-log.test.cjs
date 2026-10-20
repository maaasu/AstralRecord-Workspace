const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { tokenize, render } = require('../../src/AstralRecordControl.Web/wwwroot/js/console-log.js');
const textOf = tokens => tokens.map(token => token.text).join('');

test('console keeps Japanese and Unicode while interpreting ANSI colors and resets', () => {
    const tokens = tokenize('\x1b[90m日本語 🐈\x1b[31m赤\x1b[0m通常');
    assert.equal(textOf(tokens), '日本語 🐈赤通常');
    assert.equal(tokens[0].style.color, '#8b98ad');
    assert.equal(tokens[1].style.color, '#ed7b83');
    assert.deepEqual(tokens[2].style, {});
});

test('console handles indexed and RGB foreground/background plus attribute resets', () => {
    const tokens = tokenize('\x1b[38;5;196;48;2;1;2;3;1;3;4m色\x1b[22;23;24;39;49m戻る');
    assert.equal(tokens[0].style.color, 'rgb(255, 0, 0)');
    assert.equal(tokens[0].style.backgroundColor, 'rgb(1, 2, 3)');
    assert.equal(tokens[0].style.fontWeight, '700');
    assert.equal(tokens[0].style.fontStyle, 'italic');
    assert.equal(tokens[0].style.textDecoration, 'underline');
    assert.deepEqual(tokens[1].style, {});
    assert.equal(tokenize('\x1b[38;5;244m灰')[0].style.color, 'rgb(128, 128, 128)');
});

test('console merges adjacent identical styles after repeated ANSI resets', () => {
    const tokens = tokenize(('x\x1b[0m').repeat(52428));
    assert.equal(tokens.length, 1);
    assert.equal(tokens[0].text, 'x'.repeat(52428));
});

test('console removes cursor, title and hyperlink controls without creating browser links', () => {
    const raw = '\x1b[2J前\x1b]0;secret title\x07\x1b]8;;javascript:alert(1)\x1b\\リンク\x1b]8;;\x07後\x00\x07';
    assert.equal(textOf(tokenize(raw)), '前リンク後');
    assert.equal(textOf(tokenize('\x1b[90')), '');
    assert.equal(textOf(tokenize('\x1b[?25l本文\x1b[?25h')), '本文');
    assert.equal(textOf(tokenize('前\n\x1b]0;未終端\n[ERROR] 後続ログ\n')), '前\n\n[ERROR] 後続ログ\n');
    assert.equal(textOf(tokenize('\x1b]8;;https://example.invalid\r\n次の行')), '\r\n次の行');
    assert.deepEqual(tokenize('\x1b[38;2;999;0;0m本文')[0].style, {});
});

test('console marks severity prefixes and renders all log contents as literal text spans', () => {
    const created = [];
    const document = {
        createDocumentFragment: () => ({ children: [], append(node) { this.children.push(node); } }),
        createElement(name) {
            created.push(name);
            return { textContent: '', style: {}, className: '', set innerHTML(_) { throw new Error('HTML must not be interpreted'); } };
        }
    };
    const element = { ownerDocument: document, replaceChildren(fragment) { this.children = fragment.children; } };
    render(element, '[20:50:49 WARN]: 日本語 <img src=x onerror=alert(1)>\n\x1b[91m[ERROR] 故障\x1b[0m');
    assert.ok(created.every(name => name === 'span'));
    assert.equal(element.children.map(child => child.textContent).join(''), '[20:50:49 WARN]: 日本語 <img src=x onerror=alert(1)>\n[ERROR] 故障');
    assert.equal(element.children[0].className, 'console-level-warn');
    const error = element.children.find(child => child.textContent === '[ERROR]');
    assert.equal(error.className, 'console-level-error');
    assert.equal(error.style.color, '#ff9098');
});

test('console browser script can load repeatedly across enhanced navigation', () => {
    const context = vm.createContext({ window: {} });
    const source = fs.readFileSync(path.join(__dirname, '../../src/AstralRecordControl.Web/wwwroot/js/console-log.js'), 'utf8');
    vm.runInContext(source, context);
    vm.runInContext(source, context);
    assert.equal(textOf(context.window.ControlConsoleLog.tokenize('\x1b[32m正常\x1b[0m')), '正常');
});
