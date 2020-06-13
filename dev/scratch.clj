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

