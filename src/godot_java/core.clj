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

(def godot-class-prefix "GD")

;; NOTE: Some parameters have reserved words as names (e.g. interface, char) and thus I decided
;; that it is easier to prefix all parameters to avoid the problem.
(def godot-parameter-prefix "gd_")

(def java-source-file-root (io/file "./src/main/java/godot_java"))
(def godot-wrapper-class-package-name "godot_java.Godot")
(def godot-wrapper-class-output-dir (io/file java-source-file-root "Godot"))

(def godot-build-configuration "float_64")

(def struct-like-classes-set
  (as-> api it
    (get it "builtin_class_member_offsets")
    (filter #(= (get % "build_configuration") godot-build-configuration) it)
    (first it)
    (get it "classes")
    (map #(get % "name") it)
    (set it)))

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

(def preamble-lines
  "Lines that should be inserted in the beginning of each file (after package name)."
  ["import com.sun.jna.Memory;"
   "import com.sun.jna.Pointer;"
   "import java.util.Collections;"
   "import java.util.Map;"
   "import java.util.HashMap;"
   ""])

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

(defn block-lines [header lines]
  (concat [(format "%s {" header)]
          (map indent-line lines)
          ["}"]))

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
  ;; TODO Handle bitfields
  (block-lines (str "public enum " (get m "name"))
               (concat
                (let [n (count (get m "values"))]
                  (->> (get m "values")
                       ;: Make enum member definition line
                       (map #(format "%s(%s)"
                                     (get % "name")
                                     ;; NOTE: We add "L" to tell Java that we are using longs
                                     (str (get % "value") "L")))
                       ;; Add a semicolon for the last line and comma for other lines
                       (map-indexed (fn [i s] (str s (if (= i (dec n)) ";" ","))))))
                ;; NOTE: Long with big L because we have to use classes for types
                [(format "private static Map<Long, %s> reverseMapping;" (get m "name"))]
                (block-lines "static"
                             (concat
                              [(format "Map<Long, %s> tmp = new HashMap();" (get m "name"))]
                              ;; NOTE: "L" because of Java long
                              (map #(format "tmp.put(%sL, %s);" (get % "value") (get % "name"))
                                   (get m "values"))
                              ["reverseMapping = Collections.unmodifiableMap(tmp);"]))
                (concat
                 ["public final long value;"
                  ""]
                 (block-lines (format "private %s(long value)" (get m "name"))
                              ["this.value = value;"])
                 [""]
                 (block-lines (format "public static %s fromValue(long v)" (get m "name"))
                              (concat
                               [(format "%s res = reverseMapping.get(v);" (get m "name"))]
                               (block-lines "if (res == null)"
                                            [(format "throw new IllegalArgumentException(\"%s\");"
                                                     "Value could not be converted to an enum!")])))))))

(defn normal-constant-m->line [m]
  (format "public final static long %s = %s;" (get m "name") (get m "value")))

(defn string-name-cache-field-name [s]
  (str "stringNameCache" s))

(defn method-bind-cache-field-name [s]
  (str "methodBindCache" s))

(defn normal-class-m->string-names-to-cache [m]
  (map #(vector (get % "name") (get % "hash")) (get m "methods")))

(def self-class-string-name-cache-field-name "selfClassStringName")

(defn normal-class-m->cache-field-lines [m]
  ;; HACK: Hardcoded StringName classname
  (concat
   ["private static boolean is_initialized = false;"
    (format "private static GDStringName %s = null;" self-class-string-name-cache-field-name)]
   (map #(format "private static GDStringName %s = null;"
                 (string-name-cache-field-name (get % "name")))
        (get m "methods"))
   (map #(format "private static Pointer %s = null;"
                 (method-bind-cache-field-name (get % "name")))
        (get m "methods"))))

(defn normal-class-m->build-file-export-m [m]
  (let [classname (str godot-class-prefix (get m "name"))
        ;; NOTE: Virtual methods don't have hashes and thus cannot be called by users
        methods (filter #(not (get % "is_virtual")) (get m "methods"))]
    {:filename (str classname ".java")
     :lines (concat
             ["import godot_java.GodotBridge;"
              ""]
             (class-lines classname
                          (if-let [inherits (get m "inherits")]
                            (resolve-arg-type inherits)
                            "Object")
                          (-> (concat
                               (map normal-constant-m->line (get m "constants"))
                               (normal-class-m->cache-field-lines m)
                               ["private static boolean isInitialized = false;"
                                "private static GodotBridge bridge = null;"
                                "private Pointer nativeAddress;"]
                               ;; NOTE: Internal constructor for turning raw pointers into
                               ;; usable Java instances
                               (block-lines (format "public %s(Pointer p)" classname)
                                            (concat
                                             ;; NOTE: Java requires calling super constructor
                                             (when (get m "inherits") ["super(p);"])
                                             ["nativeAddress = p;"]))
                               (block-lines (format "%s %s()"
                                                    (if (get m "is_instantiable") "public" "private")
                                                    classname)
                                            (concat
                                             ;; NOTE: Hacky way to avoid the shackles of having to
                                             ;; call the super constructor as the first line
                                             (when (get m "inherits") ["super(null);"])
                                             [(format "nativeAddress = bridge.getNewInstancePointer(%s);"
                                                      self-class-string-name-cache-field-name)]))
                               (block-lines "public static void initialize(GodotBridge bridge)"
                                            (concat
                                             [(format "%s = bridge.stringNameFromString(\"%s\");"
                                                      self-class-string-name-cache-field-name
                                                      (get m "name"))]
                                             (map #(format "%s = bridge.stringNameFromString(\"%s\");"
                                                           (string-name-cache-field-name (get % "name"))
                                                           (get % "name"))
                                                  methods)
                                             ;; NOTE: adding L because hash is int64
                                             (map #(format "%s = bridge.getMethodBind(%s, %s, %sL);"
                                                           (method-bind-cache-field-name (get % "name"))
                                                           self-class-string-name-cache-field-name
                                                           (string-name-cache-field-name (get % "name"))
                                                           (get % "hash"))
                                                  methods)
                                             ["is_initialized = true;"]))
                               (map enum-m->lines (get m "enums"))
                               (map method-m->lines methods))
                              flatten)))}))

(defn make-struct-like-class-lines [m]
  (let [classname (str godot-class-prefix (get m "name"))
        members (as-> api it
                  (get it "builtin_class_member_offsets")
                  (filter #(= (get % "build_configuration") godot-build-configuration) it)
                  (first it)
                  (get it "classes")
                  (filter #(= (get % "name") (get m "name")) it)
                  (first it)
                  (get it "members"))
        resolve-meta #(case %
                        "float" "float"
                        "int32" "int"
                        (str godot-class-prefix %))
        member-init-string #(str (get % "member")
                                 " = "
                                 (case (get % "meta")
                                   "float" (format "m.getFloat(offset + %s);" (get % "offset"))
                                   "int32" (format "m.getInt(offset + %s);" (get % "offset"))
                                   (format "new %s(m, %s);"
                                           (str godot-class-prefix (get % "meta"))
                                           (get % "offset"))))]
    (concat
     (map #(format "public %s %s;"
                   (resolve-meta (get % "meta"))
                   (get % "member")) members)
     (block-lines (format "%s(Memory m, long offset)" classname)
                  (map member-init-string members)))))

(defn make-builtin-class-file-export-ms []
  (->> (get api "builtin_classes")
       (filter #(not (#{"Nil" "bool" "int" "float" "String"}
                      (get % "name"))))
       (map (fn [m]
              (let [classname (resolve-arg-type (get m "name"))
                    filename (str classname ".java")]
                ;; NOTE: Not the cleanest approach but will do for now
                (if (= (get m "name") "StringName")
                  {:filename filename
                   :lines (class-lines classname
                                       "Object"
                                       (concat
                                        (map enum-m->lines (get m "enums"))
                                        ["private Pointer nativeAddress;"]
                                        (block-lines (str "public " classname "(Pointer p)")
                                                     ["nativeAddress = p;"])
                                        (block-lines "public Pointer getNativeAddress()"
                                                     ["return nativeAddress;"])))}
                  {:filename filename
                   :lines (class-lines classname
                                       "Object"
                                       (concat
                                        (flatten (map enum-m->lines (get m "enums")))
                                        (when (struct-like-classes-set (get m "name"))
                                          (make-struct-like-class-lines m))))}))))))

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

(defn make-godot-bridge-class-file-export-m [gd-classnames]
  (let [classname "GodotBridge"
        preloaded-functions ["object_method_bind_call"
                             "classdb_get_method_bind"
                             "string_name_new_with_utf8_chars"
                             "object_method_bind_ptrcall"
                             "classdb_construct_object"]]
    {:filename (str classname ".java")
     :lines (concat
             ["import com.sun.jna.Function;"
              "import com.sun.jna.Memory;"
              "import com.sun.jna.Pointer;"
              (format "import %s.*;" godot-wrapper-class-package-name)]
             (class-lines
              classname "Object"
              (concat
               ["private Function pGetProcAddress;"
                "private Pointer pLibrary;"]
               (map #(format "private Function %s;" %) preloaded-functions)
               (block-lines (str classname "(Function pGetProcAddress, Pointer pLibrary)")
                            (concat
                             ["this.pGetProcAddress = pGetProcAddress;"
                              "this.pLibrary = pLibrary;"
                              ""]
                             (map #(format "%s = getGodotFunction(\"%s\");" % %)
                                  preloaded-functions)
                             (map #(format "%s.initialize(this);" (resolve-arg-type %))
                                  gd-classnames)))
               (block-lines "public Function getGodotFunction(String s)"
                            [(str "return Function.getFunction"
                                  "(pGetProcAddress.invokePointer(new Object[]{ s }));")])
              ;; FIXME: Hardcoded StringName classname
               (block-lines "public GDStringName stringNameFromString(String s)"
                           ;; FIXME: Hardcoded StringName size
                            ["Memory mem = new Memory(8);"
                             (str "string_name_new_with_utf8_chars"
                                  ".invokePointer(new Object[]{ mem, s });")
                             "return new GDStringName(mem);"])
               (block-lines (str "public Pointer getMethodBind"
                                 "(GDStringName classname, GDStringName methodName, long hash)")
                            [(str "return classdb_get_method_bind.invokePointer"
                                  "(new Object[]{ classname, methodName, hash });")])
               (block-lines "public Pointer getNewInstancePointer(GDStringName string_name)"
                            ["return new Pointer(classdb_construct_object(string_name));"]))))}))

(defn generate-and-save-java-classes []
  (.mkdirs godot-wrapper-class-output-dir)
  (let [{:keys [filename lines]}
        (make-godot-bridge-class-file-export-m (map #(get % "name") (get api "classes")))]
    (spit (io/file java-source-file-root filename)
          (str/join "\n" (concat
                          ["package godot_java;"
                           ""]
                          lines))))
  (doseq [{:keys [filename lines]}
          (concat
           [{:filename (str dont-use-me-class-name ".java")
             :lines (class-lines dont-use-me-class-name "Object" [])}
            (make-variant-class-file-export-m)
            (make-global-scope-class-file-export-m)]
           (map normal-class-m->build-file-export-m (get api "classes"))
           (make-builtin-class-file-export-ms))]
    (spit (io/file godot-wrapper-class-output-dir filename)
          (str/join "\n" (concat
                          [(format "package %s;" godot-wrapper-class-package-name)
                           ""]
                          preamble-lines
                          lines)))))

(comment (generate-and-save-java-classes))
