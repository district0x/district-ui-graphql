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


