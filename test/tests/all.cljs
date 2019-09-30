(ns tests.all
  (:require
   [bignumber.core :as bn]
   [cljs-time.core :as t]
   [cljs.test :refer [deftest is testing run-tests async use-fixtures]]
   [day8.re-frame.test :refer [run-test-async run-test-sync wait-for]]
   [district.graphql-utils :as graphql-utils]
   [district.ui.graphql.events :as events]
   [district.ui.graphql.middleware.resolver :refer [create-resolver-middleware]]
   [district.ui.graphql.subs :as subs]
   [district.ui.graphql]
   [mount.core :as mount]
   [re-frame.core :refer [reg-fx reg-event-fx subscribe reg-cofx reg-sub dispatch trim-v]]
   [re-frame.db :refer [app-db]]))


(def gql-sync (aget js/GraphQL "graphqlSync"))
(def build-schema (aget js/GraphQL "buildSchema"))
(def parse-graphql (aget js/GraphQL "parse"))


(def schema "
  scalar Date
  scalar Keyword
  scalar BigNumber

  type Query {
    searchUsers(user_registeredAfter: Date, some_param: String, user_age: BigNumber): UserList
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
    user_age: BigNumber
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
(def response-schema (atom schema))
(defn set-response! [root-value & [schema]]
  (reset! response-root-value root-value)
  (when schema
    (reset! response-schema schema)))

(defn custom-fetch [_ req]
  (js/Promise.
    (fn [resolve]
      (let [{:strs [query variables]} (js->clj (js/JSON.parse (aget req "body")))
            res (gql-sync (-> (build-schema @response-schema)
                            (graphql-utils/add-keyword-type)
                            (graphql-utils/add-date-type)
                            (graphql-utils/add-bignumber-type))
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
      (set-response! {:search-users (fn [{:keys [:user/registered-after :user/age]}]
                                      (when-not (t/equal? registered-after (t/date-time 2018 10 10))
                                        (throw (js/Error. "Pass registered-after is not correct" registered-after)))
                                      (when-not (bn/= age (bn/number "10e10"))
                                        (throw (js/Error. "Pass age is not correct" age)))
                                      {:total-count 1
                                       :items (constantly
                                                [{:user/id 1
                                                  :user/address "Street 1"
                                                  :user/registered-on (t/date-time 2018 05 05)
                                                  :user/age (bn/number "10e10")
                                                  :user/status :user.status/active
                                                  :user/favorite-numbers [1 2 3]
                                                  :user/active? true
                                                  :user/params (fn [{:keys [:param/other-key]}]
                                                                 [{:param/id 123
                                                                   :param/db "b"
                                                                   :param/key "key1"
                                                                   :param/other-key other-key}])}])})})

      (let [query1 (subscribe [::subs/query {:queries [[:search-users
                                                        {:user/registered-after (t/date-time 2018 10 10)
                                                         :user/age (bn/number "10e10")}
                                                        [:total-count
                                                         [:items [:user/address
                                                                  :user/registered-on
                                                                  :user/age
                                                                  :user/status
                                                                  :user/active?
                                                                  :user/favorite-numbers
                                                                  [:user/params {:param/other-key "kek"}
                                                                   [:param/db :param/other-key]]]]]]]}])]
        (is (true? (:graphql/loading? @query1)))
        (is (true? (:graphql/preprocessing? @query1)))
        (wait-for [::events/normalize-response]
          (wait-for [::events/set-query-loading]
            (is (not (:graphql/loading? @query1)))
            (let [{:keys [:items :total-count]} (:search-users @query1)
                  {:keys [:user/address :user/registered-on :user/age :user/status :user/favorite-numbers :user/params :user/active?]}
                  (first items)]
              (is (= 1 (:total-count (:search-users @query1))))
              (is (= address "Street 1"))
              (is (t/equal? registered-on (t/date-time 2018 05 05)))
              (is (bn/= age (bn/number "10e10")))
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
                                         [:param/db :param/other-key]]]}
                             {:consider-preprocessing-as-loading? false}])
          query3 (subscribe [::subs/query
                             {:queries [[:params {:db "b" :keys [:key3]}
                                         [:param/db
                                          {:field/data [:param/key]
                                           :field/alias :param/my-key}]]]}])]

      (is (true? (:graphql/preprocessing? @query2)))
      (is (false? (:graphql/loading? @query2)))
      (is (true? (:graphql/preprocessing? @query3)))
      (is (true? (:graphql/loading? @query3)))
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
                                           {:some/param :$a
                                            :user/registered-after :$date
                                            :user/age :$age}
                                           [[:items [:user/address]]]]]
                                :variables [{:variable/name :$a
                                             :variable/type :String!}
                                            {:variable/name :$date
                                             :variable/type :Date}
                                            {:variable/name :$age
                                             :variable/type :BigNumber}]}
                               {:variables {:a "abcd"
                                            :date (t/date-time 2018 10 10)
                                            :age (bn/number "10e10")}}])]

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
                                      :user/status (fn []
                                                     (js/Promise. (fn [resolve]
                                                                    (resolve :user.status/active))))
                                      :user/registered-on (constantly (t/date-time 2018 9 9))
                                      :user/age (constantly (bn/number "10e10"))
                                      :user/favorite-numbers (fn []
                                                               (js/Promise. (fn [resolve]
                                                                              (resolve [9 8 7]))))
                                      :user/params (fn [{:keys [:param/other-key]}]
                                                     (js/Promise. (fn [resolve]
                                                                    (resolve [{:param/id 456
                                                                               :param/db "d"
                                                                               :param/other-key "77"
                                                                               :param/creator
                                                                               (fn []
                                                                                 (js/Promise. (fn [resolve]
                                                                                                (resolve
                                                                                                  {:user/id 5
                                                                                                   :user/address "Street ABC"}))))}]))))}])})}}

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

    (set-response! {:search-users (fn [{:keys [:user/registered-after :user/age]}]
                                    {:total-count 1
                                     :items (constantly
                                              [{:user/id 1
                                                :user/address "Street 1"
                                                :user/registered-on (t/date-time 2018 5 5)
                                                :user/age (bn/number "10e10")
                                                :user/status :user.status/blocked
                                                :user/favorite-numbers [1 2 3]
                                                :user/active? true
                                                :user/params (fn [{:keys [:param/other-key]}]
                                                               [{:param/id 123
                                                                 :param/db "b"
                                                                 :param/key "key1"
                                                                 :param/other-key other-key
                                                                 :param/creator (fn []
                                                                                  {:user/id 5
                                                                                   :user/address "Street XYZ"})}])}])})
                    :params (fn [{:keys [:db :keys]}]
                              (for [key keys]
                                {:param/id key
                                 :param/db db
                                 :param/key key
                                 :param/other-key "11"
                                 :param/creator (fn []
                                                  {:user/id 1
                                                   :user/active? true})}))})

    (let [query1 (subscribe [::subs/query {:operation {:operation/type :query
                                                       :operation/name :some-query}
                                           :queries [[:search-users
                                                      {:user/registered-after (t/date-time 2018 10 10)
                                                       :user/age (bn/number "10e10")}
                                                      [:total-count
                                                       [:items [:user/address
                                                                :user/registered-on
                                                                :user/age
                                                                :user/status
                                                                :user/active?
                                                                :user/favorite-numbers
                                                                [:user/params {:param/other-key :$c}
                                                                 [:param/db :param/other-key
                                                                  [:param/creator [:user/address]]]]]]]]]
                                           :variables [{:variable/name :$c
                                                        :variable/type :String!}]}
                             {:variables {:c "kek"}}])

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

        (let [{:keys [:items :total-count]} (:search-users @query1)
              {:keys [:user/address :user/registered-on :user/age :user/status :user/favorite-numbers :user/params :user/active?]}
              (first items)]
          (is (= 2 (:total-count (:search-users @query1))))
          (is (= address "Street 2"))
          (is (t/equal? registered-on (t/date-time 2018 9 9)))
          (is (bn/= age (bn/number "10e10")))
          (is (= status :user.status/active))
          (is (= favorite-numbers [9 8 7]))
          (is (= params [{:param/db "d", :param/other-key "77" :param/creator {:user/address "Street ABC"}}]))

          ;; tricky, because active? of second query should override "true", since user id is the same
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


(deftest subscription-id
  (run-test-async
    (-> (mount/with-args {:graphql mount-args})
      (mount/start))

    (set-response! {:search-users (fn []
                                    {:total-count 1
                                     :items (constantly
                                              [{:user/address "Street 1"}])})})

    (let [query1 (subscribe [::subs/query
                             {:queries [[:search-users
                                         {:some/param "a"
                                          :user/registered-after (t/date-time 2018 10 10)
                                          :user/age (bn/number "10e10")}
                                         [:total-count
                                          [:items [:user/address]]]]]}
                             {:id :my-query}])]

      (is (true? (:graphql/loading? (first @query1))))
      (is (true? (:graphql/preprocessing? (first @query1))))
      (wait-for [::events/normalize-response]
        (wait-for [::events/set-query-loading]
          (is (nil? (:graphql/errors (first @query1))))
          (is (false? (:graphql/loading? (first @query1))))
          (is (false? (:graphql/preprocessing? (first @query1))))

          (is (= {:total-count 1 :items [{:user/address "Street 1"}]}
                 (:search-users (first @query1))))

          (set-response! {:search-users (fn []
                                          {:total-count 2
                                           :items (constantly
                                                    [{:user/address "Street 2"}])})})

          (dispatch [::events/query {:query {:queries [[:search-users
                                                        {:some/param "b"
                                                         :user/registered-after (t/date-time 2018 10 10)
                                                         :user/age (bn/number "10e10")}
                                                        [:total-count
                                                         [:items [:user/address]]]]]}
                                     :id :my-query}])

          (wait-for [::events/normalize-response]
            (is (= {:total-count 1 :items [{:user/address "Street 1"}]}
                   (:search-users (first @query1))))

            (is (= {:total-count 2 :items [{:user/address "Street 2"}]}
                   (:search-users (second @query1))))))))))



(def readme-tutorial-schema "
   scalar Date
   scalar Keyword
   scalar BigNumber

   type Query {
     user(user_id: ID): User
     searchItems(keyword: String, item_status: Keyword): [Item]
   }

   type User {
     user_id: ID
     user_address: String
     user_registeredOn: Date
     user_age: BigNumber
     user_premiumMember_: Boolean
     user_cartItems: [CartItem]
   }

   type CartItem {
     cartItem_item: Item
     cartItem_quantity: Int
   }

   type Item {
     item_id: ID
     item_title: String
     item_description: String
     item_status: Keyword
     item_price: Float
   }
  ")

(deftest readme-tutorial
  (run-test-async
    (-> (mount/with-args {:graphql (merge mount-args
                                     {:schema readme-tutorial-schema})})
      (mount/start))

    (set-response! {:user (fn []
                            {:user/id "abc"
                             :user/address "Street 123"
                             :user/status :user.status/active
                             :user/registered-on (t/date-time 2018 10 10)
                             :user/age (bn/number "10e10")
                             :user/premium-member? true
                             :user/cart-items (fn []
                                                [{:cart-item/quantity 2
                                                  :cart-item/item (fn []
                                                                    {:item/id 1
                                                                     :item/title "Some Item"
                                                                     :item/description "Some Item Description"
                                                                     :item/price 123.456})}])})
                    :search-items (fn [{:keys [:item/status]}]
                                    [{:item/id "xyz"
                                      :item/title "Some Item"
                                      :item/description "Some Item Description"
                                      :item/status status
                                      :item/price 123.456}])}
      readme-tutorial-schema)

    (let [query1 (subscribe [::subs/query {:queries [[:user
                                                      {:user/id "abc"}
                                                      [:user/address
                                                       :user/registered-on
                                                       :user/age
                                                       :user/premium-member?
                                                       [:user/cart-items [:cart-item/quantity
                                                                          [:cart-item/item [:item/title
                                                                                            :item/price]]]]]]]}])

          query2 (subscribe [::subs/query {:queries [[:search-items
                                                      {:keyword "Grass" :item/status :item.status/active}
                                                      [:item/title
                                                       :item/description
                                                       :item/status
                                                       :item/price]]]}])]

      (wait-for [::events/normalize-response ::events/query-error*]
        (is (nil? (:graphql/errors @query1)))
        (is (nil? (:graphql/errors @query2)))

        (let [{:keys [:user/address :user/registered-on :user/age :user/premium-member? :user/cart-items]} (:user @query1)
              {:keys [:item/title :item/description :item/status :item/price]}
              (first (:search-items @query2))]
          (is (= address "Street 123"))
          (is (t/equal? registered-on (t/date-time 2018 10 10)))
          (is (true? premium-member?))
          (is (= (first cart-items) {:cart-item/quantity 2
                                     :cart-item/item {:item/title "Some Item"
                                                      :item/price 123.456}}))

          (is (= title "Some Item"))
          (is (= description "Some Item Description"))
          (is (= status :item.status/active))
          (is (= price 123.456))

          (is (= "Street 123" (:user/address @(subscribe [::subs/entity :user "abc"]))))
          (is (= "Some Item" (:item/title @(subscribe [::subs/entity :item "xyz"])))))))))


(deftest readme-tutorial-empty-items
  (run-test-async
    (-> (mount/with-args {:graphql (merge mount-args
                                     {:schema readme-tutorial-schema})})
      (mount/start))

    (set-response! {:user (fn []
                            {:user/id "abc"
                             :user/address "Street 123"
                             :user/status :user.status/active
                             :user/registered-on (t/date-time 2018 10 10)
                             :user/age (bn/number "10e10")
                             :user/premium-member? true
                             :user/cart-items []})}
      readme-tutorial-schema)

    (let [query1 (subscribe [::subs/query {:queries [[:user
                                                      {:user/id "abc"}
                                                      [:user/address
                                                       :user/registered-on
                                                       :user/age
                                                       :user/premium-member?
                                                       [:user/cart-items [:cart-item/quantity
                                                                          [:cart-item/item [:item/title
                                                                                            :item/price]]]]]]]}])]

      (wait-for [::events/normalize-response ::events/query-error*]
        (is (nil? (:graphql/errors @query1)))

        (let [user (:user @query1)]
          (is (= user {:user/address "Street 123"
                       :user/registered-on (t/date-time 2018 10 10)
                       :user/age (bn/number "10e10")
                       :user/premium-member? true
                       :user/cart-items []})))))))
