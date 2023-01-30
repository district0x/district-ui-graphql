(ns district.ui.graphql.queries
  (:require
    [district.cljs-utils :as cljs-utils]
    [district.ui.graphql.utils :as utils]))

(def db-key :district.ui.graphql)


(defn config
  ([db]
   (get-in db [db-key :config]))
  ([db key]
   (get-in db [db-key :config key])))


(defn merge-config [db config]
  (update-in db [db-key :config] merge config))


(defn results [db]
  (get-in db [db-key :results]))


(defn merge-results [db results]
  (update-in db [db-key :results] cljs-utils/merge-in results))


(defn query->batched-query [db query-str variables]
  (get-in db [db-key :query->batched-query query-str variables]))


(defn query-info [db query-str]
  (get-in db [db-key :query-info query-str]))


(defn query->batched-query-info [db query variables]
  (query-info db (query->batched-query db query variables)))


(defn assoc-query-preprocessing [db query-str preprocessing?]
  (assoc-in db [db-key :query-info query-str :preprocessing?] preprocessing?))


(defn query-preprocessing? [db query-str]
  (:preprocessing? (query-info db query-str)))


(defn assoc-queries-with-batched-query [db query-configs batched-query-str & [{:keys [:finish-query-preprocessing?]}]]
  (reduce (fn [db {:keys [:query-str :variables]}]
            (cond-> db
              true (assoc-in [db-key :query->batched-query query-str variables] batched-query-str)
              finish-query-preprocessing? (assoc-query-preprocessing query-str false)))
          db
          query-configs))


(defn assoc-query-loading [db query-str loading?]
  (assoc-in db [db-key :query-info query-str :loading?] loading?))


(defn assoc-query-errors [db query-str errors]
  (assoc-in db [db-key :query-info query-str :errors] errors))


(defn query [db query-str variables & [{:keys [:consider-preprocessing-as-loading?]
                                        :or {consider-preprocessing-as-loading? true}}]]
  (let [gql-name->kw (config db :gql-name->kw)
        {:keys [:data :errors]}
        (utils/build-response-data (config db :schema)
                                   query-str
                                   (results db)
                                   variables
                                   gql-name->kw)
        query-info (query->batched-query-info db query-str variables)]
    (merge data
           (when errors
             {:graphql/errors (map #(aget % "message") (vec errors))})
           (when-let [errors (:errors query-info)]
             {:graphql/errors errors})
           (let [preprocessing? (query-preprocessing? db query-str)]
             {:graphql/preprocessing? preprocessing?
              :graphql/loading? (or (:loading? query-info)
                                    (and consider-preprocessing-as-loading?
                                         preprocessing?))}))))


(defn entities
  ([db]
   (get-in db [db-key :results :entities]))
  ([db type]
   (get-in db [db-key :results :entities type])))


(defn- entity-id [id]
  (if (map? id)
    (cljs-utils/transform-vals str id)
    (str id)))


(defn entity [db type id]
  (get-in db [db-key :results :entities type (entity-id id)]))


(defn graph [db]
  (get-in db [db-key :results :graph]))


(defn update-entity [db type id new-entity]
  (update-in db [db-key :results :entities type (entity-id id)] cljs-utils/merge-in new-entity))


(defn add-id-query [db id query-str variables]
  (update-in db [db-key :id-queries id] (comp vec conj) {:query-str query-str :variables variables}))

(defn remove-id-queries [db id]
  (update-in db [db-key :id-queries] dissoc id))

(defn id-queries [db id]
  (get-in db [db-key :id-queries id]))

(defn set-authorization-token [db token]
  (assoc-in db [db-key :authorization-token] token))

(defn authorization-token [db]
  (get-in db [db-key :authorization-token]))
