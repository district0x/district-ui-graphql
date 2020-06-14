(ns scratch
  (:require
   [com.rpl.specter :as $ :refer [select transform setval]]))


(def data
  [{:type "data"
    :name nil
    :timestamp 12321512
    :other [{:empty nil :type :empty}]}
   {:type "data"
    :name "ben"
    :timestamp 12412321}
   {:type "data"
    :name nil
    :timestamp 2234532}])


(select [0 :type] data)


(def MAP-NIL-VALS
  ($/recursive-path
   [] path
   ($/cond-path
    map? [$/MAP-VALS ($/if-path nil? $/STAY path)]
    coll? [$/ALL path])))

(setval MAP-NIL-VALS $/NONE data)
(setval MAP-NIL-VALS $/NONE {:test nil :children [nil {:a nil :b true :c "123"}]})

(def INDEXED
  "A path that visits v and collects k in [[k v], ...]."
  [$/ALL ($/collect-one $/FIRST) $/LAST])


(def INDEXED-SEQ
  "A selector that visits all elements of a seq, and collects their indices."
  [($/view #(map-indexed vector %)) INDEXED])


(def PATHWALKER
  ($/recursive-path
   [] path
   ($/cond-path
    map?           ($/stay-then-continue [INDEXED path])
    vector?        ($/stay-then-continue [INDEXED-SEQ path])
    $/STAY         $/STAY)))


(select [PATHWALKER map?] data)
(select [PATHWALKER map?] [{:a true} 123 true])
(println (select PATHWALKER [{:a {:b {:c [1 2 3]}}}]))
(println (select [PATHWALKER map?] [{:test? true} 123 true [1 2 3] {:children [1 2 {:foo :bar}]}]))


(defn tag-max-depth [max-depth & args]
  (let [node (last args)
        path (butlast args)
        path-depth (-> path count inc)]
    (if (>= path-depth max-depth) (assoc node :deep true) node)))


(def test-data {:children [{:children [{:children [{:children []}]}]}]})
(select [PATHWALKER map?] test-data)
(transform [PATHWALKER map?] (partial tag-max-depth 2) test-data)

(transform [PATHWALKER #(and (map? %) (contains? % :timestamp))]
  (fn [& args]
    (let [node (last args)
          path  (butlast args)]
      (update node :timestamp inc)))
  data)


(def walker
  "Navigate the data structure until reaching a value for which `afn`
  returns truthy. Has same semantics as clojure.walk."
  ($/recursive-path
   [afn] path
   ($/cond-path
    ($/pred afn) $/STAY
    coll?        [$/ALL path])))


(defn non-empty-keys
  "Given a map return the set of empty keys.
  All this are considered empty keys
  {:a nil
   :b []
   :c [nil nil nil]}"
  [m]
  (set (keep (fn [[k v]]
               (when-not (or (nil? v)
                             (and (sequential? v)
                                  (empty? (remove nil? v))))
                 k))
             m)))

#_(non-empty-keys
   {:a nil
    :b []
    :c [nil nil nil]
    :__typename "test"})


(defn empty-with-nils?
  "Is the sequence empty if you remove the nil elements?"
  [coll]
  (->> coll (remove nil?) empty?))


(defn remove-empty-keys
  "Removes all keys from the map if:

   - the value is nil

   - the value is a sequential collection, and is empty if you remove the nil elements."
  [m]
  (setval [$/MAP-VALS ($/if-path sequential? empty-with-nils? nil?)] $/NONE m))


(defn non-empty-keys
  "Retrieve the list of keys with values that are 'non-empty'.
  
  # Notes
  
  - Please refer to `remove-empty-keys`."
  [m]
  (-> m remove-empty-keys keys set))


#_(remove-empty-keys {:a nil
                      :b []
                      :c [nil nil nil]
                      :__typename "test"})


(defn- ensure-count
  "Ensures the sequence of map values `map-seq` has `n` values by
  repeating the first map sequence element to satisfy `n` elements in
  the sequence.

  # Notes

  - Preserves the key `:__typename` in the first element when repeating"
  [map-seq n]
  (let [num-offset (max (- n (count map-seq)) 0)
        repeat-value (if-let [typename (-> map-seq first :__typename)]
                       {:__typename typename}
                       {})
        repeat-seq (repeat num-offset repeat-value)]
    ;; Append `repeat-seq` to the end of the `map-seq`
    (setval [$/END] map-seq repeat-seq)))


#_(ensure-count [{:a 123 :__typename "Category"} {}] 4)


(letfn [(merge-in-colls* [xs ys]
          (cond
            (map? xs)
            (merge-with merge-in-colls* xs ys)

            (and (sequential? xs)
                 (sequential? ys)
                 (or (map? (first xs))
                     (map? (first ys))))
            (let [num-elements (max (count xs) (count ys))]
              (mapv (partial reduce merge-in-colls*)
                    (partition 2 (interleave (ensure-count xs num-elements) (ensure-count ys num-elements)))))

            :else ys))]
  (defn merge-in-colls
    "Merge multiple nested maps. Merges maps in collections as well"
    [& args]
    (reduce merge-in-colls* nil args)))

#_(merge-in-colls
   [{:a [{} {} {}]}]
   [{:a [{} {} {}]}]
   [{:a [{} {} {}]}])
