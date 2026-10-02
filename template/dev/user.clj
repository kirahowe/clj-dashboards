(ns user
  (:require [dashboards.server :as server]))

(defn go
  "Serve the app at http://localhost:8080/. Re-evaluate anything in
  my-dashboard.app and reload the page to see the change."
  []
  (require 'my-dashboard.app)
  (server/run-app! (resolve 'my-dashboard.app/app)))

(defn stop [] (server/stop-app!))
