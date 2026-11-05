import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const code = fs.readFileSync(new URL('../AstralRecordWeb/wwwroot/js/action-confirm.js', import.meta.url), 'utf8');

function fixture({ valid = true, supported = true, submitterConfirmation = false } = {}) {
    const handlers = new Map();
    const element = () => ({ textContent: '', addEventListener(name, callback) { handlers.set(this.id + name, callback); } });
    const cancel = { ...element(), id: 'cancel' };
    const accept = { ...element(), id: 'accept' };
    const details = { append() {}, replaceChildren() {} };
    const dialog = { ...element(), id: 'dialog', open: false,
        showModal: supported ? function () { this.open = true; } : undefined,
        close() { this.open = false; handlers.get('dialogclose')(); },
        querySelector: selector => selector.includes('details') ? details : selector.includes('accept') ? accept : cancel };
    const title = element(), message = element();
    const document = { getElementById: id => id === 'action-confirm' ? dialog : id.endsWith('title') ? title : message,
        createElement: element, addEventListener: (name, cb, capture) => { assert.equal(capture, true); handlers.set(name, cb); } };
    const confirmation = { confirmTitle: '削除しますか？', confirmMessage: '影響を確認してください。', confirmAccept: '確定する' };
    let focused = false, posts = 0, locked = false;
    const submitter = { dataset: submitterConfirmation ? confirmation : {},
        hasAttribute: name => submitterConfirmation && name === 'data-confirm-title', focus() { focused = true; }, value: 'disable' };
    const form = { dataset: submitterConfirmation ? {} : confirmation, isConnected: true,
        hasAttribute: name => !submitterConfirmation && name === 'data-confirm-title', reportValidity: () => valid,
        querySelectorAll: () => [], requestSubmit(button) { assert.equal(button, submitter); submit(); } };
    function submit() {
        let prevented = false, stopped = false;
        handlers.get('submit')?.({ target: form, submitter, preventDefault() { prevented = true; }, stopImmediatePropagation() { stopped = true; } });
        // Simulate the page's single-submit listener, which must not run on a cancelled request.
        if (!stopped) { locked = true; if (!prevented) posts++; }
        return { prevented, stopped };
    }
    vm.runInNewContext(code, { document, HTMLFormElement: { prototype: { requestSubmit() {} } } });
    return { submit, dialog, title, submitter, accept: () => handlers.get('acceptclick')(),
        cancel: () => handlers.get('cancelclick')(), state: () => ({ focused, posts, locked }) };
}

test('cancel and Escape-style close do not submit or lock the form and restore focus', () => {
    const f = fixture();
    assert.deepEqual(f.submit(), { prevented: true, stopped: true });
    assert.equal(f.dialog.open, true);
    f.cancel();
    assert.deepEqual(f.state(), { focused: true, posts: 0, locked: false });
    f.submit(); f.dialog.close();
    assert.equal(f.state().posts, 0);
});

test('confirmation submits exactly once with the original action button', () => {
    const f = fixture({ submitterConfirmation: true });
    f.submit();
    assert.equal(f.title.textContent, '削除しますか？');
    f.accept(); f.accept();
    assert.equal(f.submitter.value, 'disable');
    assert.deepEqual(f.state(), { focused: false, posts: 1, locked: true });
});

test('invalid forms never open confirmation or submit', () => {
    const f = fixture({ valid: false });
    f.submit();
    assert.equal(f.dialog.open, false);
    assert.equal(f.state().posts, 0);
});

test('unsupported dialog preserves the existing server form path', () => {
    const f = fixture({ supported: false });
    f.submit();
    assert.equal(f.state().posts, 1);
});
