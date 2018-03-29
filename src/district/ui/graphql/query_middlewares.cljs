(ns district.ui.graphql.query-middlewares
  (:require
    [clojure.set :as set]
    [district.graphql-utils :as graphql-utils]
    [district.ui.graphql.utils :as utils]))

(def visit (aget js/GraphQL "visit"))
(def gql-sync (aget js/GraphQL "graphqlSync"))
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


#_(defn- mask-root-value [root-value]
    (if (map? root-value)
      (into {} (map (fn [[k v]]
                      [k (cond
                           (fn? v)
                           (fn [params context schema]
                             (if (nil? (mask-root-value (v params context schema)))
                               nil
                               true))

                           :else
                           (if (nil? v) nil true))])
                    root-value))
      root-value))

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


#_(defn mask-field-resolver [root-value args _ info]
    #_(print.foo/look root-value)
    #_(print.foo/look (aget info "fieldName"))
    (let [return-type (aget info "returnType")
          value (aget root-value (print.foo/look (aget info "fieldName")))
          value (if (fn? value) (value args) value)]
      (cond
        (or (object? value)
            (array? value))
        (print.foo/look value)

        #_(object? value)
        #_(clj->js {})

        #_(array? value)
        #_value

        #_(instance? (aget js/GraphQL "GraphQLList") return-type)
        #_(when value (clj->js [value]))

        :else (if (nil? value) nil true))))


(defn mask-field-resolver [root-value args _ info]
  #_ (print.foo/look root-value)
  #_ (print.foo/look (aget info "fieldName"))
  (let [return-type (aget info "returnType")
        value (aget root-value (aget info "fieldName"))
        value (if (fn? value) (value args) value)]

    (if (and (instance? (aget js/GraphQL "GraphQLList") return-type)
             (not (array? value)))
      (clj->js [])
      value)

    #_(cond
        (or (object? value)
            (array? value))
        (print.foo/look value)

        #_(object? value)
        #_(clj->js {})

        #_(array? value)
        #_value

        #_(instance? (aget js/GraphQL "GraphQLList") return-type)
        #_(when value (clj->js [value]))

        :else (if (nil? value) nil true))))


(defn create-root-value-middleware [{:keys [:id :root-value :context]}]
  (middleware
    id
    (fn [{:keys [:schema :query :variables]}]
      (let [res (gql-sync schema
                          (print-str-graphql query)
                          (graphql-utils/clj->js-root-value (mask-root-value root-value))
                          #_(graphql-utils/clj->js-root-value root-value)
                          context
                          (clj->js variables)
                          nil
                          mask-field-resolver)]

        (print.foo/look (graphql-utils/js->clj-response res)))

      {})))
