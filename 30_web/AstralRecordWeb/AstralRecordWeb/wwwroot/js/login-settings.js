document.getElementById('copy-login-id')?.addEventListener('click', async () => {
    const input = document.getElementById('login-id');
    const status = document.getElementById('copy-status');
    try {
        await navigator.clipboard.writeText(input.value);
        status.textContent = 'ログインIDをコピーしました。';
    } catch {
        input.focus();
        input.select();
        status.textContent = '選択されたログインIDをコピーしてください。';
    }
});
