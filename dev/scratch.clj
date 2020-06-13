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


(def NIL-VALS
  ($/recursive-path
   [] path
   ($/cond-path
    map? [$/MAP-VALS ($/if-path nil? $/STAY path)]
    coll? [$/ALL path])))


(def INDEXED
  "A path that visits v and collects k in [[k v], ...]."
  [$/ALL ($/collect-one $/FIRST) $/LAST])


(def INDEXED-SEQ
  "A selector that visits all elements of a seq, and collects their indices."
  [($/view #(map-indexed vector %)) INDEXED])


(def PathWalker
  ($/recursive-path
   [] p
   ($/cond-path
    map?     [INDEXED p]
    vector?  [INDEXED-SEQ p]
    $/STAY   $/STAY)))


(setval NIL-VALS $/NONE data)


(select [PathWalker] data)

(transform [PathWalker]
  (fn [& args]
   (let [value (last args)
         path (butlast args)]
     (if (number? value)
       (inc value))))
  data)


(def walker
  "Navigate the data structure until reaching a value for which `afn`
  returns truthy. Has same semantics as clojure.walk."
  ($/recursive-path
   [afn] path
   ($/cond-path
    ($/pred afn) $/STAY
    coll?        [$/ALL path])))
               
