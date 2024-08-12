(ns godot-java.core
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def api (json/decode-stream (io/reader "godot-headers/extension_api.json")))

;; NOTE: The are some enums that start with "Variant." and Variant is not an explicitly
;; decalred class, so we need to account for that
(defn variant-global-enum? [m]
  (str/starts-with? (get m "name") "Variant."))

(def global-enum-set
  (->> (get api "global_enums")
       (filter (complement variant-global-enum?))
       (map #(get % "name"))
       set))

(def base-package-name "godot_java.Godot")

(def godot-class-prefix "GD")

;; NOTE: Some parameters have reserved words as names (e.g. interface, char) and thus I decided
;; that it is easier to prefix all parameters to avoid the problem.
(def godot-parameter-prefix "gd_")

(def java-class-build-dir (io/file "./build/godot_java/godot/"))

(defn indent-line [s]
  (str "    " s))


(def dont-use-me-class-name "GDDontUseMe")

(defn sanitize-method-name [s]
  (case s
    ;; NOTE: new is a reserved word
    "new" "gd_new"
    ;; NOTE: wait is a final method defined in java.lang.Object
    "wait" "gd_wait"
    s))

(def dont-use-me-class-lines
  ["// This is a dummy class for types that could not be converted (C pointers)"
   (format "class %s extends Object {}" dont-use-me-class-name)])

(defn resolve-arg-type [?s]
  (if (nil? ?s)
    "void"
    ;; FIXME: You can only discard this for enums
    (let [s (last (str/split ?s #"::" 2))]
      (cond
        ;; NOTE: It's a C pointer, not handling that
        (str/includes? s "*") dont-use-me-class-name
        (= s "bool") "boolean"
        (= s "String") "String"
        ;; NOTE: Godot "float" should always be double
        (= s "float") "double"
        ;; NOTE: Godot integers are 64bit which in Java would be a long
        (= s "int") "long"
        (global-enum-set s) (str "GDGlobalScope." s)
        :else (str godot-class-prefix s)))))

(defn resolve-member-type [s]
  (case s
    "float" "float"
    "int32" "int"
    (str godot-class-prefix s)))

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
                  (sanitize-method-name (get m "name"))]
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
          (map #(format "%s(%s)"
                        (get % "name")
                        ;; NOTE: We add "L" to tell Java that we are using longs
                        (str (get % "value") "L")))
          ;; Add a semicolon for the last line and comma for other lines
          (map-indexed (fn [i s] (str s (if (= i (dec n)) ";" ","))))
          (map indent-line)))
   ;; NOTE: This adds the boilerplate constructor that lets us pass values to enums
   (map indent-line
        ["public final long value;"
         ""
         (format "private %s(long value) {" (get m "name"))
         (indent-line "this.value = value;")
         "}"])
   ["}"]))

(defn normal-constant-m->line [m]
  (format "public final static long %s = %s;" (get m "name") (get m "value")))

(defn normal-class-m->build-file-export-m [m]
  (let [classname (str godot-class-prefix (get m "name"))]
    {:filename (str classname ".java")
     :lines (class-lines classname
                         (if-let [inherits (get m "inherits")]
                           (resolve-arg-type inherits)
                           "Object")
                         (-> (concat
                              (map normal-constant-m->line (get m "constants"))
                              (map enum-m->lines (get m "enums"))
                              (map method-m->lines (get m "methods")))
                             flatten))}))

#_(defn struct-like-class-m->build-file-export-m [m]
  (let [classname (resolve-arg-type (get m "name"))]
    {:filename (str classname ".java")
     ;; TODO: Implement body
     :lines (class-lines classname "Object" [])}))

#_(defn make-struct-like-classes []
  (let [classes (as-> api it
                  (get it "builtin_class_member_offsets")
                  (filter #(= (get % "build_configuration") "float_64") it)
                  (first it)
                  (get it "classes"))]
    (map struct-like-class-m->build-file-export-m classes)))

(defn make-builtin-class-file-export-ms []
  (->> (get api "builtin_classes")
       (filter #(not (#{"Nil" "bool" "int" "float" "String"}
                      (get % "name"))))
       (map (fn [m]
         (let [classname (resolve-arg-type (get m "name"))]
           {:filename (str classname ".java")
            ;; TODO Implement body
            :lines (class-lines classname
                                "Object"
                                (flatten (map enum-m->lines (get m "enums"))))})))))

(defn make-global-scope-class-file-export-m []
  (let [classname (str godot-class-prefix "GlobalScope")]
    {:filename (str classname ".java")
     :lines (class-lines classname
                         "Object"
                         (flatten
                          (->> (get api "global_enums")
                               (filter (complement variant-global-enum?))
                               (map enum-m->lines))))}))

(defn make-variant-class-file-export-m []
  (let [classname (str godot-class-prefix "Variant")]
    {:filename (str classname ".java")
     :lines (class-lines classname
                         "Object"
                         (flatten
                          (->> (get api "global_enums")
                               (filter variant-global-enum?)
                               (map #(update % "name" (fn [s] (subs s (count "Variant.")))))
                               (map enum-m->lines))))}))

(defn generate-and-save-java-classes []
  (.mkdirs java-class-build-dir)
  (doseq [{:keys [filename lines]}
          (concat
           [{:filename (str dont-use-me-class-name ".java")
             :lines (class-lines dont-use-me-class-name "Object" [])}
            (make-variant-class-file-export-m)
            (make-global-scope-class-file-export-m)]
           (map normal-class-m->build-file-export-m (get api "classes"))
           (make-builtin-class-file-export-ms))]
    (spit (io/file java-class-build-dir filename)
          (str/join "\n" (concat
                          [(format "package %s;" base-package-name)
                           ""]
                          lines)))))

(comment (generate-and-save-java-classes))
