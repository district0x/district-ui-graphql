(defproject district0x/district-ui-graphql "1.0.13-SNAPSHOT"
  :description "district UI module for GraphQL integration"
  :url "https://github.com/district0x/district-ui-graphql"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[district0x/bignumber "1.0.3"]
                 [camel-snake-kebab "0.4.1"]
                 [cljsjs/apollo-fetch "0.7.0-0"]
                 [cljsjs/dataloader "1.4.0-0"]
                 [cljsjs/graphql "15.3.0-0"]
                 [com.andrewmcveigh/cljs-time "0.5.2"]
                 [day8.re-frame/forward-events-fx "0.0.6"]
                 [day8.re-frame/http-fx "0.1.6"]
                 [district0x/district-cljs-utils "1.0.4"]
                 [district0x/district-graphql-utils "1.0.11-SNAPSHOT"]
                 [district0x/graphql-query "1.0.4"]
                 [district0x/contextual "0.2.0"]
                 [mount "0.1.16"]
                 [org.clojure/clojurescript "1.10.773"]
                 [re-frame "0.12.0"]]

  :doo {:paths {:karma "./node_modules/karma/bin/karma"}
        :build "tests"
        :karma {:config {"logLevel" "debug"}}}

  :clean-targets ^{:protect false} ["target" "tests-output"]

  :npm {:devDependencies [[karma "^4.4.1"]
                          [karma-chrome-launcher "^3.1.0"]
                          [karma-cli "^2.0.0"]
                          [karma-cljs-test "^0.1.0"]]}

  :profiles {:dev {:dependencies [[com.cemerick/piggieback "0.2.2"]
                                  [day8.re-frame/test "0.1.5"]
                                  [org.clojure/clojure "1.8.0"]
                                  [org.clojure/tools.nrepl "0.2.13"]
                                  [print-foo-cljs "2.0.3"]]
                   :plugins [[lein-cljsbuild "1.1.7"]
                             [lein-ancient "0.6.15"]
                             [lein-doo "0.1.10"]
                             [lein-npm "0.6.2"]]}}

  :deploy-repositories [["snapshots" {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_token
                                      :sign-releases false}]
                        ["releases"  {:url "https://clojars.org/repo"
                                      :username :env/clojars_username
                                      :password :env/clojars_token
                                      :sign-releases false}]]
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["deploy"]]

  :cljsbuild {:builds [{:id "tests"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "tests-output/tests.js"
                                   :output-dir "tests-output"
                                   :main "tests.runner"
                                   :optimizations :none}}]})
