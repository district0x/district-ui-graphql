(ns district.ui.graphql.effects
  (:require
    [cljsjs.graphql]
    [clojure.string :as string]
    [district.cljs-utils :as cljs-utils]
    [district.graphql-utils :as graphql-utils]
    [district.ui.graphql.utils :as utils]
    [re-frame.core :refer [reg-fx dispatch]]))

(def print-str-graphql (aget js/GraphQL "print"))

(reg-fx
  ::enqueue-query
  (fn [{:keys [:fetcher :dataloader :schema :query :variables] :as opts}]
    (js-invoke dataloader "load" opts)))


(reg-fx
  ::fetch
  (fn [{:keys [:fetcher :query :variables :on-success :on-error :on-request :on-response :gql-name->kw
               :query-middlewares] :as opts}]

    (let [non-middleware-fields [:on-error :on-request :on-response :on-success]

          {:keys [:query :variables :responses]}
          (utils/apply-query-middlewares query-middlewares (apply dissoc opts non-middleware-fields))

          req-opts (merge opts {:query query
                                :query-str (print-str-graphql query)
                                :variables variables})

          fetcher-promise (when-not (empty? (string/trim (:query-str req-opts)))
                            (fetcher (clj->js {:query (:query-str req-opts) :variables variables})))]

      (println (print-str-graphql query))

      (.catch (.then (js/Promise.all (clj->js (concat responses [fetcher-promise])))
                     (fn [resps]
                       (let [res (reduce (fn [acc res]
                                           (.log js/console res)
                                           (let [res (graphql-utils/js->clj-response res {:gql-name->kw gql-name->kw})]
                                             (utils/merge-in-colls acc (print.foo/look res))))
                                         {}
                                         resps)]
                         (when on-response
                           (dispatch (vec (concat on-response [(print.foo/look res) req-opts]))))
                         (if (empty? (:errors res))
                           (when on-success
                             (dispatch (vec (concat on-success [(:data res) req-opts]))))
                           (when on-error
                             (dispatch (vec (concat on-error [(:errors res) req-opts]))))))))
              (fn [error]
                (let [error {:message (ex-message error)}]
                  (when on-response
                    (dispatch (vec (concat on-response [{:errors [error]} req-opts]))))
                  (when on-error
                    (dispatch (vec (concat on-error [[error] req-opts]))))))))))


(defn- create-middleware-fn [method]
  (fn [{:keys [:fetcher :middlewares :afterwares]}]
    (doseq [middleware (or middlewares afterwares)]
      (js-invoke fetcher method (fn [response options next]
                                  (when (fn? middleware)
                                    (apply middleware response options next))
                                  (when (sequential? middleware)
                                    (dispatch (vec (concat middleware [response options next])))))))))

(reg-fx
  ::add-fetch-middleware
  (create-middleware-fn "use"))

(reg-fx
  ::add-fetch-afterware
  (create-middleware-fn "useAfter"))

(reg-fx
  ::add-fetch-batch-middleware
  (create-middleware-fn "batchUse"))

(reg-fx
  ::add-fetch-batch-afterware
  (create-middleware-fn "batchUseAfter"))

(reg-fx
  ::fetch-middleware-next
  (fn [next]
    (next)))