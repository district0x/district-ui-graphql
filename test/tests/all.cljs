(ns tests.all
  (:require
    [cljs-time.coerce :as tc]
    [cljs-time.core :as t]
    [cljs.test :refer [deftest is testing run-tests async use-fixtures]]
    [clojure.data]
    [day8.re-frame.test :refer [run-test-async run-test-sync wait-for]]
    [district.cljs-utils :as cljs-utils]
    [district.graphql-utils :as graphql-utils]
    [district.ui.graphql.effects :as effects]
    [district.ui.graphql.events :as events]
    [district.ui.graphql.middleware.resolver :refer [create-resolver-middleware]]
    [district.ui.graphql.queries :as queries]
    [district.ui.graphql.subs :as subs]
    [district.ui.graphql]
    [mount.core :as mount]
    [re-frame.core :refer [reg-fx reg-event-fx subscribe reg-cofx reg-sub dispatch trim-v]]
    [re-frame.db :refer [app-db]]))


(def gql-sync (aget js/GraphQL "graphqlSync"))
(def build-schema (aget js/GraphQL "buildSchema"))
(def parse-graphql (aget js/GraphQL "parse"))


#_(comment
    (def schema "
  scalar Date
  scalar Keyword

  type Query {
    searchUsers(user_status: Keyword): [User]
  }

  type User {
    user_address: String
    user_status: Keyword
    user_registeredOn: Date
    user_active_: Boolean
    user_favoriteNumbers: [Int]
    user_params: [Parameter]
  }

  type Parameter {
    param_db: String
    param_key: Keyword
  }
")
    )


(def schema "
  scalar Date
  scalar Keyword

  type Query {
    searchUsers(user_registeredAfter: Date, some_param: String): UserList
    params(db: String!, keys: [Keyword!]): [Parameter]
  }

  type UserList  {
    items: [User]
    totalCount: Int
  }

  type User {
    user_id: ID
    user_address: String
    user_status: Keyword
    user_registeredOn: Date
    user_active_: Boolean
    user_favoriteNumbers: [Int]
    user_params(param_otherKey: String): [Parameter]
  }

  type Parameter {
    param_id: ID
    param_db: ID
    param_key: Keyword
    param_otherKey: String
    param_creator: User
  }
")

(def response-root-value (atom nil))
(defn set-response! [root-value]
  (reset! response-root-value root-value))

(defn custom-fetch [_ req]
  (js/Promise.
    (fn [resolve]
      (let [{:strs [query variables]} (js->clj (js/JSON.parse (aget req "body")))
            res (gql-sync (-> (build-schema schema)
                            (graphql-utils/add-keyword-type)
                            (graphql-utils/add-date-type))
                          query
                          (graphql-utils/clj->js-root-value @response-root-value)
                          {}
                          (clj->js variables)
                          nil)]
        (let [body (js/JSON.stringify res)]
          (resolve (js/Response. (clj->js body) (clj->js {:status 200}))))))))


(use-fixtures
  :each
  {:after
   (fn []
     (mount/stop))})


(reg-event-fx
  ::refetch-trigger-event
  (constantly nil))

(def mount-args {:schema schema :url "http://localhost:1234/" :fetch-opts {:customFetch custom-fetch}})


(deftest basic-query
  (run-test-async
    (-> (mount/with-args {:graphql mount-args})
      (mount/start))

    (testing "Basic query, resolves values, vectors, scalars properly"
      (set-response! {:search-users (fn [{:keys [:user/registered-after]}]
                                      (when-not (t/equal? registered-after (t/date-time 2018 10 10))
                                        (throw (js/Error. "Pass registered-after is not correct" registered-after)))
                                      {:total-count 1
                                       :items (constantly
                                                [{:user/id 1
                                                  :user/address "Street 1"
                                                  :user/registered-on (t/date-time 2018 05 05)
                                                  :user/status :user.status/active
                                                  :user/favorite-numbers [1 2 3]
                                                  :user/active? true
                                                  :user/params (fn [{:keys [:param/other-key]}]
                                                                 [{:param/id 123
                                                                   :param/db "b"
                                                                   :param/key "key1"
                                                                   :param/other-key other-key}])}])})})

      (let [query1 (subscribe [::subs/query {:queries [[:search-users
                                                        {:user/registered-after (t/date-time 2018 10 10)}
                                                        [:total-count
                                                         [:items [:user/address
                                                                  :user/registered-on
                                                                  :user/status
                                                                  :user/active?
                                                                  :user/favorite-numbers
                                                                  [:user/params {:param/other-key "kek"}
                                                                   [:param/db :param/other-key]]]]]]]}])]

        (wait-for [::events/normalize-response]
          (is (true? (:graphql/loading? @query1)))
          (is (nil? (:graphql/errors @query1)))

          (wait-for [::events/set-query-loading]
            (let [{:keys [:items :total-count]} (:search-users @query1)
                  {:keys [:user/address :user/registered-on :user/status :user/favorite-numbers :user/params :user/active?]}
                  (first items)]
              (is (= 1 (:total-count (:search-users @query1))))
              (is (= address "Street 1"))
              (is (t/equal? registered-on (t/date-time 2018 05 05)))
              (is (= status :user.status/active))
              (is (= favorite-numbers [1 2 3]))
              (is (= params [{:param/db "b", :param/other-key "kek"}]))
              (is (true? active?))

              (is (= "Street 1" (:user/address @(subscribe [::subs/entity :user 1]))))
              (is (= "b" (:param/db @(subscribe [::subs/entity :parameter {:param/id 123 :param/db "b"}])))))))))))


(deftest query-batching
  (run-test-async
    (-> (mount/with-args {:graphql mount-args})
      (mount/start))

    (set-response! {:params (fn [{:keys [:db :keys]}]
                              (for [key keys]
                                {:param/id key
                                 :param/db db
                                 :param/key key
                                 :param/other-key "11"
                                 :param/creator (fn []
                                                  {:user/id 1
                                                   :user/active? true})}))})

    (let [query2 (subscribe [::subs/query
                             {:queries [{:query/data [:params {:db "a" :keys [:abc/key1 :key2]}
                                                      [:param/db
                                                       :param/key
                                                       [:param/creator [:user/active?]]]]
                                         :query/alias :my-params}
                                        [:params {:db "b" :keys [:key3]}
                                         [:param/db :param/other-key]]]}])
          query3 (subscribe [::subs/query
                             {:queries [[:params {:db "b" :keys [:key3]}
                                         [:param/db
                                          {:field/data [:param/key]
                                           :field/alias :param/my-key}]]]}])]

      (wait-for [::events/normalize-response]
        (is (nil? (:graphql/errors @query2)))
        (is (nil? (:graphql/errors @query3)))
        (let [{:keys [:my-params :params]} @query2]

          (is (= my-params [{:param/db "a" :param/key :abc/key1 :param/creator {:user/active? true}}
                            {:param/db "a" :param/key :key2 :param/creator {:user/active? true}}]))
          (is (= params [{:param/db "b" :param/other-key "11"}]))
          (is (= (:params @query3) [{:param/db "b" :param/my-key :key3}]))

          (is (= "a" (:param/db @(subscribe [::subs/entity :parameter {:param/id :abc/key1 :param/db "a"}]))))
          (is (= "a" (:param/db @(subscribe [::subs/entity :parameter {:param/id :key2 :param/db "a"}]))))
          (is (= "b" (:param/db @(subscribe [::subs/entity :parameter {:param/id :key3 :param/db "b"}]))))
          (is (= true (:user/active? @(subscribe [::subs/entity :user 1])))))))))


(deftest query-variables
  (run-test-async
    (-> (mount/with-args {:graphql mount-args})
      (mount/start))

    (set-response! {:search-users (fn [{:keys [:some/param]}]
                                    {:items (constantly
                                              [{:user/id param
                                                :user/address param}])})})

    (testing "Correctly merges queries, even with same variable names"
      (let [query1 (subscribe [::subs/query
                               {:operation {:operation/type :query
                                            :operation/name :some-query}
                                :queries [[:search-users
                                           {:some/param :$a}
                                           [[:items [:user/address]]]]]
                                :variables [{:variable/name :$a
                                             :variable/type :String!}]}
                               {:variables {:a "123456"}}])

            query2 (subscribe [::subs/query
                               {:operation {:operation/type :query
                                            :operation/name :some-other-query}
                                :queries [[:search-users
                                           {:some/param :$a :user/registered-after :$date}
                                           [[:items [:user/address]]]]]
                                :variables [{:variable/name :$a
                                             :variable/type :String!}
                                            {:variable/name :$date
                                             :variable/type :Date}]}
                               {:variables {:a "abcd"
                                            :date (t/date-time 2018 10 10)}}])]

        (wait-for [::events/normalize-response]
          (is (nil? (:graphql/errors @query1)))
          (is (nil? (:graphql/errors @query2)))
          (is (= "123456" (get-in @query1 [:search-users :items 0 :user/address])))
          (is (= "abcd" (get-in @query2 [:search-users :items 0 :user/address])))
          (is (= "123456" (:user/address @(subscribe [::subs/entity :user "123456"]))))
          (is (= "abcd" (:user/address @(subscribe [::subs/entity :user "abcd"])))))))))


(deftest query-fragments
  (run-test-async
    (-> (mount/with-args {:graphql mount-args})
      (mount/start))

    (set-response! {:search-users (fn [{:keys [:some/param]}]
                                    {:items (constantly
                                              [{:user/id param
                                                :user/status :user.status/blocked
                                                :user/address param
                                                :user/active? true
                                                :user/favorite-numbers [1 2]
                                                :user/params (fn [{:keys [:param/other-key]}]
                                                               [{:param/id "key1"
                                                                 :param/db "b"
                                                                 :param/key "key1"
                                                                 :param/other-key other-key}])}])})})

    (let [query1 (subscribe [::subs/query
                             {:operation {:operation/type :query
                                          :operation/name :some-query}
                              :queries [[:search-users
                                         {:some/param "123"}
                                         [[:items [[:fragment/basic-user-fields :fragment/more-user-fields]
                                                   :user/favorite-numbers]]]]
                                        {:query/data [:search-users
                                                      {:some/param "456"}
                                                      [[:items [:user/status
                                                                :user/active?
                                                                [:fragment/basic-user-fields]]]]]
                                         :query/alias :other-users}]
                              :fragments [{:fragment/name :fragment/basic-user-fields
                                           :fragment/type :User
                                           :fragment/fields [:user/address :user/active?]}
                                          {:fragment/name :fragment/more-user-fields
                                           :fragment/type :User
                                           :fragment/fields [[:fragment/inner-fragment]
                                                             [:user/params {:param/other-key :$key}
                                                              [:param/db :param/other-key]]]}
                                          {:fragment/name :fragment/inner-fragment
                                           :fragment/type :User
                                           :fragment/fields [:user/status]}]
                              :variables [{:variable/name :$key
                                           :variable/type :String!}]}
                             {:variables {:key "kek"}}])]


      (wait-for [::events/normalize-response]
        (is (nil? (:graphql/errors @query1)))
        (let [{:keys [:search-users :other-users]} @query1]
          (is (= search-users {:items
                               [{:user/address "123"
                                 :user/active? true
                                 :user/status :user.status/blocked
                                 :user/favorite-numbers [1 2]
                                 :user/params [{:param/db "b"
                                                :param/other-key "kek"}]}]}))
          (is (= other-users {:items
                              [{:user/status :user.status/blocked
                                :user/active? true
                                :user/address "456"}]}))

          (is (= "123" (:user/address @(subscribe [::subs/entity :user "123"]))))
          (is (= "456" (:user/address @(subscribe [::subs/entity :user "456"]))))
          (is (= "b" (:param/db @(subscribe [::subs/entity :parameter {:param/id "key1" :param/db "b"}])))))))))


(deftest query-refetching
  (run-test-async
    (let [total-count (atom 0)]
      (-> (mount/with-args {:graphql mount-args})
        (mount/start))

      (set-response! {:search-users (fn []
                                      (swap! total-count inc)
                                      {:total-count @total-count})})

      (let [query1 (subscribe [::subs/query
                               {:queries [[:search-users {:some/param "a"}
                                           [:total-count]]]}
                               {:refetch-on #{::refetch-trigger-event}}])]

        (wait-for [::events/normalize-response]
          (is (nil? (:graphql/errors @query1)))
          (is (= {:total-count 1} (:search-users @query1)))

          (dispatch [::refetch-trigger-event])

          (wait-for [::events/normalize-response]
            (is (= {:total-count 2} (:search-users @query1)))))))))


(deftest resolver-middleware
  (run-test-async
    (let [middleware1 {:root-value
                       {:search-users
                        (fn [{:keys [:user/registered-after]}]
                          (when-not (t/equal? registered-after (t/date-time 2018 10 10))
                            (throw (js/Error. "Pass registered-after is not correct" registered-after)))
                          {:total-count 2
                           :items (constantly
                                    [{:user/id (fn []
                                                 (js/Promise. (fn [resolve]
                                                                (resolve 2))))
                                      :user/address "Street 2"
                                      :user/active? false
                                      :user/status (fn []
                                                     (js/Promise. (fn [resolve]
                                                                    (resolve :user.status/active))))
                                      :user/registered-on (constantly (t/date-time 2018 9 9))
                                      :user/favorite-numbers (fn []
                                                               (js/Promise. (fn [resolve]
                                                                              (resolve [9 8 7]))))
                                      :user/params (fn [{:keys [:param/other-key]}]
                                                     (js/Promise. (fn [resolve]
                                                                    (resolve [{:param/id 456
                                                                               :param/db "d"
                                                                               :param/other-key "77"}]))))}])})}}

          middleware2 {:root-value
                       {:params (fn [{:keys [:db :keys]}]
                                  (js/Promise. (fn [resolve]
                                                 (resolve
                                                   (for [key keys]
                                                     {:param/id (fn []
                                                                  (js/Promise. (fn [resolve]
                                                                                 (resolve key))))
                                                      :param/db db
                                                      :param/key (constantly key)
                                                      :param/other-key (fn []
                                                                         (js/Promise. (fn [resolve]
                                                                                        (resolve "99"))))
                                                      :param/creator (fn []
                                                                       (js/Promise. (fn [resolve]
                                                                                      (resolve
                                                                                        {:user/id 2
                                                                                         :user/active? false}))))})))))}}]
      (-> (mount/with-args {:graphql (merge mount-args
                                            {:query-middlewares [(create-resolver-middleware :middleware1 middleware1)
                                                                 (create-resolver-middleware :middleware2 middleware2)]})})
        (mount/start)))

    (set-response! {:search-users (fn [{:keys [:user/registered-after]}]
                                    {:total-count 1
                                     :items (constantly
                                              [{:user/id 1
                                                :user/address "Street 1"
                                                :user/registered-on (t/date-time 2018 5 5)
                                                :user/status :user.status/blocked
                                                :user/favorite-numbers [1 2 3]
                                                :user/active? true
                                                :user/params (fn [{:keys [:param/other-key]}]
                                                               [{:param/id 123
                                                                 :param/db "b"
                                                                 :param/key "key1"
                                                                 :param/other-key other-key}])}])})
                    :params (fn [{:keys [:db :keys]}]
                              (for [key keys]
                                {:param/id key
                                 :param/db db
                                 :param/key key
                                 :param/other-key "11"
                                 :param/creator (fn []
                                                  {:user/id 1
                                                   :user/active? true})}))})

    (let [query1 (subscribe [::subs/query {:queries [[:search-users
                                                      {:user/registered-after (t/date-time 2018 10 10)}
                                                      [:total-count
                                                       [:items [:user/address
                                                                :user/registered-on
                                                                :user/status
                                                                :user/active?
                                                                :user/favorite-numbers
                                                                [:user/params {:param/other-key "kek"}
                                                                 [:param/db :param/other-key]]]]]]]}])

          query2 (subscribe [::subs/query
                             {:operation {:operation/type :query
                                          :operation/name :some-query}
                              :queries [{:query/data [:params {:db :$a :keys [:abc/key1 :key2]}
                                                      [:param/db
                                                       :param/key
                                                       [:param/creator [:user/active?]]]]
                                         :query/alias :my-params}
                                        [:params {:db :$b :keys [:key3]}
                                         [:param/db :param/other-key]]]
                              :variables [{:variable/name :$a
                                           :variable/type :String!}
                                          {:variable/name :$b
                                           :variable/type :String!}]}
                             {:variables {:a "a" :b "b"}}])]

      (wait-for [::events/normalize-response]
        (is (nil? (:graphql/errors @query1)))
        (is (nil? (:graphql/errors @query2)))

        (print.foo/look @(subscribe [::subs/entities]))
        (print.foo/look @(subscribe [::subs/graph]))

        (print.foo/look @query1)
        (print.foo/look @query2)

        (let [{:keys [:items :total-count]} (:search-users @query1)
              {:keys [:user/address :user/registered-on :user/status :user/favorite-numbers :user/params :user/active?]}
              (first items)]
          (is (= 2 (:total-count (:search-users @query1))))
          (is (= address "Street 2"))
          (is (t/equal? registered-on (t/date-time 2018 9 9)))
          (is (= status :user.status/active))
          (is (= favorite-numbers [9 8 7]))
          (is (= params [{:param/db "d", :param/other-key "77"}]))
          (is (false? active?))

          (is (= "Street 2" (:user/address @(subscribe [::subs/entity :user 2]))))
          (is (= "d" (:param/db @(subscribe [::subs/entity :parameter {:param/id 456 :param/db "d"}])))))

        (let [{:keys [:my-params :params]} @query2]

          (is (= my-params [{:param/db "a" :param/key :abc/key1 :param/creator {:user/active? false}}
                            {:param/db "a" :param/key :key2 :param/creator {:user/active? false}}]))
          (is (= params [{:param/db "b" :param/other-key "99"}]))

          (is (= "a" (:param/db @(subscribe [::subs/entity :parameter {:param/id :abc/key1 :param/db "a"}]))))
          (is (= "a" (:param/db @(subscribe [::subs/entity :parameter {:param/id :key2 :param/db "a"}]))))
          (is (= "b" (:param/db @(subscribe [::subs/entity :parameter {:param/id :key3 :param/db "b"}]))))
          (is (= false (:user/active? @(subscribe [::subs/entity :user 2])))))))))
