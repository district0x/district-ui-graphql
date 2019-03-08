(defproject district0x/district-ui-graphql "1.0.9-SNAPSHOT"
  :description "district UI module for GraphQL integration"
  :url "https://github.com/district0x/district-ui-graphql"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[district0x/bignumber "1.0.3"]
                 [camel-snake-kebab "0.4.0"]
                 [cljsjs/apollo-fetch "0.7.0-0"]
                 [cljsjs/dataloader "1.4.0-0"]
                 [cljsjs/graphql "0.13.1-0"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 [day8.re-frame/forward-events-fx "0.0.5"]
                 [district0x/district-cljs-utils "1.0.3"]
                 [district0x/district-graphql-utils "1.0.6"]
                 [district0x/graphql-query "1.0.4"]
                 [madvas/contextual "0.1.0"]
                 [mount "0.1.11"]
                 [org.clojure/clojurescript "1.10.238"]
                 [re-frame "0.10.2"]]

  :doo {:paths {:karma "./node_modules/karma/bin/karma"}}

  :clean-targets ^{:protect false} ["target" "tests-output"]

  :npm {:devDependencies [[karma "1.7.1"]
                          [karma-chrome-launcher "2.2.0"]
                          [karma-cli "1.0.1"]
                          [karma-cljs-test "0.1.0"]]}

  :profiles {:dev {:dependencies [[com.cemerick/piggieback "0.2.2"]
                                  [day8.re-frame/test "0.1.5"]
                                  [org.clojure/clojure "1.8.0"]
                                  [org.clojure/tools.nrepl "0.2.13"]
                                  [print-foo-cljs "2.0.3"]]
                   :plugins [[lein-cljsbuild "1.1.7"]
                             [lein-doo "0.1.9"]
                             [lein-npm "0.6.2"]]}}

  :deploy-repositories [["snapshots" {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_password
                                      :sign-releases false}]
                        ["releases"  {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_password
                                      :sign-releases false}]]

  :release-tasks [#_["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["deploy"]]

  :cljsbuild {:builds [{:id "tests"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "tests-output/tests.js"
                                   :output-dir "tests-output"
                                   :main "tests.runner"
                                   :optimizations :none}}]})
