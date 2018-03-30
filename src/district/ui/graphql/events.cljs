(ns district.ui.graphql.events
  (:require
    [cljsjs.apollo-fetch]
    [cljsjs.graphql]
    [day8.re-frame.forward-events-fx]
    [district.graphql-utils :as graphql-utils]
    [district.ui.graphql.effects :as effects]
    [district.ui.graphql.queries :as queries]
    [district.ui.graphql.query-middlewares :refer [add-id-fields-middleware create-add-fields-middleware middleware]]
    [district.ui.graphql.utils :as utils]
    [graphql-query.core :refer [graphql-query]]
    [re-frame.core :refer [reg-event-fx trim-v]]))

(def interceptors [trim-v])
(def build-schema (aget js/GraphQL "buildSchema"))

(reg-event-fx
  ::start
  interceptors
  (fn [{:keys [:db]} [{:keys [:schema :url :query-middlewares :fetch-opts] :as opts}]]

    (let [fetcher (js/apolloFetch.createApolloFetch (clj->js (merge {:uri url} fetch-opts)))
          dataloader (utils/create-dataloader {:fetch-event [::fetch]
                                               :on-success [::normalize-response]
                                               :on-error [::query-error*]
                                               :on-response [::query-response*]
                                               :on-request [::query-request*]})]

      {:db (-> db
             (queries/merge-config
               (merge
                 {:typename-field :__typename
                  :kw->gql-name graphql-utils/kw->gql-name
                  :gql-name->kw graphql-utils/gql-name->kw
                  :fetcher fetcher
                  :dataloader dataloader
                  :schema (-> (build-schema schema)
                            (graphql-utils/add-keyword-type {:disable-serialize? true})
                            (graphql-utils/add-date-type {:disable-serialize? true}))
                  :query-middlewares (concat [(middleware :add-id-fields add-id-fields-middleware)
                                              (create-add-fields-middleware :add-typename ["__typename"])]
                                             query-middlewares)}
                 (select-keys opts [:typename-field :kw->gql-name :gql-name->kw]))))})))


(reg-event-fx
  ::query
  interceptors
  (fn [{:keys [:db]} [{:keys [:query :query-str :variables :refetch-on :refetch-id] :as opts}]]
    (let [{:keys [:query :query-str]} (if-not (or query query-str)
                                        (utils/parse-query query {:kw->gql-name (queries/config db :kw->gql-name)})
                                        opts)]

      (merge
        {::effects/enqueue-query
         {:schema (queries/config db :schema)
          :fetcher (queries/config db :fetcher)
          :dataloader (queries/config db :dataloader)
          :query query
          :query-str query-str
          :variables variables}}
        (when (and refetch-on refetch-id)
          {:forward-events {:register (str queries/db-key refetch-id)
                            :events refetch-on
                            :dispatch-to [::query (dissoc opts :refetch-on :refetch-id)]}})))))


(reg-event-fx
  ::normalize-response
  interceptors
  (fn [{:keys [:db]} [response {:keys [:query :query-clj :variables]}]]
    (let [query-clj (if-not query-clj
                      (utils/query->clj query
                                        (queries/config db :schema)
                                        {:gql-name->kw (queries/config db :gql-name->kw)
                                         :variables variables})
                      query-clj)
          results (utils/normalize-response response
                                            query-clj
                                            (select-keys (queries/config db) [:typename-field
                                                                              :kw->gql-name
                                                                              :gql-name->kw]))]
      {:db (queries/merge-results db results)})))


(reg-event-fx
  ::assoc-queries-with-merged-query
  interceptors
  (fn [{:keys [:db]} [{:keys [:merged-query-str :query-configs]}]]
    {:db (queries/assoc-queries-with-merged-query db query-configs merged-query-str)}))


(reg-event-fx
  ::set-query-loading
  interceptors
  (fn [{:keys [:db]} [{:keys [:query-str :loading?]}]]
    {:db (queries/assoc-query-loading db query-str loading?)}))


(reg-event-fx
  ::set-query-errors
  interceptors
  (fn [{:keys [:db]} [{:keys [:query-str :errors]}]]
    {:db (queries/assoc-query-errors db query-str errors)}))


(reg-event-fx
  ::fetch
  interceptors
  (fn [{:keys [:db]} [opts]]
    {::effects/fetch (merge (queries/config db) opts)}))


(reg-event-fx
  ::query-error*
  interceptors
  (fn [{:keys [:db]} [errors {:keys [:query-str]}]]
    {:dispatch [::set-query-errors {:errors errors :query-str query-str}]}))


(reg-event-fx
  ::query-request*
  interceptors
  (fn [{:keys [:db]} [{:keys [:query-str :query-configs]}]]
    {:dispatch-n [[::assoc-queries-with-merged-query {:merged-query-str query-str
                                                      :query-configs query-configs}]
                  [::set-query-loading {:query-str query-str
                                        :loading? true}]]}))

(reg-event-fx
  ::query-response*
  interceptors
  (fn [{:keys [:db]} [_ {:keys [:query-str]}]]
    {:dispatch [::set-query-loading {:query-str query-str :loading? false}]}))


(reg-event-fx
  ::unregister-refetch
  interceptors
  (fn [{:keys [:db]} [{:keys [:refetch-id]}]]
    {:forward-events {:unregister (str queries/db-key refetch-id)}}))


(reg-event-fx
  ::stop
  interceptors
  (fn [{:keys [:db]}]
    ))
