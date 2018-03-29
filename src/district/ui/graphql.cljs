(ns district.ui.graphql
  (:require
    [cljs.spec.alpha :as s]
    [district.ui.graphql.events :as events]
    [mount.core :as mount :refer [defstate]]
    [re-frame.core :refer [dispatch-sync]]))

(defn start [opts]
  (dispatch-sync [::events/start opts])
  opts)

(defn stop []
  (dispatch-sync [::events/stop]))

(defstate district-ui-graphql
  :start (start (:graphql (mount/args)))
  :stop (stop))

