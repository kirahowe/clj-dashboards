# dashboards-core

The library you write dashboards with. It has no web-server dependency.

| Namespace | |
|---|---|
| `dashboards.app` | `app` (bundles a UI and a server function), `page-html`, `local-file` |
| `dashboards.ui` | pages, layout, inputs and outputs, as plain hiccup |
| `dashboards.render` | render specs: `plot` (plotje), `table` (tablecloth), `text`, `print`, `ui`, `auto`, `download` |
| `dashboards.reactive` | the reactive engine: `value`, `reactive`, `observe`, `observe-event`, `event-reactive`, `isolate`, `req`, `validate`, `invalidate-later`, `reactive-poll`, `debounce` |
| `dashboards.session` | one browser tab's session, behind a transport-neutral message protocol, plus helpers for server functions (`notify!`, `update-input!`, `on-ended`, `request`) |
| `dashboards.html` | hiccup → HTML, datasets → tables and CSV |

The browser client (`resources/dashboards/assets/dashboards.js` and
`dashboards.css`) is plain JavaScript and CSS with no build step. To serve
the files, use the `server` module or any transport: see the namespace
docstring of `dashboards.session` for the protocol.

```clojure
io.github.kirahowe/dashboards-core
{:git/url "https://github.com/kirahowe/clj-dashboards" :git/sha "…" :deps/root "core"}
```
