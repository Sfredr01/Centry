# UI components

How the browser UI is put together, so a new page looks and behaves like the rest. Templates live in
`src/main/resources/templates`; the shared pieces are in `fragments/`.

Add a component only when a page uses it. If a second page needs the same markup, move it into
`fragments/components.html`; until then keep it in the page.

## Money

Show money with `${@money.format(x)}` (the `Money` bean): US dollars, grouped, two decimals, whatever the
server's locale. Machine values stay raw: `progress` value/max and `input` values.

## Icons

`fragments/icons.html`, one fragment per icon: `edit`, `delete`, `settings`, `add`, `home`, `budgets`,
`logout`, `close`, `back`, `more`, `check`, `warning`, `error`, `subscriptions`. All are 24 px outlines with a 2 px stroke, drawn
in the text colour by `.icon`. `close`, `back` and `more` are not used yet.

```html
<svg th:replace="~{fragments/icons :: edit}"></svg>
<svg th:replace="~{fragments/icons :: __${name}__}"></svg>   <!-- the name from a variable -->
```

Account is not an icon: it is the initials avatar in the app bar. The gear (`settings`) is a commented slot in
the account menu in `layout.html` until a settings page exists.

Rules:

1. An icon-only control has `aria-label` and `title` with the same text, naming the action and its object:
   "Edit Market", not "Edit".
2. An icon beside a visible word, or a decorative one, stays `aria-hidden="true" focusable="false"`. That is the
   default in `icons.html`.
3. The hit area is at least 44 x 44 px (`.icon-btn`, the avatar). Tab bar items are at least 56 px tall.
4. Every control shows the 2 px `--focus` ring.
5. Meaning is never carried by colour or an icon alone: pills keep their word, notices keep their sentence.
6. Destructive actions keep the word ("Delete") next to the trash icon and sit behind `confirm()`.
7. Keep a text label wherever an icon could be ambiguous. Primary form submits stay text.

## Components

`fragments/components.html`. Put `th:if` and `th:each` on a wrapping `<th:block>`, never on the same tag as
`th:replace`: replace runs first (precedence 100, against 200 and 300), so the loop or condition would be lost.

```html
<!-- iconLink(href, icon, label): a link that is only an icon -->
<a th:replace="~{fragments/components :: iconLink(@{/expenses/1/edit}, 'edit', 'Edit Market')}"></a>

<!-- avatar(initials) -->
<span th:replace="~{fragments/components :: avatar('SR')}"></span>

<!-- pill(level): On track / Near limit / Over limit, from a BudgetLevel -->
<span th:replace="~{fragments/components :: pill(${b.level})}"></span>

<!-- budgetBar(b): figures, bar, pill and what is left, for a BudgetStatus. The caller owns the card. -->
<article class="budget-bar card" th:attr="data-level=${b.level}">
    <th:block th:replace="~{fragments/components :: budgetBar(${b})}"></th:block>
</article>

<!-- expenseRow(r): a dashboard row for a RecentExpense, with the pencil only where r.editable -->
<th:block th:each="r : ${recent}"><li th:replace="~{fragments/components :: expenseRow(${r})}"></li></th:block>

<!-- emptyState(id, title, text, ctaHref, ctaLabel): ctaHref null leaves out the button -->
<section th:replace="~{fragments/components :: emptyState('empty-state', 'No expenses yet', 'Add one.', @{/add}, 'Add an expense')}"></section>

<!-- notice(kind, text): kind is success, warning or error; errors are role=alert, the rest role=status -->
<div th:replace="~{fragments/components :: notice('success', ${message})}"></div>
```

## Cards

A card is Pico's `<article class="card">`; there is no card fragment. Anatomy: head (title, optional meta,
actions top right), body, optional foot.

- One primary action per card. Secondary actions are labelled icon buttons, never text links like "Edit".
- Destructive actions are never on the face. They live in the editor, as a word plus the trash icon, behind
  `confirm()`.
- Expense rows have no whole-row link. A pencil at the row's end opens the editor; rows the viewer cannot edit
  get an empty `.icon-slot` so amounts stay in line.
- Dashboard budget cards are read-only. On the budgets page an admin gets a pencil that opens the editor
  (`details.budget-edit`) inside the card; a rejected edit reopens its own card.

## Loading

`static/js/app.js` is the only first-party script.

- A busy button shows after 300 ms (`DELAY_MS`), so fast responses never flash a spinner. It sets `aria-busy`;
  Pico draws the spinner.
- A second submit of the same form is blocked immediately, whatever the delay.
- Quick-add is the one htmx form. Its result is announced through `#add-status`, a live region outside the
  swapped panel, so the text is read when it changes. Other pages announce through the notice's `role` after the
  page loads.
- The same script closes the account menu on Escape or an outside click.
- No skeletons yet: every list is rendered in the page response. Add one with the first lazily loaded region,
  and stop its shimmer under `prefers-reduced-motion`.
