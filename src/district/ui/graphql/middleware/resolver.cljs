(ns district.ui.graphql.middleware.resolver
  (:require
    [clojure.walk :as walk]
    [district.graphql-utils :as graphql-utils]
    [district.ui.graphql.utils :as utils]))

(def visit (aget js/GraphQL "visit"))
(def gql-sync (aget js/GraphQL "graphqlSync"))
(def gql (aget js/GraphQL "graphql"))
(def print-str-graphql (aget js/GraphQL "print"))


(defn- mask-value [value]
  (cond
    (nil? value)
    nil
    (sequential? value)
    []
    :else true))


(defn- mask-root-value [root-value]
  (cond
    (map? root-value)
    (into {} (map (fn [[k v]]
                    [k (cond
                         (fn? v)
                         (fn [params context schema]
                           (mask-root-value (v params context schema)))
                         :else
                         (mask-value v))])
                  root-value))

    (sequential? root-value)
    (map mask-root-value root-value)

    :else (mask-value root-value)))


(defn- mask-field-resolver [root-value args context info]
  (let [return-type (aget info "returnType")
        value (aget root-value (aget info "fieldName"))
        value (cond
                (fn? value) (value args)
                (true? root-value) true
                (and (object? root-value)
                     (aget root-value "promise?")) true
                :else value)]

    (cond
      (and (instance? (aget js/GraphQL "GraphQLObjectType") return-type)
           (not (object? value))
           value)
      (clj->js {"promise?" true})

      (and (instance? (aget js/GraphQL "GraphQLList") return-type)
           (not (array? value))
           value)
      (js/Array. (clj->js {"promise?" true}))

      :else value)))


(defn- response-remove-seqs [resp]
  (update resp :data (fn [data]
                       (walk/postwalk (fn [x]
                                        (cond
                                          (and (sequential? x)
                                               (map? (first x)))
                                          (first x)
                                          :else x))
                                      data))))


(defn create-resolver-middleware [id {:keys [:root-value :context]}]
  (utils/create-middleware
    id
    (fn [{:keys [:schema :query :variables :gql-name->kw]}]
      (let [context (merge context {:db re-frame.db/app-db})
            query-str (print-str-graphql query)
            variables (clj->js variables)
            res (-> (gql-sync schema
                              query-str
                              (graphql-utils/clj->js-root-value (mask-root-value root-value))
                              context
                              variables
                              nil
                              mask-field-resolver)
                  graphql-utils/js->clj-response
                  response-remove-seqs)
            res-data (:data res)
            new-query (-> query
                        (visit #js {:leave (fn [node key parent path ancestors]
                                             (condp = (aget node "kind")
                                               "OperationDefinition"
                                               (if (and (aget node "selectionSet")
                                                        (not (seq (aget node "selectionSet" "selections"))))
                                                 nil
                                                 js/undefined)

                                               "Field"
                                               (let [q-path
                                                     (utils/ancestors->query-path (concat ancestors [parent node])
                                                                                  {:use-aliases? true
                                                                                   :gql-name->kw gql-name->kw})
                                                     mask-value (get-in res-data q-path)]
                                                 (if (and (seq q-path)
                                                          (or (and (not (nil? mask-value))
                                                                   (not (map? mask-value)))
                                                              (and (aget node "selectionSet")
                                                                   (not (seq (aget node "selectionSet" "selections"))))))
                                                   nil
                                                   js/undefined))

                                               js/undefined))})
                        utils/remove-unused-variable-defs)]

        {:query new-query
         :response (gql schema
                        query-str
                        (graphql-utils/clj->js-root-value root-value)
                        context
                        variables
                        nil)}))))

