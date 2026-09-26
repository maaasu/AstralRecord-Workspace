document.querySelectorAll('form[data-single-submit]').forEach((form) => {
  form.addEventListener('submit', (event) => {
    if (form.dataset.submitted === 'true') {
      event.preventDefault();
      return;
    }
    if (!form.reportValidity()) {
      event.preventDefault();
      return;
    }
    form.dataset.submitted = 'true';
    form.querySelectorAll('button[type="submit"]').forEach((button) => { button.disabled = true; });
  });
});
