(ns godot-java.core
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def api (json/decode-stream (io/reader "godot-headers/extension_api.json")))

(def base-package-name "godot_java.Godot")

(def godot-class-prefix "GD")

(defn package-string [package-name]
  (format "package %s;" package-name))

(defn class-lines [classname parent-classname body-lines]
  (concat
   [(format "public class %s extends %s {" classname parent-classname)]
   body-lines
   ["}"]))

(comment
  (->> (concat
        [(package-string base-package-name)
         ""]
        (class-lines "Child" "Parent" []))
       (str/join "\n")
       println))
