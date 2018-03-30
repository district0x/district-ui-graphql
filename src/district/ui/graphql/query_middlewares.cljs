(ns district.ui.graphql.query-middlewares
  (:require
    [clojure.set :as set]
    [district.graphql-utils :as graphql-utils]
    [district.ui.graphql.utils :as utils]
    [clojure.walk :as walk]
    [district.cljs-utils :as cljs-utils]
    [clojure.string :as string]))

(def visit (aget js/GraphQL "visit"))
(def gql-sync (aget js/GraphQL "graphqlSync"))
(def gql (aget js/GraphQL "graphql"))
(def print-str-graphql (aget js/GraphQL "print"))

(defn middleware [id middleware-fn]
  {:id id
   :fn middleware-fn})

(defn add-id-fields-middleware [{:keys [:query :schema]}]
  {:query
   (visit query
          #js {:enter (fn [node key parent path ancestors]
                        (when (and (= key "selectionSet")
                                   (pos? (count ancestors)))
                          (when-let [id-field-names (utils/get-id-fields-names schema (concat ancestors [parent]))]
                            (let [selection-names (set (map #(aget % "name" "value") (aget node "selections")))
                                  field-names-to-add (set/difference id-field-names selection-names)]

                              (when (seq selection-names)
                                (doseq [field-name field-names-to-add]
                                  (.push (aget node "selections") (clj->js (utils/create-field-node field-name)))))
                              node)))
                        js/undefined)})})


(defn- selection-set-has-field? [SelectionSet Field]
  (.some (aget SelectionSet "selections")
         (fn [Selection]
           (and (= (aget Selection "kind") "Field")
                (= (aget Selection "name" "value")
                   (aget Field "name" "value"))))))


(defn add-fields-middleware [fields {:keys [:query]}]
  (let [fields (map (fn [field]
                      (clj->js (if (string? field)
                                 (utils/create-field-node field)
                                 field)))
                    fields)]
    {:query
     (visit query
            #js {:enter (fn [node key parent path ancestors]
                          (if (= key "selectionSet")
                            (do
                              (when (not (contains? #{"OperationDefinition" "FragmentDefinition"}
                                                    (aget parent "kind")))
                                (doseq [field fields]
                                  (when (not (selection-set-has-field? node field))
                                    (.push (aget node "selections") field))))
                              node)
                            js/undefined))})}))


(defn create-add-fields-middleware [id fields]
  (middleware id (partial add-fields-middleware fields)))


(defn mask-value [value]
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


(defn mask-field-resolver [root-value args context info]
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

(defn clj->js-root-value [root-value & [opts]]
  (let [gql-name->kw (or (:gql-name->kw opts) graphql-utils/gql-name->kw)
        kw->gql-name (or (:kw->gql-name opts) graphql-utils/kw->gql-name)]

    (cond
      (map? root-value)
      (clj->js (into {} (map (fn [[k v]]
                               [(kw->gql-name k)
                                (cond
                                  (fn? v)
                                  (fn [params context schema]
                                    (let [parsed-params (cljs-utils/transform-keys gql-name->kw (js->clj params))
                                          result (clj->js-root-value (v parsed-params context schema))]
                                      result))

                                  :else v)])
                             root-value))
               :keyword-fn identity)

      (sequential? root-value)
      (clj->js (map clj->js-root-value root-value) :keyword-fn identity)

      (instance? js/Promise root-value)
      (.then root-value clj->js-root-value)

      :else root-value)))


(defn create-resolver-middleware [{:keys [:id :root-value :context]}]
  (middleware
    id
    (fn [{:keys [:schema :query :variables :gql-name->kw]}]
      (let [query-str (print-str-graphql query)
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
            res-data (print.foo/look (:data res))
            new-query (visit query #js {:leave (fn [node key parent path ancestors]
                                                 (condp = (aget node "kind")
                                                   "Field"
                                                   (let [path
                                                         (utils/ancestors->query-path (concat ancestors [parent node])
                                                                                      {:use-aliases? true
                                                                                       :gql-name->kw gql-name->kw})
                                                         mask-value (get-in res-data path)]
                                                     (if (and (seq path)
                                                              (or (and (not (nil? mask-value))
                                                                       (not (map? mask-value)))
                                                                  (and (aget node "selectionSet")
                                                                       (not (seq (aget node "selectionSet" "selections"))))))
                                                       nil
                                                       js/undefined))
                                                   js/undefined))})
            new-query-str (print-str-graphql new-query)]


        #_(print.foo/look query-clj)
        #_(println res)
        #_(println query-str)

        (println "nq" query-str)
        #_(when (not= new-query-str query-str)
            (println "HAA!"))


        {:query new-query
         :response (gql schema
                        query-str
                        (clj->js-root-value root-value)
                        context
                        variables
                        nil)}))))

