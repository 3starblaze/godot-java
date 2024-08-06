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
                  (resolve-arg-type (get-in m ["return_value" "type"]))
                  (get m "name")]
                 (filter (complement nil?))
                 (str/join " "))
            (args-info->s (map (fn [arg] {:name (get arg "name")
                                          :type (resolve-arg-type (get arg "type"))})
                               (get m "arguments"))))]
   ["}"]))

(defn enum-m->lines [m]
  (concat
   [(format "public enum %s {" (get m "name"))]
   ;; TODO Handle bitfields
   (let [n (count (get m "values"))]
     (->> (map #(format "%s = %s" (get % "name") (get % "value")) (get m "values"))
          (map-indexed (fn [i s] (str s (if (= i (dec n)) ";" ","))))
          (map indent-line)))
   ["}"]))

(defn normal-constant-m->line [m]
  (format "public final static long %s = %s;" (get m "name") (get m "value")))

(defn normal-class-m->lines [m]
  (concat
   [(package-string base-package-name)
    ""]
   (class-lines (str godot-class-prefix (get m "name"))
                (if-let [inherits (get m "inherits")]
                  (resolve-arg-type inherits)
                  "Object")
                (-> (concat
                     (map normal-constant-m->line (get m "constants"))
                     (map enum-m->lines (get m "enums"))
                     (map method-m->lines (get m "methods")))
                    flatten))))

(comment
  (->> (get api "classes")
       (filter #(= (get % "name") "Object"))
       first
       normal-class-m->lines
       flatten
       (str/join "\n")
       println))
