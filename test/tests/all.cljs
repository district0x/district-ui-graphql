(ns tests.all
  (:require
    [cljs-time.coerce :as tc]
    [cljs-time.core :as t]
    [cljs.test :refer [deftest is testing run-tests async use-fixtures]]
    [day8.re-frame.test :refer [run-test-async run-test-sync wait-for]]
    [district.graphql-utils :as graphql-utils]
    [district.ui.graphql.effects :as effects]
    [district.ui.graphql.events :as events]
    [district.ui.graphql.queries :as queries]
    [district.ui.graphql.query-middlewares :refer [create-root-value-middleware]]
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
    (-> (mount/with-args {:graphql (merge mount-args
                                          {:query-middlewares [(create-root-value-middleware
                                                                 {:id :some-mid
                                                                  :root-value {:search-users
                                                                               (fn [{:keys [:user/registered-after]}]
                                                                                 {:total-count 4
                                                                                  :items (constantly
                                                                                           #_(js/Promise. (fn [resolve]
                                                                                                            (resolve 1)))
                                                                                           [{:user/id (fn []
                                                                                                        (js/Promise. (fn [resolve]
                                                                                                                       (resolve 1))))
                                                                                             :user/favorite-numbers (fn []
                                                                                                                      (js/Promise. (fn [resolve]
                                                                                                                                     (resolve 1))))}])})}})]})})
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
    (-> (mount/with-args {:graphql (merge mount-args
                                          {:query-middlewares
                                           [(create-root-value-middleware
                                              {:id :some-mid
                                               :root-value {:params (fn [{:keys [:db :keys]}]
                                                                      (for [key keys]
                                                                        {:param/id key
                                                                         :param/db db
                                                                         :param/key key
                                                                         :param/other-key "11"
                                                                         :param/creator (fn []
                                                                                          (js/Promise. (fn [resolve]
                                                                                                         (resolve 1)))
                                                                                          )}))}})]})})
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
    (-> (mount/with-args {:graphql (merge mount-args
                                          {:query-middlewares
                                           [(create-root-value-middleware
                                              {:id :some-mid
                                               :root-value {:search-users (fn [{:keys [:some/param]}]
                                                                            {:items (constantly
                                                                                      #_(js/Promise. (fn [resolve]
                                                                                                       (resolve 1)))

                                                                                      [{:user/id param}])})}})]})})
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

          (is (= "123456" (get-in @query1 [:search-users :items 0 :user/address])))
          (is (= "abcd" (get-in @query2 [:search-users :items 0 :user/address])))
          (is (= "123456" (:user/address @(subscribe [::subs/entity :user "123456"]))))
          (is (= "abcd" (:user/address @(subscribe [::subs/entity :user "abcd"])))))))))


#_(deftest query-fragments
    (run-test-async
      (-> (mount/with-args {:graphql (merge mount-args
                                            {:query-middlewares
                                             [(create-root-value-middleware
                                                {:id :some-mid
                                                 :root-value {:search-users (fn [{:keys [:some/param]}]
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
                                                                                                           :param/other-key other-key}])}])})}})]})})
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
                                             :fragment/fields [:user/status
                                                               [:user/params {:param/other-key :$key}
                                                                [:param/db :param/other-key]]]}]
                                :variables [{:variable/name :$key
                                             :variable/type :String!}]}
                               {:variables {:key "kek"}}])]


        (wait-for [::events/normalize-response]
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


#_(deftest query-refetching
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
            (is (= {:total-count 1} (:search-users @query1)))

            (dispatch [::refetch-trigger-event])

            (wait-for [::events/normalize-response]
              (is (= {:total-count 2} (:search-users @query1)))))))))
