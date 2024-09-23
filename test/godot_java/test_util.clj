(ns godot-java.test-util
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.instrument :as mi]))

(defn malli-instrumentation-fixture [test-run]
  (mi/instrument!
   {:report (fn [violation-type {:keys [fn-name] :as data}]
              (let [[schema val]
                    (case violation-type
                      ::m/invalid-input [(:input data) (:args data)]
                      ::m/invalid-output [(:output data) (:value data)])]
                (printf "Reporting malli instrumentation issue in %s:\n" fn-name)
                (-> (m/explain schema val) me/humanize println)
                (println "Value:")
                (println val)))})
  (test-run)
  (mi/unstrument!))
