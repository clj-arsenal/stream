(ns test
  (:require
   [clj-arsenal.check :as check]
   [clj-arsenal.stream]))

(defn run
  []
  (check/report-all-checks-and-exit!))
