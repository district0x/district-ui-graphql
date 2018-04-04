(ns district.ui.graphql.subs
  (:require
    [district.ui.graphql.events :as events]
    [district.ui.graphql.queries :as queries]
    [district.ui.graphql.utils :as utils]
    [graphql-query.core :refer [graphql-query]]
    [re-frame.core :refer [reg-sub reg-sub-raw dispatch-sync dispatch]]
    [reagent.ratom :refer [make-reaction]]))

(defn- sub-fn [query-fn]
  (fn [db [_ & args]]
    (apply query-fn db args)))


(reg-sub-raw
  ::query
  (fn [db [_ query {:keys [:variables :refetch-on :refetch-id :disable-fetch?]}]]
    (let [{:keys [:query :query-str]}
          (utils/parse-query query {:kw->gql-name (queries/config @db :kw->gql-name)})

          refetch-id (when refetch-on (or refetch-id (hash [query-str variables refetch-on])))]
      (when-not disable-fetch?
        (dispatch-sync [::events/query {:query query
                                        :query-str query-str
                                        :variables variables
                                        :refetch-on refetch-on
                                        :refetch-id refetch-id}]))
      (make-reaction
        (fn []
          (queries/query @db query-str variables))
        :on-dispose (fn []
                      (when refetch-id
                        (dispatch [::events/unregister-refetch {:refetch-id refetch-id}])))))))


(reg-sub
  ::entities
  (sub-fn queries/entities))


(reg-sub
  ::entity
  (sub-fn queries/entity))


(reg-sub
  ::graph
  queries/graph)
