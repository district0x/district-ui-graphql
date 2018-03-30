# district-ui-graphql

[![Build Status](https://travis-ci.org/district0x/district-ui-graphql.svg?branch=master)](https://travis-ci.org/district0x/district-ui-graphql)

Clojurescript [mount](https://github.com/tolitius/mount) + [re-frame](https://github.com/Day8/re-frame) UI module,
that provides client-side solution for [GraphQL](https://graphql.org/) and re-frame.
Think of [apollo-client](https://github.com/apollographql/apollo-client), but tailored specifically for re-frame.
 
## Installation
Add `[district0x/district-ui-graphql "1.0.0"]` into your project.clj  
Include `[district.ui.graphql]` in your CLJS file, where you use `mount/start`

## API Overview

**Warning:** district0x modules are still in early stages, therefore API can change in a future.

- [district.ui.graphql](#districtuigraphql)
- [district.ui.graphql.subs](#districtuigraphqlsubs)
  - [::query](#query-sub)
  - [::entities](#entities-sub)
  - [::entity](#entity-sub)
  - [::query-results](#query-results-sub)
- [district.ui.graphql.events](#districtuigraphqlevents)
  - [::query](#query-evt)
  - [::normalize-response](#normalize-response-evt)
  - [::assoc-queries-with-merged-query](#assoc-queries-with-merged-query-evt)
  - [::set-query-loading](#set-query-loading-evt)
  - [::set-query-errors](#set-query-errors-evt)
  - [::fetch](#fetch-evt)
  - [::unregister-refetch](#unregister-refetch-evt)
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
  - [typename-field->gql-name](#typename-field->gql-name)
  - [id-field->gql-name](#id-field->gql-name)
  - [results](#results)
  - [merge-results](#merge-results)
  - [query->merged-query](#query-merged-query)
  - [query-info](#query-info)
  - [query->merged-query-info](#query-merged-query-info)
  - [assoc-queries-with-merged-query](#assoc-queries-with-merged-query)
  - [assoc-query-loading](#assoc-query-loading)
  - [assoc-query-errors](#assoc-query-errors)
  - [query](#query)
  - [entities](#entities)
  - [entity](#entity)
  - [graph](#graph)
- [district.ui.graphql.utils](#districtuigraphqlutils)
  
## Tutorial
Here's brief tutorial how to use this module step-by-step with explanations what's happening under the hood.
This tutorial assumes basic understanding of GraphQL. It explains only client-side part of GraphQL. For server-side, 
you can consider using [district-server-graphql](https://github.com/district0x/district-server-graphql), but of course
it is server agnostic, so you can use any GraphQL server implementation.  
Complete documentation is below for more in depth understanding.  

#### Schema
First, we need to define GraphQL schema. It's good to have it in shared namespace between server and client, so you can easily 
pass it to both. At the moment, we don't have library that would convert a CLJS data scructure into GraphQL schema, 
so we're gonna define it as a string. In future we'll provide such library as well.  

Let's consider following schema: 
```clojure

```


First, we need to initialize this module via [mount](https://github.com/tolitius/mount). This code should be run before 
your first re-frame event is dispatched.   






## district.ui.graphql
This namespace contains graphql [mount](https://github.com/tolitius/mount) module.

You can pass following args to initiate this module: 
* `:routes` Routes as you'd pass them into bide library
* `:default-route` Default route, passed as `:default` into bide library.
* `:html5?` Pass true if you want to use HTML5 history. This option overrides `:html5-hosts`.
* `:html5-hosts` Collection of hostnames for which HTML5 history should be used. You can also pass string of comma separated
hostnames instead of collection. This is useful for defining hostnames in project.clj via `:closure-defines`. 


```clojure
  (ns my-district.core
    (:require [mount.core :as mount]
              [district.ui.graphql]))
              
  (-> (mount/with-args
        {:graphql {:routes [["/a" :route/a]
                           ["/b/:b" :route/b]]
                  :default-route :route/a
                  :html5-hosts ["localhost" "mydomain.com"]}})
    (mount/start))
```

## district.ui.graphql.subs
re-frame subscriptions provided by this module:

#### <a name="active-page-sub">`::active-page`
Returns active page. A map containing `:name` `:params` `:query`.

#### <a name="active-page-name-sub">`::active-page-name`
Returns route name of active page. 

#### <a name="active-page-params-sub">`::active-page-params`
Returns params of active page.

#### <a name="active-page-query-sub">`::active-page-query`
Returns query of active page. 

#### <a name="resolve-sub">`::resolve`
Works as bide's `resolve`, but you don't need to pass graphql. 

#### <a name="match-sub">`::match`
Works as bide's `match`, but you don't need to pass graphql.

#### <a name="bide-graphql-sub">`::bide-graphql`
Return's bide's graphql.

#### <a name="html5?-sub">`::html5?`
True if using HTML5 History. 

## district.ui.graphql.events
re-frame events provided by this module:

#### <a name="active-page-changed-evt">`::active-page-changed`
Event fired when active page has been changed. Use this event to hook into event flow.  

#### <a name="watch-active-page-evt">`::watch-active-page`
Event to call [::watch-active-page](watch-active-page-fx) effect.

#### <a name="unwatch-active-page-evt">`::unwatch-active-page`
Event to call [::unwatch-active-page](unwatch-active-page-fx) effect.

#### <a name="navigate-evt">`::navigate`
Event to call [::navigate](#navigate-fx) effect.

#### <a name="replace-evt">`::replace`
Event to call [::replace](#replace-fx) effect.

## district.ui.graphql.effects
re-frame effects provided by this module

#### <a name="watch-active-page-fx">`::watch-active-page`
This is special type of effect useful for hooking into [active-page-changed](#active-page-changed) event. Works similarly as
[re-frame-forward-events-fx](https://github.com/Day8/re-frame-forward-events-fx), but instead of dispatching being based on events, it
is based on route name, params and query. This is useful when, for example, your module needs to load data when user visits certain page.

As a param you pass collection of maps containing following keys: 
* `:id` ID of watcher, so you can later unwatch using this ID
* `:name` Route name dispatching will be based on. You can pass also collection of routes or a predicate function.
* `:params` Route params dispatching will be based on. You can also pass predicate function.
* `:query` Route query dispatching will be based on. You can also pass predicate function.
* `:dispatch` Dispatch fired when certain name/params/query is hit. Fired event will get name, params, query as last 3 args.  
* `:dispatch-n` Dispatches fired when certain name/params/query is hit.

You can do dispatching based on either name, params or query, or any combination of two or three of them.  

```clojure
(ns my.district
  (:require
    [district.ui.graphql.effects :as graphql-effects]
    [re-frame.core :refer [reg-event-fx]]))


(reg-event-fx
  ::my-event
  (fn []
    ;; When :route/b page is visited ::some-event will be fired
    {::graphql-effects/watch-active-page [{:id :watcher1
                                          :name :route/b
                                          :dispatch [::some-event]}]}))
                                   
(reg-event-fx
  ::my-event
  (fn []
    ;; When :route/c page with {:a 1} params is visited ::some-event will be fired
    {::graphql-effects/watch-active-page [{:id :watcher1
                                          :name :route/c
                                          :params {:a 1}
                                          :dispatch [::some-event]}]}))
                                   
(reg-event-fx
  ::my-event
  (fn []
    ;; When any page with {:some-param "abc"} query is visited ::some-event will be fired
    {::graphql-effects/watch-active-page [{:id :watcher1
                                          :query {:some-param "abc"}
                                          :dispatch [::some-event]}]}))                                                                      
```

#### <a name="unwatch-active-page-fx">`::unwatch-active-page`
Unwatches previously set watcher based on `:id`. 

```clojure
(reg-event-fx
  ::my-event
  (fn []
    {::graphql-effects/unwatch-active-page [{:id :watcher1}]}))
```

#### <a name="navigate-fx">`::navigate`
Reframe effect to call bide's `navigate!` function.

#### <a name="replace-fx">`::replace`
Reframe effect to call bide's `replace!` function.  


## district.ui.graphql.queries
DB queries provided by this module:   
*You should use them in your events, instead of trying to get this module's 
data directly with `get-in` into re-frame db.*

#### <a name="active-page">`active-page [db]`
Works the same way as sub `::active-page`

#### <a name="active-page-name">`active-page-name [db]`
Works the same way as sub `::active-page-name`

#### <a name="active-page-params">`active-page-params [db]`
Works the same way as sub `::active-page-params`

#### <a name="active-page-query">`active-page-query [db]`
Works the same way as sub `::active-page-query`

#### <a name="assoc-active-page">`assoc-active-page [db active-page]`
Associates new active-page and returns new re-frame db.

#### <a name="resolve">`resolve [db & args]`
Works the same way as sub `::resolve`

#### <a name="match">`match [db & args]`
Works the same way as sub `::match`

#### <a name="bide-graphql">`bide-graphql [db]`
Works the same way as sub `::bide-graphql`

#### <a name="html5?">`html5? [db]`
Works the same way as sub `::html5?`

#### <a name="assoc-bide-graphql">`assoc-bide-graphql [db bide-graphql]`
Associates new bide graphql and returns new re-frame db. 

#### <a name="assoc-html5">`assoc-html5 [db html5?]`
Associates whether the module is using html5 history or not. 

## district.ui.graphql.utils
Util functions provided by this module:

#### <a name="resolve-util">`resolve [name & [params query]]`
Serves as a wrapper for instantly derefed sub `::resolve`.

#### <a name="match-util">`match [path]`
Serves as a wrapper for instantly derefed sub `::match`. 

```clojure
(ns my-district.core
    (:require [mount.core :as mount]
              [district.ui.graphql]
              [district.ui.graphql.utils :as utils]))
              
  (-> (mount/with-args
        {:graphql {:routes [["/a" :route/a]
                           ["/b/:b" :route/b]]
                  :default-route :route/a}})
    (mount/start))
    
(utils/resolve :route/a)
;; => "/a"

(utils/resolve :route/b {:b "abc"} {:c "xyz"})
;; => "/b/abc?c=xyz"

(utils/match "/b/abc?c=xyz")
;; => [:route/b {:b "abc"} {:c "xyz"}]
```

## district.ui.component.page
Multimethod to define pages upon. `district.ui.component.graphql` component will then route those pages according to active-page.

```clojure
(ns my-district.core
  (:require 
    [district.ui.component.page :refer [page]]))

(defmethod page :route/home []
  [:div "Welcome to Home Page"])
```

## district.ui.component.graphql
Components that switches pages (as defined via `district.ui.component.page`) based on current active-page.

```clojure
(ns my-district.core
  (:require
    [reagent.core :as r]
    [mount.core :as mount]
    [district.ui.graphql] 
    [district.ui.component.page :refer [page]]
    [district.ui.component.graphql :refer [graphql]]))

  (-> (mount/with-args
        {:graphql {:routes [["/" :route/home]
                           ["/user" :route/user]]
                  :default-route :route/home}})
    (mount/start))

(defmethod page :route/home []
  [:div "Welcome to Home Page"])
  
(defmethod page :route/user []
  [:div "Welcome to User Page"])

(r/render [graphql] (.getElementById js/document "app"))
```

## Development
```bash
lein deps
# To run tests and rerun on changes
lein doo chrome tests
```