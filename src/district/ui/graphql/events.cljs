(ns district.ui.graphql.events
  (:require
    [ajax.core :as ajax]
    [cljsjs.apollo-fetch]
    [cljsjs.graphql]
    [day8.re-frame.forward-events-fx]
    [day8.re-frame.http-fx]
    [district.graphql-utils :as graphql-utils]
    [district.ui.graphql.effects :as effects]
    [district.ui.graphql.middleware.id-fields :refer [id-fields-middleware]]
    [district.ui.graphql.middleware.typenames :refer [typenames-middleware]]
    [district.ui.graphql.queries :as queries]
    [district.ui.graphql.utils :as utils]
    [re-frame.core :refer [reg-event-fx reg-event-db trim-v]]
    [goog.object :as gobj]))

(def interceptors [trim-v])

(defn add-token-fetcher-middleware [data next]
  (when-let [token (queries/authorization-token @re-frame.db/app-db)]
    (when-not (.. data -options -headers)
      (set! (.. data -options -headers) #js {}))
    (gobj/set (.. data -options -headers) "authorization" (str "Bearer " token)))
  (next))

(reg-event-db
  ::set-authorization-token
  (fn [db [_ token]]
    (queries/set-authorization-token db token)))

(reg-event-fx
  ::start
  interceptors
  (fn [{:keys [:db]} [{:keys [:schema :url :query-middlewares :fetch-opts :disable-default-middlewares?] :as opts}]]
    (let [fetcher (when url
                    (doto (js/apolloFetch.createApolloFetch (clj->js (merge {:uri url} fetch-opts)))
                      (.use add-token-fetcher-middleware)))
          dataloader (utils/create-dataloader {:fetch-event [::fetch]
                                               :on-success [::normalize-response]
                                               :on-error [::query-error*]
                                               :on-response [::query-response*]
                                               :on-request [::query-request*]})]

      {:db (-> db
             (queries/merge-config
               (merge
                 {:kw->gql-name graphql-utils/kw->gql-name
                  :gql-name->kw graphql-utils/gql-name->kw
                  :fetcher fetcher
                  :dataloader dataloader
                  :schema (utils/build-schema schema)
                  :query-middlewares (concat (when-not disable-default-middlewares?
                                               [(utils/create-middleware :id-fields id-fields-middleware)
                                                (utils/create-middleware :typenames typenames-middleware)])
                                             query-middlewares)}
                 (select-keys opts [:kw->gql-name :gql-name->kw]))))})))


(reg-event-fx
  ::query
  interceptors
  (fn [{:keys [:db]} [{:keys [:query :query-str :variables :refetch-on :refetch-id :id] :as opts}]]
    (let [{:keys [:query :query-str]} (if-not query-str
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
        {:db (cond-> db
               true (queries/assoc-query-preprocessing query-str true)
               id (queries/add-id-query id query-str variables))}

        (when (and refetch-on refetch-id)
          {:forward-events {:register (str queries/db-key refetch-id)
                            :events refetch-on
                            :dispatch-to [::query (dissoc opts :refetch-on :refetch-id)]}})))))


(reg-event-fx
  ::mutation
  interceptors
  (fn [{:keys [:db]} [{:keys [:queries :query :variables :on-success :on-error] :as opts}]]
    (let [query (cond->> query
                        (some? queries) (merge-with into {:queries queries})
                        true (merge {:operation {:operation/type :mutation
                                                 :operation/name :a-mutation}}))
          {:keys [:url :kw->gql-name :gql-name->kw :query-middlewares :fetcher :schema]} (queries/config db)
          {:keys [:query :query-str]} (utils/parse-query query
                                                               {:kw->gql-name kw->gql-name})]

      {::effects/fetch {:fetcher (queries/config db :fetcher)
                        :query query
                        :variables variables
                        :on-success [::mutation-success {:on-success on-success}]
                        :on-error [::mutation-error {:on-error on-error}]
                        :gql-name->kw gql-name->kw
                        :query-middlewares query-middlewares
                        :schema schema}})))


(reg-event-fx
  ::mutation-success
  interceptors
  (fn [{:keys [:db]} [{:keys [:on-success]} resp {:keys [:query :query-str :variables :query-clj]}]]
    {:dispatch [::normalize-response resp {:query-clj query-clj
                                           :query-str query-str
                                           :variables variables
                                           :on-normalization-success on-success}]}))


(reg-event-fx
  ::mutation-error
  interceptors
  (fn [{:keys [:db]} [{:keys [:on-error]} errors {:keys [:query-str]}]]
    {:dispatch-n (cond-> [[::set-query-errors {:errors errors :query-str query-str}]]
                         on-error (conj (vec (concat on-error [errors]))))}))

(reg-event-fx
  ::normalize-response
  interceptors
  (fn [{:keys [:db]} [response {:keys [:query-clj :query-str :variables :on-normalization-success]}]]
    (let [results (utils/normalize-response response query-clj (queries/config db))]
      (merge {:db (queries/merge-results db results)}
             (when on-normalization-success
                 (let [{:keys [:schema :gql-name->kw]} (queries/config db)
                       {:keys [:data]} (utils/build-response-data schema query-str results variables gql-name->kw)]
                   {:dispatch (vec (concat on-normalization-success [data]))}))))))


(reg-event-fx
  ::assoc-queries-with-batched-query
  interceptors
  (fn [{:keys [:db]} [{:keys [:batched-query-str :query-configs]}]]
    {:db (queries/assoc-queries-with-batched-query db query-configs batched-query-str {:finish-query-preprocessing? true})}))


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
  ::set-schema
  interceptors
  (fn [{:keys [:db]} [schema]]
    {:db (queries/merge-config db {:schema (utils/build-schema schema)})}))


(reg-event-fx
  ::update-entity
  interceptors
  (fn [{:keys [:db]} [type id new-entity]]
    {:db (queries/update-entity db type id new-entity)}))


(reg-event-fx
  ::query-error*
  interceptors
  (fn [{:keys [:db]} [errors {:keys [:query-str]}]]
    {:dispatch [::set-query-errors {:errors errors :query-str query-str}]}))


(reg-event-fx
  ::query-request*
  interceptors
  (fn [{:keys [:db]} [{:keys [:query-str :query-configs]}]]
    {:dispatch-n [[::assoc-queries-with-batched-query {:batched-query-str query-str
                                                       :query-configs query-configs}]
                  [::set-query-loading {:query-str query-str
                                        :loading? true}]]}))

(reg-event-fx
  ::query-response*
  interceptors
  (fn [{:keys [:db]} [_ {:keys [:query-str]}]]
    {:dispatch [::set-query-loading {:query-str query-str
                                     :loading? false}]}))


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
