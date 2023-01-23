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

(def print-schema-graphql (aget js/GraphQL "printSchema"))

(def schema-str "
  type Query {
    users: UserList!
  }

  type UserList {
    items: [User]!
    totalCount: Int!
  }

  type User {
    user_id: ID!
    user_address: String!
    user_favoriteNumbers: [Int]!
  }")

(deftest ignore-null-test
  (let [schema (utils/build-schema schema-str)
        nullable-schema (utils/ignore-null schema)]
    (is (not (clojure.string/includes? (print-schema-graphql nullable-schema) "!")))
    (is (not= (aget schema "_typeMap") (aget nullable-schema "_typeMap")))))
