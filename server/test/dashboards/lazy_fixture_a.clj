(ns dashboards.lazy-fixture-a
  "Loaded only by `dashboards.server-test`, with `requiring-resolve`,
  while an app is loading: nothing else may require it.")

(def answer 42)
