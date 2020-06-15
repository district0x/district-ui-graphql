(ns scratch
  (:require
   [com.rpl.specter :as $ :refer [select transform setval]]
   [clojure.walk :as walk]))


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

(def data2
  {:search-users
   {:items
    [{:user/params
      [{:__typename "Parameter",
        :param/db "b",
        :param/other-key "kek",
        :param/id "key1"}],
      :__typename "User",
      :user/address "123",
      :user/active? true,
      :user/id "123",
      :user/status "user.status/blocked",
      :user/favorite-numbers [1 2]}],
    :__typename "UserList"},
   :other-users
   {:items
    [{:__typename "User",
      :user/status "user.status/blocked",
      :user/active? true,
      :user/address "456", 
      :user/id "456"}], 
    :__typename "UserList"}})


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
(select [PATHWALKER map?] data2)
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
(transform [PATHWALKER map?] (partial tag-max-depth 2) data2)

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
   [{:a [{} {} {:a :value}]}]
   [{:a [{} {} {:b :value2}]}])


;; Right
{:search-users
 {{:user/registered-after 1539129600000, :user/age 100000000000}
  {:items
   [{:user/registered-on 1525478400000,
     :__typename "User",
     :user/favorite-numbers [1 2 3],
     :user/id "1",
     :user/active? true,
     :user/status "user.status/active",
     :user/params
     {{:param/other-key "kek"}
      [{:__typename "Parameter",
        :param/db "b",
        :param/other-key "kek",
        :param/id "123"}]},
     :user/age 100000000000,
     :user/address "Street 1"}],
   :__typename "UserList", 
   :total-count 1}}}

;; Wrong
{:search-users
 {:items
  [{:user/registered-on 1525478400000,
    :__typename "User",
    :user/favorite-numbers [1 2 3],
    :user/id "1",
    :user/active? true,
    :user/status "user.status/active",
    :user/params
    [{:__typename "Parameter",
      :param/db "b",
      :param/other-key "kek",
      :param/id "123"}],
    :user/age 100000000000,
    :user/address "Street 1"}], 
  :__typename "UserList", 
  :total-count 1}}
