(ns build.build
  (:require
   [babashka.fs :as bfs]
   [clojure.string :as str]
   [clojure.java.shell :as shell]
   [babashka.process :as bp]
   ))


(defn get-project-root! []
  ;; We assume this file is in "<repo>/bin" folder and we can find the repo by getting the parent
  ;; directory.

  (-> (bfs/cwd)
      bfs/file
      )
  #_
  (-> *file* io/as-file .getParentFile .getParent))

(defn get-classpath! []
  (->> (str/split
        (:out (shell/sh "clojure" "-Spath" "-A:dev" :dir (get-project-root!)))
        #":")
       ;; Classpath can include relative paths which need to be absolute because the godot project
       ;; is in another place and that probably breaks the classpaths. Making paths absolute does
       ;; the trick, so that's what we do.
       (map #(if (str/starts-with? % "/") % (str (get-project-root!) "/" %)))
       (str/join ":")))


(defn get-os! []
  (-> (bp/shell {:out :string} "uname -s") :out
      str/lower-case str/split-lines first keyword)
  )

(defn echo [x] (apply println ">>" (:cmd x)))

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


(defn get-env! [ns f]
  (let
      [JAVA_HOME (-> (System/getenv) (get "JAVA_HOME"))]
   (merge
    ;; `System/getenv` doesn't return a real map and we can't merge the result
    ;; so we use `into` to force it to be a map.
    (into {} (System/getenv))
    {"GODOT_CLOJURE_CLASSPATH" (get-classpath!)
     "GODOT_CLOJURE_ENTRY_NS" ns
     "GODOT_CLOJURE_ENTRY_FN" f
     "LD_LIBRARY_PATH"
     (let [os (get-os!)]
       (cond-> (str JAVA_HOME "/lib/server/")
         (#{:darwin} os) (str ":" (get-project-root!) "/src/dummy_godot_project/")
         ))
     })))
