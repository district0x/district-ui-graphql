(ns scratch
  (:require
   [com.rpl.specter :as $ :refer [select transform setval]]
   [clojure.walk :as walk]
   [clojure.pprint :refer [pprint]]))


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


#_(select [0 :type] data)


(def MAP-NIL-VALS
  ($/recursive-path
   [] path
   ($/cond-path
    map? [$/MAP-VALS ($/if-path nil? $/STAY path)]
    coll? [$/ALL path])))

#_(setval MAP-NIL-VALS $/NONE data)
#_(setval MAP-NIL-VALS $/NONE {:test nil :children [nil {:a nil :b true :c "123"}]})

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


#_(select [PATHWALKER map?] data)
#_(select [PATHWALKER map?] data2)
#_(select [PATHWALKER map?] [{:a true} 123 true])
#_(println (select PATHWALKER [{:a {:b {:c [1 2 3]}}}]))
#_(println (select [PATHWALKER map?] [{:test? true} 123 true [1 2 3] {:children [1 2 {:foo :bar}]}]))


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
{:params
 {{:db "a", :keys ["abc/key1" "key2"]}
  [{:param/creator
    {:__typename "User", :user/active? true, :user/id "1"},
    :__typename "Parameter",
    :param/db "a",
    :param/key "abc/key1",
    :param/id ":abc/key1"}
   {:__typename "Parameter",
    :param/db "a",
    :param/key "key2",
    :param/creator {:user/active? true, :user/id "1"},
    :param/id ":key2"}],
  {:db "b", :keys ["key3"]}
  [{:__typename "Parameter",
    :param/db "b",
    :param/other-key "11", 
    :param/id ":key3", 
    :param/key "key3"}]}}

;; Wrong
{:params
 {{:db "b", :keys ["key3"]}
  [{:__typename "Parameter",
    :param/db "b",
    :param/other-key "11",
    :param/id ":key3",
    :__alias {:args {:db "b", :keys ["key3"]}, :name nil}}
   {:__typename "Parameter",
    :param/db "b",
    :param/my-key "key3",
    :param/id ":key3",
    :__alias {:args {:db "b", :keys ["key3"]}, :name :params}}],
  {:db "a", :keys ["abc/key1" "key2"]}
  [{:param/creator
    {:__typename "User", :user/active? true, :user/id "1"},
    :__typename "Parameter",
    :param/db "a",
    :param/key "abc/key1",
    :param/id ":abc/key1",
    :__alias
    {:args {:db "a", :keys ["abc/key1" "key2"]}, :name :params}}
   {:__typename "Parameter",
    :param/db "a",
    :param/key "key2",
    :param/creator {:user/active? true, :user/id "1"},
    :param/id ":key2",
    :__alias
    {:args {:db "a", :keys ["abc/key1" "key2"]}, :name :params}}]}}


(def map-with-key-walker
  "Recursively post walk the tree, and stay at map nodes that contain the key `k`."
  ($/recursive-path
   [k] path
   ($/cond-path
    #(and (map? %) (contains? % k)) ($/continue-then-stay [$/MAP-VALS path])
    map?                            [$/MAP-VALS path]
    coll?                           [$/ALL path])))


(def ALIAS-WALKER (map-with-key-walker :__alias))


(def test-data-alias [{:__alias true}
                      {:__alias true
                       :children [{:__alias true}]}])

#_(select ALIAS-WALKER test-data-alias)

#_(transform ALIAS-WALKER (fn [node] {(:__alias node) node}) test-data-alias)


(def MAP-PATHWALKER
  "Walks over the data structure, producing a navigation path of
  collected values of each value traversed.

  # Notes

  - Sequences are ignored when producing a path. This is optimal for our given scenarios.
  "
  ($/recursive-path
   [] path
   ($/cond-path
    map?           ($/continue-then-stay [INDEXED path])
    coll?          [$/ALL path])))


#_(transform MAP-PATHWALKER
             (fn [& args]
               (let [node (last args)]
                 (assoc node :node-visited true)))
             data2)


(def MALFORMED-ALIAS
  "Navigates through the alias replacements that can be merged."
  ($/recursive-path
   [] path
   ($/cond-path
    #(and (coll? %)
          (seq %)
          (-> % first map?)
          (-> % first :__typename not))
    ($/continue-then-stay [$/ALL path])
    map?   [$/MAP-VALS path]
    coll?  [$/ALL path])))


(def wrong-data
  {:my-params
   {{:db "a", :keys ["abc/key1" "key2"]}
    [{:param/creator
      {:__typename "User", :user/active? true, :user/id "1"},
      :__typename "Parameter",
      :param/db "a",
      :param/key "abc/key1",
      :param/id ":abc/key1",
      :__alias
      {:args {:db "a", :keys ["abc/key1" "key2"]}, :name :params}}
     {:__typename "Parameter",
      :param/db "a",
      :param/key "key2",
      :param/creator {:user/active? true, :user/id "1"},
      :param/id ":key2",
      :__alias
      {:args {:db "a", :keys ["abc/key1" "key2"]}, :name :params}}]},
   :params
   {{:db "b", :keys ["key3"]}
    [{:__typename "Parameter",
      :param/db "b",
      :param/other-key "11",
      :param/id ":key3",
      :__alias {:args {:db "b", :keys ["key3"]}, :name nil}}]},
   :params/a1410909801
   {{:db "b", :keys ["key3"]}
    [{:__typename "Parameter",
      :param/db "b",
      :param/my-key "key3",
      :param/id ":key3",
      :__alias {:args {:db "b", :keys ["key3"]}, :name :params}}]}})


#_(pprint (->> wrong-data vals (apply merge-in-colls)))


(defn graphql-alias-map?
  [m]
  (and (map? m)
       (contains? m :__alias)
       (-> m :__alias :name)))


(def ALIASED-PATHWALKER
  [PATHWALKER graphql-alias-map?])


#_(pprint (select ALIASED-PATHWALKER wrong-data))


(defn construct-path-alias-playbook
  "Constructing a play book of deltas to perform on the original structure.

   :old-path key is the original path.

   :new-path key is the new path

   :node is the element that needs to be moved to the new-path from the old-path.
   "
  [args]
  (let [node (last args)
        path (butlast args)
        alias-name (-> node :__alias :name)]
    {:node node
     :old-path path
     :new-path (concat [alias-name] (rest path))}))


(defn apply-path-alias-playbook
  [data {:keys [node old-path new-path]}]
  (let [new-path (-> new-path butlast vec) ;; remove the vec index
        result
        (-> data
            (update-in new-path vec) ;; Make sure path exists
            (update-in new-path conj node)
          
            ;; TODO: Remove old path
            )]
    result))


(defn resolve-alias-data
  ""
  [data]
  (->> data
       (select ALIASED-PATHWALKER)
       (map construct-path-alias-playbook)
       (reduce apply-path-alias-playbook data)))


#_(pprint (resolve-alias-data wrong-data))
