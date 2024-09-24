(ns build.build
  (:require
   [clojure.string :as str]
   [babashka.process :as bp]
   [zprint.core :as zp]
   ))

(defn get-os! []
  (-> (bp/shell {:out :string} "uname -s") :out
      str/lower-case str/split-lines first keyword)
  )

(defn zpprint [args]
  (zp/czprint args
              {:max-length [1000 10]
               :style [:dark-color-map]
               :map {:key-order [:replace :op :params :data]}}))

(defn echo [x] (apply println ">>" (:cmd x)))

(defn exit [x]
  (println x)
  (System/exit 1)
  )

(defn java-home []
  (-> (System/getenv) (get "JAVA_HOME"))
  )

(defn ld-library-path []
  (let [JAVA_HOME (-> (System/getenv) (get "JAVA_HOME"))
        os (get-os!)]
    (->
     (str JAVA_HOME "/lib/server")
     #_
     (cond->
       (#{:darwin} os) (str ":" (get-project-root!) "/src/dummy_godot_project/")
       ))))

