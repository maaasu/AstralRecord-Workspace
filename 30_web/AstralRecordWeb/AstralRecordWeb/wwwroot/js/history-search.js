(() => {
    document.querySelectorAll('[data-history-entity-search]').forEach(root => {
        const form = root.closest('form');
        const input = root.querySelector('input[role="combobox"]');
        const list = root.querySelector('[data-history-suggestions]');
        const status = root.querySelector('[data-history-search-status]');
        if (!form || !input || !list || !status || !root.dataset.suggestionsUrl) return;

        let timer = 0;
        let generation = 0;
        let request = null;
        let composing = false;

        function clearSuggestions() {
            list.replaceChildren();
            list.hidden = true;
            input.setAttribute('aria-expanded', 'false');
        }

        function renderSuggestions(suggestions) {
            list.replaceChildren();
            suggestions.forEach(suggestion => {
                const row = document.createElement('li');
                const button = document.createElement('button');
                const name = document.createElement('strong');
                const id = document.createElement('small');
                button.type = 'button';
                button.setAttribute('role', 'option');
                button.dataset.historySuggestionId = suggestion.id;
                button.dataset.historySuggestionName = suggestion.name;
                name.textContent = suggestion.name;
                id.textContent = `ID: ${suggestion.id}`;
                button.append(name, id);
                row.append(button);
                list.append(row);
            });
            list.hidden = suggestions.length === 0;
            input.setAttribute('aria-expanded', suggestions.length > 0 ? 'true' : 'false');
            status.textContent = suggestions.length
                ? `${suggestions.length}件の候補があります。候補を選ぶとIDが入力されます。`
                : '一致する候補がありません。名称を入力してそのまま検索できます。';
        }

        async function search(searchGeneration, term) {
            if (searchGeneration !== generation) return;
            request = new AbortController();
            status.textContent = '候補を検索しています…';
            try {
                const url = new URL(root.dataset.suggestionsUrl, window.location.href);
                url.searchParams.set('term', term);
                ['From', 'To', 'UserUuid', 'AccountId', 'Query'].forEach(name => {
                    const field = form.querySelector(`[name="${name}"]`);
                    if (field?.value) url.searchParams.set(name, field.value);
                });
                const response = await fetch(url, {
                    signal: request.signal,
                    credentials: 'same-origin',
                    cache: 'no-store',
                    headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
                });
                if (searchGeneration !== generation) return;
                if (response.redirected || response.status === 401 || response.status === 403) {
                    clearSuggestions();
                    status.textContent = '管理権限を確認できません。再ログインしてからお試しください。';
                    return;
                }
                if (!response.ok) throw new Error('Suggestions unavailable');
                const result = await response.json();
                if (searchGeneration === generation) renderSuggestions(result.suggestions ?? []);
            } catch (error) {
                if (error.name !== 'AbortError' && searchGeneration === generation) {
                    clearSuggestions();
                    status.textContent = '候補を取得できませんでした。名称を入力してそのまま検索できます。';
                }
            }
        }

        function scheduleSearch() {
            clearTimeout(timer);
            request?.abort();
            generation++;
            clearSuggestions();
            const term = input.value.trim();
            if (term.length < 2) {
                status.textContent = '2文字以上入力すると候補を表示します。名称を直接入力して検索することもできます。';
                return;
            }
            const searchGeneration = generation;
            status.textContent = '候補を検索しています…';
            timer = setTimeout(() => search(searchGeneration, term), 250);
        }

        input.addEventListener('input', event => {
            if (!composing && !event.isComposing) scheduleSearch();
        });
        input.addEventListener('compositionstart', () => {
            composing = true;
            clearTimeout(timer);
            request?.abort();
            generation++;
            clearSuggestions();
        });
        input.addEventListener('compositionend', () => {
            composing = false;
            scheduleSearch();
        });
        input.addEventListener('keydown', event => {
            if (event.key === 'Escape') {
                clearSuggestions();
                return;
            }
            if (event.key === 'ArrowDown' && !list.hidden) {
                event.preventDefault();
                list.querySelector('button')?.focus();
            }
        });
        list.addEventListener('keydown', event => {
            const options = [...list.querySelectorAll('button')];
            const currentIndex = options.indexOf(document.activeElement);
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                const direction = event.key === 'ArrowDown' ? 1 : -1;
                options[(currentIndex + direction + options.length) % options.length]?.focus();
            } else if (event.key === 'Escape') {
                event.preventDefault();
                clearSuggestions();
                input.focus();
            }
        });
        list.addEventListener('click', event => {
            const button = event.target.closest('[data-history-suggestion-id]');
            if (!button) return;
            input.value = button.dataset.historySuggestionId;
            clearSuggestions();
            status.textContent = `${button.dataset.historySuggestionName}（ID: ${button.dataset.historySuggestionId}）を選択しました。`;
            input.focus();
        });
        form.addEventListener('submit', clearSuggestions);
        if (input.value.trim().length >= 2) scheduleSearch();
    });
})();
