# district-ui-graphql

[![Build Status](https://travis-ci.org/district0x/district-ui-graphql.svg?branch=master)](https://travis-ci.org/district0x/district-ui-graphql)

Clojurescript [re-mount](https://github.com/district0x/d0x-INFRA/blob/master/re-mount.md) module,
that provides client-side solution for [GraphQL](https://graphql.org/) and re-frame.
Think of [apollo-client](https://github.com/apollographql/apollo-client), but tailored specifically for re-frame.

## Installation

district-ui-graphql is available as a Maven artifact from Clojars. The latest released version is:
[![Clojars Project](https://img.shields.io/clojars/v/district0x/district-ui-graphql.svg)](https://clojars.org/district0x/district-ui-graphql)<br>

Include `[district.ui.graphql]` in your CLJS file, where you use `mount/start`

## Introduction
Here's introduction guide on how to use this module step-by-step with explanations what's happening under the hood.
Basic understanding of GraphQL is assumed. It explains only client-side part of GraphQL. For server-side,
you can consider using [district-server-graphql](https://github.com/district0x/district-server-graphql) or any
other GraphQL server implementation.
Complete documentation is below for more in-depth understanding.

Full code of example application can be found at [district-demo-graphql](https://github.com/district0x/district-demo-graphql).

### Schema
First, we need to define GraphQL schema. It's good to have it in shared namespace between server and client, so you can easily
pass it to both. At the moment, we don't have library that would convert a cljs data scructure into GraphQL schema,
so we're going to define it as a string. In future we'll provide such library as well.

Let's consider this simple schema for an ecommerce app:
```clojure
(ns my.app.graphql-schema)

(def schema "
   scalar Date
   scalar Keyword

   type Query {
     user(user_id: ID): User
     searchItems(keyword: String, item_status: Keyword): [Item]
   }

   type User {
     user_id: ID
     user_address: String
     user_registeredOn: Date
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
```

This module comes with built-in scalar definitions for `Date` and `Keyword`.
If you want to use them in your schema, all you need to do is to add them at top of your schema as you see in the example.
`Keyword` will be parsed into Clojure's keyword and `Date` into `goog.DateTime`.

Since GraphQL field names support very limited character set and
we want to preserve keyword namespaces, we use [kw->gql-name](https://github.com/district0x/district-graphql-utils#kw-gql-name)
and [gql-name->kw](https://github.com/district0x/district-graphql-utils#gql-name-kw) for conversion. These functions can
be changed in configuration.

### Module Start
Next we start our graphql [re-mount](https://github.com/district0x/d0x-INFRA/blob/master/re-mount.md) module. This should be done at app bootstrap, where you initialize re-frame.

```clojure
(ns my.app.core
  (:require
    [district.ui.graphql]
    [my.app.graphql-schema :refer [schema]]
    [mount.core :as mount]))

(-> (mount/with-args {:graphql {:schema schema
                                :url "http://localhost:1234/"}})
  (mount/start))
```

### Using Subscriptions
Now we're pretty much set up! Let's look how reagent components would look like.
Again, this pseudocode assumes you have server running and retuning valid GraphQL responses.

```clojure
(ns my.app.home-page
  (:require [district.ui.graphql.subs :as gql]))

(defn user-info []
  (let [query (subscribe [::gql/query
                          {:queries [[:user
                                      {:user/id "abc"}
                                      [:user/address
                                       :user/registered-on
                                       :user/premium-member?
                                       [:user/cart-items [:cart-item/quantity
                                                          [:cart-item/item [:item/title
                                                                            :item/price]]]]]]]}])]
    (fn []
      (if (:graphql/loading? @query)
        [:div "Loading..."]
        (let [{:keys [:user/address :user/registered-on :user/premium-member? :user/cart-items]} (:user @query)]
          [:div
           [:div "User Information:"]
           [:div "Address: " address]
           (when registered-on
             [:div "Registered On: " (format-date registered-on)])
           [:div "Premium Member?: " premium-member?]
           [:div "Cart Items:"]
           (for [{:keys [:cart-item/item :cart-item/quantity]} cart-items]
             (let [{:keys [:item/title :item/price]} item]
               [:div {:key title}
                title ": $" (* quantity price)]))])))))


(defn searched-items []
  (let [query (subscribe [::gql/query
                          {:queries [[:search-items
                                      {:keyword "Grass" :item/status :item.status/active}
                                      [:item/id
                                       :item/title
                                       :item/description
                                       :item/status
                                       :item/price]]]}])]
    (fn []
      [:div
       [:div "Search Results:"]
       (if-not (:graphql/errors @query)
         (for [{:keys [:item/id :item/title :item/description :item/status :item/price]} (:search-items @query)]
           [:div {:key id}
            [:div "Item: " title]
            [:div description]
            [:div "Status: " status]
            [:div "Price: $" price]])
         [:div "Errors during loading: " (:graphql/errors @query)])])))

(defn home-page []
  [:div
   [user-info]
   [searched-items]])

```

Believe or not, that's it! That's all code needed for this app's front-end. Now you might be asking:
How about sending requests to a server via events? How about storing data in app state? None of this needs to be coded. Our
fancy subscriptions handle it all for you! Let's go step-by-step what actually happens here:

1. We pass queries into `query` subscription as [graphql-query](https://github.com/district0x/graphql-query) data structure
2. Module batches together queries from all rendered components into a single big query
3. The query is sent to GraphQL server
4. Received response is **normalised** and stored in re-frame db
5. Subscription then queries normalised data with the same GraphQL query and returns data

There is good amount of underlying complexity associated with these steps, but luckily for you, you don't need to deal
with any of that! But for better comprehension, let's look at some of them into more detail:

### Normalisation
When data is received from the server as graph, we need to normalise this data, because some data can be duplicated throughout
the graph. For example, if I receive data about user with id `"1"` from query `:user-by-id`, I want it to be recognised
as the same user even when completely different query (e.g `:search-users`) returns data for user with id `"1"`.

To solve this, this module appends each level of the query with all `ID` fields related to queried type, by looking at
schema. After we receive response, we can then extract "entities" (as you might know then from datomic/datascript)
from graph, by using their ID fields and Type. Module then replaces recognised entities in graph with their references.
So when something about an entity gets updated, any branch in graph will be always showing updated data.

Let's say we receive response as following:
```clojure
{:user-by-id {:user/id 1
              :user/name "John"
              :user/address "Street 123"
              :__typename :user}
 :search-users [{:user/id 1
                 :user/name "John"
                 :user/age 21
                 :__typename :user}]}
```

Now after normalisation, the data structure would be similar to this:

```clojure
{:entities {:user {1 {:user/id 1
                      :user/name "John"
                      :user/address "Street 123"
                      :user/age 21
                      :__typename :user}}}
 :user-by-id {:id 1
              :type :user
              :graphql/ref? true}
 :search-users [{:id 1
                 :type :user
                 :graphql/ref? true}]}
```

Now you might say: That's cool, but both `:user-by-id` and `:search-users` probably return different results based
on passed parameters. That's right! That's why we organise them internally by passed parameters. So stored state
might be looking more like this:


```clojure
{:entities {:user {1 {:user/id 1
                      :user/name "John"
                      :user/address "Street 123"
                      :user/age 21
                      :__typename :user}}}
 :user-by-id {{:user/id 1}
              {:id 1
               :type :user
               :graphql/ref? true}}
 :search-users {{:some-param "abc" :some-other-param "xyz"}
                [{:id 1
                  :type :user
                  :graphql/ref? true}]}}
```

After all normalised data is stored in your re-frame db as any other client-side data. You can easily inspect them with
subscriptions: `::entities`, `::entity` and `::graph` or simply by inspecting re-frame db. In your reagent components,
you should always use `::query` subscription to query data with client-side GraphQL, because type coercion happens
on that level.

### Refetching
Sometimes you might want to refetch data after some real-time event occurs, not only when component is mounted into DOM.
For that purpose `::query` subscription offers `:refetch-on` option. You simply pass it set of events as you'd pass
them into [re-frame-forward-events-fx](https://github.com/Day8/re-frame-forward-events-fx) `:events` field.
When one of those events occures, query will be refetched. When the component is removed from DOM, event listener will be stopped.

```clojure
(defn searched-items []
  (let [query (subscribe [::subs/query
                          {:queries [[:search-items
                                      {:keyword "Grass" :item/status :item.status/active}
                                      [:item/title]]]}
                          {:refetch-on #{::item-changed}}])]
    (fn []
      [:div "Search Results:"]
      ;; skipped for brevity ...
      )))
```

### Query Middleware
If you think this was cool so far, you ain't seen best part yet ;) We've been said GraphQL truly shines in loading data
from multiple resources, right? True, but usually this is kept only for server-side where GraphQL resolvers are run.
We already need to run GraphQL resolvers against re-frame db client-side, so why not to open Pandora's box
little bit more :)

By using query middlewares we can intercept and modify query before it's send to the server, as well as return
partial response, which will be eventually merged into final response.

Creating middleware from scratch has definitely use cases, but might be bit too "low level" for your needs.
For that reason, we're going to look at `create-resolver-middleware` function, which simplifies the most common use of
middlewares: Loading data from multiple resources.

Let's stick with previous ecommerce example, but this time let's say we initialise it with our custom resolver middleware.

```clojure
  (ns my.app.core
    (:require
      [district.ui.graphql.middleware.resolver :refer [create-resolver-middleware]]
      [my.app.graphql-schema :refer [schema]]
      [my.app.my-resolver :refer [my-resolver]]
      [mount.core :as mount]))

  (-> (mount/with-args {:graphql
                        {:schema schema
                         :url "http://localhost:1234/"
                         :query-middlewares [(create-resolver-middleware :my-resolver {:root-value my-resolver})]}})
    (mount/start))
```

"Root Value" is term from GraphQL, that stands for tree structure describing how data in query should be resolved.
Basic rule is: Everytime when any leaf in the tree is supposed to return map or collection, you need to wrap it
in a function. Let's see how one example of root value may look like:

```clojure
(ns my.app.my-resolver)

(def my-resolver
  {:user (fn [{:keys [:user/id]}]
           {:user/id "abc"
            :user/address "Street 123"
            :user/age 21
            :user/premium-member? true
            :user/cart-items (fn []
                               [{:cart-item/quantity 2
                                 :cart-item/item (fn []
                                                   {:item/title "Some Item"
                                                    :item/price 123.456})}])})
   :search-items (fn [{:keys [:keyword :item/status]}]
                   [{:item/id "xyz"
                     :item/title "Some Item"
                     :item/description "Some Item Description"
                     :item/status :item.status/active
                     :item/price 123.456}])})
```

So what we see here, is that resolver returns some data, but not all of data requested by components. What happens next,
is that middleware will automatically remove returned fields from query and server will be queried only for data not
returned by this resolver.

Now you might be saying: Wow, interesting, but I still don't see how this is so useful. Well, true mindblow comes when
you realise you can return Promise for any field in the tree, therefore make any number of asynchronous requests!

Alternative resolver might look like:

```clojure
(def my-resolver
  {:user (fn [{:keys [:user/id]}]
           {:user/id "abc"
            :user/address "Street 123"
            :user/age (load-age-from-grandmas-notebook id)
            :user/premium-member? (load-premium-member-from-cache id)
            :user/cart-items (fn []
                               [{:cart-item/quantity (load-quantity-from-blockchain id)
                                 :cart-item/item (fn []
                                                   {:item/title "Some Item"
                                                    :item/price (load-price-from-3rd-party-api)})}])})
   :search-items (fn [{:keys [:keyword :item/status]}]
                   (search-items-from-legacy-rest-api keyword status))})
```

Important to realise here is that all asynchronous requests will be made at once, including last one sent to the GraphQL
server. This is simply because if some field returns Promise, we can automatically remove
it from query and continue. Situation is of course different for nested Promises, which by the way, you can also use.

Some of you might realise now: Wait, so if a middleware returns all fields requested by query, will it "shrink" final
query into nil and not send any request to GraphQL server?
That's right! That means you could possibly resolve everything via REST API in middlewares and
don't even need to have GraphQL server! That's why `:url` in module configuration is optional.

Current limitation of query "shrinking" are fragments. Fragments are not being removed from query if they're not
necessary to send to the GraphQL server. We hope to resolve this in future.


### Infinite Scroll
To be able to implement feature such as infinite scroll, query subscription accepts one extra parameter `:id`.
When `:id` is passed, subscription can be extended "on the fly" to return results from new queries. So in
case of infinite scroll, you want single subscription returning all loaded results, but you want to keep adding results
into this subscription as user scrolls down.

So for example you might have subscription like following:
```clojure
(subscribe [::subs/query {:queries [[:search-users
                                       {:limit 10 :offset 0}
                                       [:total-count
                                        [:items [:user/address]]]]]}
              {:id :my-query}])

```
Then somewhere else in your code, you'll have callback for scrolling event, where you'll load another query into the
`:my-query` subscription like this:

```clojure
(dispatch [::events/query {:query {:queries [[:search-users
                                                {:limit 10 :offset 10}
                                                [:total-count
                                                 [:items [:user/address]]]]]}
                             :id :my-query}])
```

Then the subscription will return collection of all query results.

### Complex Queries
The guide above didn't show all kinds of complex GraphQL queries including fragments, variables, aliases etc.
These are all supported by this module, to see how they're used in action please inspect [tests](https://github.com/district0x/district-ui-graphql/blob/master/test/tests/all.cljs) and
[graphql-query](https://github.com/district0x/graphql-query).

## Documentation

## API Overview

- [district.ui.graphql](#districtuigraphql)
- [district.ui.graphql.subs](#districtuigraphqlsubs)
  - [::query](#query-sub)
  - [::entities](#entities-sub)
  - [::entity](#entity-sub)
  - [::query-results](#query-results-sub)
- [district.ui.graphql.events](#districtuigraphqlevents)
- [::query](#query-evt)
  - [::set-query-loading](#set-query-loading-evt)
  - [::set-query-errors](#set-query-errors-evt)
  - [::fetch](#fetch-evt)
  - [::set-schema](#set-schema-evt)
  - [::update-entity](#update-entity-evt)
  - [::unregister-refetch](#unregister-refetch-evt)
  - [::set-authorization-token](#set-authorization-token)

- [district.ui.graphql.effects](#districtuigraphqleffects)
  - [::enqueue-query](#enqueue-query-fx)
  - [::fetch](#fetch-fx)
  - [::add-fetch-middleware](#add-fetch-middleware-fx)
  - [::add-fetch-afterware](#add-fetch-afterware-fx)
  - [::add-fetch-batch-middleware](#add-fetch-batch-middleware-fx)
  - [::add-fetch-batch-afterware](#add-fetch-batch-afterware-fx)
  - [::fetch-middleware-next](#fetch-middleware-next-fx)
- [district.ui.graphql.queries](#districtuigraphqlqueries)
  - [config](#config)
  - [merge-config](#merge-config)
  - [results](#results)
  - [merge-results](#merge-results)
  - [query->batched-query](#query-batched-query)
  - [query-info](#query-info)
  - [query->batched-query-info](#query-batched-query-info)
  - [assoc-query-loading](#assoc-query-loading)
  - [assoc-query-preprocessing](#assoc-query-preprocessing)
  - [assoc-query-errors](#assoc-query-errors)
  - [query](#query)
  - [entities](#entities)
  - [entity](#entity)
  - [graph](#graph)
  - [update-entity](#update-entity)
  - [query-preprocessing?](#query-preprocessing)
- [district.ui.graphql.utils](#districtuigraphqlutils)
- [district.ui.graphql.middleware.id-fields](#districtuigraphqlmiddlewareid-fields)
- [district.ui.graphql.middleware.typenames](#districtuigraphqlmiddlewaretypenames)
- [district.ui.graphql.middleware.resolver](#districtuigraphqlmiddlewareresolver)
  - [update-entity](#update-entity)


## district.ui.graphql
This namespace contains graphql [mount](https://github.com/tolitius/mount) module.

You can pass following args to initiate this module:
* `:url` URL of your HTTP GraphQL server
* `:schema` GraphQL schema
* `:query-middlewares` Collection of middlewares in order they'll be executed
* `:kw->gql-name` Function to convert keyword into GraphQL field name. Default: [kw->gql-name](https://github.com/district0x/district-graphql-utils#kw-gql-name)
* `:gql-name->kw` Function to convert GraphQL field name inyo keyword. Default: [gql-name->kw](https://github.com/district0x/district-graphql-utils#gql-name-kw)
* `:fetch-opts` Parameter passed to create [apollo-fetch](https://github.com/apollographql/apollo-fetch)

## district.ui.graphql.subs
re-frame subscriptions provided by this module:

#### <a name="query-sub">`::query [query query-opts]`
Primary interface of this module. You can pass query either as a string or [graphql-query](https://github.com/district0x/graphql-query)
data structure. When a reagent component with this subscription is mounted, it enqueues passed query. After all components
are mounted, all enqueued queries are batched and passed to middlewares and eventually GraphQL server.

query-opts:
* `:variables`: Variable values, when using [GraphQL variables](http://graphql.org/learn/queries/#variables)
* `:refetch-on`: Set of events as passed to [re-frame-forward-events-fx](https://github.com/Day8/re-frame-forward-events-fx),
that trigger refetching of the query.
* `:refetch-id`: ID of refetch listener, so later it can be stopped. If not provided, it'll be calculated automatically for you.
* `:disable-fetch?`: Pass true if you want to disable remote fetching of this query, and just query re-frame db client-side.
* `:id`: ID of subscription. See Infinite Scroll section.
* `:consider-preprocessing-as-loading?`: Will be returning `:graphql/loading? true` also for preprocessing phase of query. Default: true

#### <a name="entities-sub">`::entities [& type]`
Returns all recognised entities extracted from graph responses. Pass type if you want to get entities only for
certain type as defined in GraphQL schema. Recommended to use only for debugging purposes.

Note entity types are also converted with [gql-name->kw](https://github.com/district0x/district-graphql-utils#gql-name-kw)
from GraphQL definitions.

```clojure
@(subscribe [::gql/entities :user])
```

#### <a name="entity-sub">`::entity [type id]`
Returns single entity by its type and id. If an object type has in its schema definition just single field of type `ID`,
it's enough to pass single value. Hoveever if object type has multiple `ID` fields, you need to pass map of id keys and
values as id param.

```clojure
@(subscribe [::gql/entity :user "abc"])
@(subscribe [::gql/entity :user-friendship {:user/id "abc" :friend/id "xyz"}])
```

#### <a name="graph-sub">`::graph`
Returns graph of all responses stored client-side.

## district.ui.graphql.events
re-frame events provided by this module:

#### <a name="query-evt">`::query [query-opts]`
Event that gets fired by subscription `::query` for remote fetching. Unless doing something specific, you woudln't
have to use this event, since it's used by subscription under the hood.

#### <a name="set-schema-evt">`::set-schema [schema]`
Event to change schema during runtime

#### <a name="update-entity-evt">`::update-entity [type id new-entity]`
Sometimes you might need to update entity manually. This event will help you with that. Must pass GraphQL type and
entity ID.

#### <a name="set-authorization-token">`::set-authorization-token [token]`
Sets the authorization token. When set it will be added under request headers authorization for every GraphQL request.

## district.ui.graphql.effects
re-frame effects provided by this module

#### <a name="enqueue-query-fx">`::enqueue-query`
Effect that enqueues query into internally used [dataloader](https://github.com/facebook/dataloader). Used by event `::query`.

#### <a name="fetch-fx">`::fetch`
Applies middlewares and performs [apollo-fetch](https://github.com/apollographql/apollo-fetch).

#### <a name="add-fetch-middleware-fx">`::add-fetch-middleware`
Runs `ApolloFetch.use`

#### <a name="add-fetch-afterware-fx">`::add-fetch-afterware`
Runs `ApolloFetch.useAfter`

#### <a name="add-fetch-batch-middleware-fx">`::add-fetch-batch-middleware`
Runs `ApolloFetch.batchUse`

#### <a name="add-fetch-batch-afterware-fx">`::add-fetch-batch-afterware`
Runs `ApolloFetch.batchUseAfter`

#### <a name="fetch-middleware-next-fx">`::fetch-middleware-next`
Runs "next" function of to invoke next ApolloFetch middleware

## district.ui.graphql.queries
DB queries provided by this module:
*You should use them in your events, instead of trying to get this module's
data directly with `get-in` into re-frame db.*

#### <a name="query">`query [db query-str variables]`
Performs client-side graphql query against graph & entities stored in re-frame db

#### <a name="config">`config [db & [key]]`
Returns config options of this module

#### <a name="merge-config">`merge-config [db config]`
Merges in new config options

#### <a name="results">`results [db]`
Returns entities and graph

#### <a name="merge-results">`merge-results [db results]`
Merges in entities and graph

#### <a name="query-batched-query">`query->batched-query [db query-str variables]`
Returns associated batched query for given query and variables

#### <a name="query-info">`query-info [db query-str]`
Returns loading and errors state of given query

#### <a name="query-batched-query-info">`query->batched-query-info [db & args]`
Return loading and errors state of batched query given query and variables

#### <a name="assoc-query-loading">`assoc-query-loading [db query-str loading?]`
Associates loading state with given query

#### <a name="assoc-query-preprocessing">`assoc-query-preprocessing [db query-str preprocessing?]`
Associates preprocessing state with given query

#### <a name="assoc-query-errors">`assoc-query-errors [db query-str errors]`
Associates errors with given query

#### <a name="entities">`entities [db & [type]]`
Works same way as subscription `::entities`

#### <a name="entity">`entity [db type id]`
Works same way as subscription `::entity`

#### <a name="graph">`graph [db]`
Works same way as subscription `::graph`

#### <a name="update-entity">`update-entity [db type id new-entity]`
Updates entity in re-frame db. Works same was as event `::update-entity`.

#### <a name="query-preprocessing">`query-preprocessing? [db query-str]`
Returns true if query is being preprocessed.

## district.ui.graphql.utils
In this namespace is bunch of utility functions for internal purposes of this module.

## district.ui.graphql.middleware.id-fields
Contains built-in middleware that adds ID fields to each level of query, based on definitions in schema. This
middleware is automatically added and is essential for normalisation to work.

## district.ui.graphql.middleware.typenames
Contains built-in middleware, that figures out typenames for each level in query. This middleware is automatically
added and is essential for normalisation to work.

## district.ui.graphql.middleware.resolver

#### <a name="create-resolver-middleware">`create-resolver-middleware [db opts]`
Creates resolver middleware, which functionality is described in guide above.

opts:
* `:root-value`: Root value resolver. Can return Promise on any leaf in a tree
* `:context`: Context available throughout root-value functions. re-frame db is added to the context automatically for you.

## Development
```bash
lein deps
# To run tests and rerun on changes
lein doo chrome tests
```
