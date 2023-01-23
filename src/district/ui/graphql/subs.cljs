(ns district.ui.graphql.subs
  (:require
    [district.ui.graphql.events :as events]
    [district.ui.graphql.queries :as queries]
    [district.ui.graphql.utils :as utils]
    [cljs-time.extend]
    [re-frame.core :refer [reg-sub reg-sub-raw dispatch-sync dispatch]]
    [reagent.ratom :refer [make-reaction]]))

(defn- sub-fn [query-fn]
  (fn [db [_ & args]]
    (apply query-fn db args)))


(reg-sub-raw
  ::query
  (fn [db [_ query {:keys [:variables :refetch-on :refetch-id :disable-fetch? :id :consider-preprocessing-as-loading?]
                    :or {consider-preprocessing-as-loading? true}}]]
    (let [{:keys [:query :query-str]}
          (utils/parse-query query {:kw->gql-name (queries/config @db :kw->gql-name)})

          refetch-id (when refetch-on (or refetch-id (hash [query-str variables refetch-on])))]
      (when-not disable-fetch?
        (dispatch-sync [::events/query {:query query
                                        :query-str query-str
                                        :variables variables
                                        :refetch-on refetch-on
                                        :refetch-id refetch-id
                                        :id id}]))
      (make-reaction
        (fn []
          (if-not id
            (queries/query @db query-str variables {:consider-preprocessing-as-loading? consider-preprocessing-as-loading?})
            (doall
              (map (fn [{:keys [:query-str :variables]}]
                     (queries/query @db query-str variables
                                    {:consider-preprocessing-as-loading? consider-preprocessing-as-loading?}))
                   (queries/id-queries @db id)))))
        :on-dispose (fn []
                      (when id
                        (swap! db queries/remove-id-queries id))
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
