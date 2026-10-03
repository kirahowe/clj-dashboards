# dashboards-core

The library you write dashboards with. It has no web-server dependency.

| Namespace | |
|---|---|
| `dashboards.app` | `app` (bundles a UI and a server function), `page-html`, `local-file` |
| `dashboards.ui` | pages, layout, inputs and outputs as hiccup, as functions or `[:ui/...]` component tags; `defcomponent` for your own |
| `dashboards.render` | render specs: `plot` (plotje), `table` (tablecloth), `text`, `print`, `ui`, `auto`, `download` |
| `dashboards.reactive` | the reactive engine: `value`, `reactive`, `observe`, `observe-event`, `event-reactive`, `isolate`, `req`, `validate`, `invalidate-later`, `reactive-poll`, `debounce` |
| `dashboards.session` | one browser tab's session: takes Datastar signal snapshots, sends message maps; plus helpers for server functions (`notify!`, `update-input!`, `on-ended`, `request`) |
| `dashboards.datastar` | session messages as Datastar SSE events, via the Datastar Clojure SDK; reading the signals a request carries |
| `dashboards.html` | hiccup → HTML, datasets → tables and CSV |

The browser client is [Datastar](https://data-star.dev) 1.0.4 (vendored,
MIT) plus `resources/dashboards/assets/dashboards.js`, a small ES module
for plot sizing, brushing, notifications and connection state, and
`dashboards.css`. There's no build step. To serve them, use the `server`
module or any transport: see the docstrings of `dashboards.session` and
`dashboards.datastar` for the protocol.

```clojure
io.github.kirahowe/dashboards-core
{:git/url "https://github.com/kirahowe/clj-dashboards" :git/sha "…" :deps/root "core"}
```
