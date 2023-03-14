(ns district.ui.graphql.middleware.typenames
  (:require
    [district.ui.graphql.utils :as utils]
    ["graphql" :as GraphQL]))


(def visit (aget GraphQL "visit"))
(def gql-sync (aget GraphQL "graphqlSync"))
(def print-str-graphql (aget GraphQL "print"))


(defn- field-resolver [root-value args context info]
  (let [return-type (aget info "returnType")]
    (cond
      (and (instance? (aget GraphQL "GraphQLObjectType") return-type))
      (js/Object.)

      (and (instance? (aget GraphQL "GraphQLList") return-type)
           (instance? (aget GraphQL "GraphQLObjectType") (aget return-type "ofType")))
      ;; TODO QUESTION How do I know here if I'll be resolving to empty or not
      ;; since we don't know what resolver will apply
      (js/Array. (js/Object.))

      :else nil)))


(defn typenames-middleware [{:keys [:query :schema :variables :kw->gql-name]}]
  (let [typename-field (clj->js (utils/create-field-node "__typename"))
        typenames-query (visit query
                               #js {:leave (fn [node key parent path ancestors]
                                             (condp = (aget node "kind")
                                               "Field"
                                               (if (and (not (contains? #{"OperationDefinition" "FragmentDefinition"}
                                                                        (aget parent "kind")))
                                                        (and (aget node "selectionSet")
                                                             (seq (aget node "selectionSet" "selections"))))
                                                 (do
                                                   (let [node (clj->js (js->clj node))] ;; deep clone ¯\_(ツ)_/¯
                                                     (.push (aget node "selectionSet" "selections") typename-field)
                                                     node))
                                                 js/undefined)
                                               js/undefined))})]

    {:response (-> (gql-sync #js {:schema (utils/ignore-null schema)
                                  :source (print-str-graphql typenames-query)
                                  :variableValues (clj->js variables)
                                  :fieldResolver field-resolver}))}))


