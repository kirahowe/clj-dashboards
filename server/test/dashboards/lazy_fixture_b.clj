(ns dashboards.lazy-fixture-b
  "Loaded only by a test app's `app.clj` in `dashboards.server-test`,
  from a future: nothing else may require it.")

(def answer 43)
