(ns godot-java.core
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def api (json/decode-stream (io/reader "godot-headers/extension_api.json")))

(def base-package-name "godot_java.Godot")

(def godot-class-prefix "GD")

;; NOTE: Some parameters have reserved words as names (e.g. interface, char) and thus I decided
;; that it is easier to prefix all parameters to avoid the problem.
(def godot-parameter-prefix "gd_")

(def java-class-build-dir (io/file "./build/godot_java/godot/"))

(defn indent-line [s]
  (str "    " s))

(defn package-string [package-name]
  (format "package %s;" package-name))

(def dont-use-me-class-name "GDDontUseMe")

(def dont-use-me-class-lines
  [(package-string base-package-name)
   ""
   "// This is a dummy class for types that could not be converted (C pointers)"
   (format "class %s extends Object {}" dont-use-me-class-name)])

(defn resolve-arg-type [?s]
  (cond
    (nil? ?s) "void"
    ;; NOTE: It's a C pointer, not handling that
    (str/includes? ?s "*") dont-use-me-class-name
    ;; FIXME: You can only discard this for enums
    :else (->> (str/split ?s #"::" 2)
               last
               (str godot-class-prefix))))

(defn convert-parameter-name [s]
  (str godot-parameter-prefix s))

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
            (args-info->s (map (fn [arg] {:name (convert-parameter-name (get arg "name"))
                                          :type (resolve-arg-type (get arg "type"))})
                               (get m "arguments"))))]
   ["}"]))

(defn enum-m->lines [m]
  (concat
   [(format "public enum %s {" (get m "name"))]
   ;; TODO Handle bitfields
   (let [n (count (get m "values"))]
     (->> (get m "values")
          ;: Make enum member definition line
          (map #(format "%s(%s)" (get % "name") (get % "value")))
          ;; Add a semicolon for the last line and comma for other lines
          (map-indexed (fn [i s] (str s (if (= i (dec n)) ";" ","))))
          (map indent-line)))
   ;; NOTE: This adds the boilerplate constructor that lets us pass values to enums
   (map indent-line
        ["public final int value;"
         ""
         (format "private %s(int value) {" (get m "name"))
         (indent-line "this.value = value;")
         "}"])
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

(defn generate-and-save-java-classes []
  (.mkdirs java-class-build-dir)
  (spit (io/file java-class-build-dir (str dont-use-me-class-name ".java"))
        (str/join "\n" dont-use-me-class-lines))
  (doseq [[classname source] (map #(vector (get % "name")
                                           (str/join "\n" (normal-class-m->lines %)))
                                  (get api "classes"))]
    (spit (io/file java-class-build-dir (str (resolve-arg-type classname) ".java"))
          source)))

(comment
  (->> (get api "classes")
       (filter #(= (get % "name") "Object"))
       first
       normal-class-m->lines
       flatten
       (str/join "\n")
       println))

(comment (generate-and-save-java-classes))
