(ns tests.runner
  (:require
    [cljs.spec.alpha :as s]
    [doo.runner :refer-macros [doo-tests]]
    [tests.all]
    [tests.utils]))

(s/check-asserts true)

(enable-console-print!)

(doo-tests 'tests.all 'tests.utils)

