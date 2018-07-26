(ns tests.utils
  (:require [cljs.test :refer [deftest is testing run-tests async use-fixtures]]
            [district.ui.graphql.utils :as utils]))


(deftest remove-empty-typename-paths-test
  (is (= (utils/remove-empty-typename-paths
          {:data {:search-users
                  {:items [{:user/params [{:__typename "Parameter"}], :__typename "User"}],
                   :__typename "UserList",
                   :total-count 0}}})
         {:data {:search-users {:items [], :__typename "UserList", :total-count 0}}})))


