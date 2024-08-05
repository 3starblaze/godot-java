(ns godot-java.core
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def api (json/decode-stream (io/reader "godot-headers/extension_api.json")))

(def base-package-name "godot_java.Godot")

(def godot-class-prefix "GD")

(defn indent-line [s]
  (str "    " s))

(defn package-string [package-name]
  (format "package %s;" package-name))

(defn resolve-arg-type [?s]
  (if (nil? ?s)
    "void"
    ;; TODO: Check if this is correct behavior
    (->> (str/split ?s #"::" 2)
       last
       (str godot-class-prefix))))

(defn class-lines [classname parent-classname body-lines]
  (concat
   [(format "public class %s extends %s {" classname parent-classname)]
   (map indent-line body-lines)
   ["}"]))

(defn args-info->s [args-info]
  (str/join ", " (map #(format "%s %s" (:type %) (:name %)) args-info)))

(defn method-m->lines [m]
  (concat
   [(format "%s(%s) {"
            (->> ["public"
                  (if (get m "is_static") "static" nil)
                  (if (get m "is_virtual") nil "final")
                  (or (get-in m ["return_value" "type"]) "void")
                  (get m "name")]
                 (filter (complement nil?))
                 (str/join " "))
            (args-info->s (map (fn [arg] {:name (get arg "name")
                                          :type (resolve-arg-type (get arg "type"))})
                               (get m "arguments"))))]
   ["}"]))

(defn normal-class-m->lines [m]
  (concat
   [(package-string base-package-name)
    ""]
   (class-lines (str godot-class-prefix (get m "name"))
                (or (str godot-class-prefix (get m "inherits")) "Object")
                (flatten (map method-m->lines (get m "methods"))))))

(comment
  (->> (get api "classes")
       second
       normal-class-m->lines
       (str/join "\n")
       println))
