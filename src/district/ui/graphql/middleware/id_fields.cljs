(ns district.ui.graphql.middleware.id-fields
  (:require
    [clojure.set :as set]
    [district.ui.graphql.utils :as utils]))

(def visit (aget js/GraphQL "visit"))

(defn id-fields-middleware [{:keys [:query :schema]}]
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