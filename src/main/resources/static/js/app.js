// Centry's one first-party script: busy submit buttons on slow requests, and closing the account menu.
(() => {
  const DELAY_MS = 300; // anything quicker would only flash a spinner
  const timers = new WeakMap();
  const submitOf = (form) => form.querySelector('[type=submit]');
  const later = (form, button) => timers.set(form, setTimeout(() => button.setAttribute('aria-busy', 'true'), DELAY_MS));
  const reset = (form) => {
    clearTimeout(timers.get(form));
    delete form.dataset.submitting;
    form.querySelectorAll('[aria-busy]').forEach((el) => el.removeAttribute('aria-busy'));
  };

  // Plain forms. htmx has already called preventDefault on its own forms, so they are skipped here.
  document.addEventListener('submit', (event) => {
    const form = event.target;
    if (event.defaultPrevented) return;
    if (form.dataset.submitting) { event.preventDefault(); return; } // second tap while the first is in flight
    form.dataset.submitting = 'true';
    const button = event.submitter || submitOf(form);
    if (button) later(form, button);
  });
  // Back/forward cache restores a page with the busy state frozen in it.
  window.addEventListener('pageshow', (event) => { if (event.persisted) document.querySelectorAll('form').forEach(reset); });

  // htmx forms: hx-disabled-elt already blocks the second tap; add the delayed spinner.
  document.addEventListener('htmx:beforeRequest', (event) => {
    const form = event.detail.elt, button = form.matches('form') && submitOf(form);
    if (button) later(form, button);
  });
  document.addEventListener('htmx:afterRequest', (event) => reset(event.detail.elt));

  // Pico's <details class="dropdown"> stays open on its own: close it on an outside click or Escape.
  document.addEventListener('click', (event) => {
    document.querySelectorAll('details.dropdown[open]').forEach((menu) => {
      if (!menu.contains(event.target)) menu.removeAttribute('open');
    });
  });
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const menu = document.querySelector('details.dropdown[open]');
    if (menu) { menu.removeAttribute('open'); menu.querySelector('summary').focus(); }
  });
})();
