(ns godot-java.core-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [clojure.string :as str]
   [godot-java.core :as gdj]
   [godot-java.test-util :as test-util]))

(use-fixtures :once test-util/malli-instrumentation-fixture)

(deftest parameter-m->memory-info-test
  (let [f gdj/parameter-m->memory-info
        void-memory-info {:typeclass :void
                          :java-type "void"
                          :n-bytes 0}]
    (is (= (f nil) void-memory-info))
    (is (= (f {}) void-memory-info))
    (is (= (f {"name" "new_line_no"
               "type" "int"
               "meta" "int32"})
           {:typeclass :primitive
            :java-type "int"
            :n-bytes 4}))
    (is (= (f {"name" "username",
               "type" "String"})
           {:typeclass :opaque
            :java-type "GDString"
            :n-bytes 8}))
    (is (= (f {"name" "merge_mode",
               "type" "enum::UndoRedo.MergeMode",
               "default_value" "0"})
           {:typeclass :enum
            :java-type "GDUndoRedo.MergeMode"
            :n-bytes 8}))
    (is (= (f {"name" "msgids"
               "type" "typedarray::String"})
           {:typeclass :opaque
            :java-type "GDArray"
            :n-bytes 8}))
    (is (= (f {"name" "flags"
               "type" "bitfield::Mesh.ArrayFormat"
               "default_value" "0"})
           {:typeclass :enum
            :java-type "GDMesh.ArrayFormat"
            :n-bytes 8}))
    (is (= (f {"type" "bool"})
           {:typeclass :primitive
            :java-type "boolean"
            :n-bytes 1}))
    (is (= (f {"type" "Quaternion"})
           {:typeclass :struct-like
            :java-type "GDQuaternion"
            :n-bytes 16}))
    (is (= (f {"name" "amplitude",
               "type" "float",
               "meta" "double"})
           {:typeclass :primitive
            :java-type "double"
            :n-bytes 8}))
    (is (= (f {"type" "float"
               "meta" "float"})
           {:typeclass :primitive
            :java-type "float"
            :n-bytes 4}))
    (is (= (f {"member" "position"
               "offset" 0
               "meta" "Vector2"})
           {:typeclass :struct-like
            :java-type "GDVector2"
            :n-bytes 8}))))

(deftest method-m->classmap-method []
  (let [sample-classmap-method (gdj/method-m->classmap-method
                                {"name" "get_camera_attributes"
                                 "is_const" true
                                 "is_vararg" false
                                 "is_static" false
                                 "is_virtual" false
                                 "hash" 3921283215
                                 "return_value" {"type" "CameraAttributes"}})
        any-line-matches? (fn [pred] (not (->> (:lines sample-classmap-method)
                                               (map pred)
                                               (filter identity)
                                               empty?)))]
    (is (any-line-matches? #(str/includes? % "Memory resMem ="))
        "There should be a line that initializes resulting memory")
    (is (any-line-matches? #(str/includes? % "resMem.close()"))
        "There should be a line that frees the resulting memory")))

(deftest classmap->exportmap []
  (let [sample-classmap {:classname "Foo"}
        {:keys [filename lines]} (gdj/classmap->exportmap sample-classmap)
        exists-line-with-str? (fn [s] (not (empty? (->> lines (filter #(str/includes? % s))))))]
    (is (= filename "Foo.java"))
    (is (exists-line-with-str? "class"))
    (is (exists-line-with-str? "Foo"))
    (is (exists-line-with-str? "package"))))

(deftest apply-hooks-test []
  (let [sample-classmap {:classname "Foo"}
        new-classmap (gdj/apply-hooks sample-classmap [])]
    (is (= (:classname new-classmap) "Foo")
        "The classname should not have changed")))
