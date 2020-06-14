(ns tests.utils
  (:require [cljs.test :refer [deftest is are testing run-tests async use-fixtures]]
            [district.ui.graphql.utils :as utils]))


(deftest remove-empty-typename-paths-test
  (is (= (utils/remove-empty-typename-paths
          {:data {:search-users
                  {:items [{:user/params [{:__typename "Parameter"}], :__typename "User"}],
                   :__typename "UserList",
                   :total-count 0}}})
         {:data {:search-users {:items [], :__typename "UserList", :total-count 0}}})))


(deftest remove-nil-vals
  (are [x y] (= (utils/remove-nil-vals x) y)
    {:test nil :children [nil {:a nil :b true :c "123"}]}
    {:children [nil {:b true :c "123"}]}

    [{:a nil :b []} {:a nil} {}]
    [{:b []} {} {}]

    [{:a '()}]
    [{:a '()}]

    [{:a '({:a nil :b false})}]
    [{:a '({:b false})}]

    [{:a #{}}]
    [{:a #{}}]

    [[[{:a [:a :b nil {:d nil}]}]]]
    [[[{:a [:a :b nil {}]}]]]))


(deftest empty-with-nils?
  (are [x y] (= (utils/empty-with-nils? x) y)
    []
    true

    [nil nil nil]
    true

    [:a :b]
    false

    [:a :b nil]
    false))


(deftest remove-empty-keys
  (are [x y] (= (utils/remove-empty-keys x) y)
    {:a nil}
    {}

    {:a true}
    {:a true}

    {:a []}
    {}

    {:a [nil nil nil]}
    {}

    {:a nil :b [nil] :c [nil nil :d] :e []}
    {:c [nil nil :d]}))


(deftest non-empty-keys
  (are [x y] (= (utils/non-empty-keys x) y)
    {:a nil :__typename "Test"}
    #{:__typename}

    {:a [nil nil nil] :b true :c true :d nil :e []}
    #{:b :c}))

 
(deftest ensure-count
  (are [args result] (= (utils/ensure-count (first args) (second args)) result)

    [[{}] 1]
    [{}]

    [[{}] 2]
    [{} {}]

    [[{}] 0]
    [{}]
    
    [[{:__typename "Test"}] 1]
    [{:__typename "Test"}]

    [[{:__typename "Test"}] 2]
    [{:__typename "Test"} {:__typename "Test"}]

    [[{:__typename "Test"} {}] 2]
    [{:__typename "Test"} {}]

    [[{:__typename "Test"} {}] 3]
    [{:__typename "Test"} {} {:__typename "Test"}]

    [[{:__typename "Test"} {}] 4]
    [{:__typename "Test"} {} {:__typename "Test"} {:__typename "Test"}]))
