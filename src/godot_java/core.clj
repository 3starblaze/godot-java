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

;; NOTE: Some parameters have reserved words as names (e.g. interface, char) and thus I decided
;; that it is easier to prefix all parameters to avoid the problem.
(def godot-parameter-prefix "gd_")

(def java-source-file-root (io/file "./src/main/java/godot_java"))
(def godot-wrapper-class-package-name "godot_java.Godot")
(def godot-wrapper-class-output-dir (io/file java-source-file-root "Godot"))

(def godot-build-configuration "float_64")
(def void-pointer-size (if (or (= godot-build-configuration "float_64")
                               (= godot-build-configuration "double_64"))
                         8
                         4))

(defn godot-classname->java-classname [classname]
  (str "GD" classname))

(def string-name-java-classname
  (godot-classname->java-classname "StringName"))

(def struct-like-classes-set
  (as-> api it
    (get it "builtin_class_member_offsets")
    (filter #(= (get % "build_configuration") godot-build-configuration) it)
    (first it)
    (get it "classes")
    (map #(get % "name") it)
    (map godot-classname->java-classname it)
    (set it)))

(def singleton-set
  (->> (get api "singletons")
       ;; NOTE: Name always matches the type, so we just take that info
       (map #(get % "type"))
       (map godot-classname->java-classname)
       set))

(def size-mappings
  (as-> api it
    (get it "builtin_class_sizes")
    (filter #(= (get % "build_configuration") godot-build-configuration) it)
    (first it)
    (get it "sizes")
    (map #(vector (get % "name") (get % "size")) it)
    (into {} it)))

(defn indent-line [s]
  (str "    " s))

(def dont-use-me-class-name (godot-classname->java-classname "DontUseMe"))

(defn sanitize-method-name [s]
  (case s
    ;; NOTE: new is a reserved word
    "new" "gd_new"
    ;; NOTE: wait is a final method defined in java.lang.Object
    "wait" "gd_wait"
    s))

(def preamble-lines
  "Lines that should be inserted in the beginning of each file (after package name)."
  ["import com.sun.jna.Memory;"
   "import com.sun.jna.Pointer;"
   "import java.util.Collections;"
   "import java.util.Map;"
   "import java.util.HashMap;"
   ""])

(def meta-type->java-type
  {"float" "float"
   "double" "double"
   "uint8" "byte"
   "uint16" "short"
   "uint32" "int"
   "uint64" "long"
   "int8" "byte"
   "int16" "short"
   "int32" "int"
   "int64" "long"})

(def java-type->bytes
  {"float" 4
   "double" 8
   "boolean" 1
   "byte" 1
   "short" 2
   "int" 4
   "long" 8})


;; NOTE: Initially String was also part of this map but I don't want to handle automatic String
;; conversion right now, so I omitted String
(def godot-type-overrides
  {"Nil" "void"
   "bool" "boolean"
   "int" "long"
   "float" "double"})

(defn parameter-m->java-type [m]
  (let [type-meta (get m "meta")
        type-type (get m "type")
        overriden-type (get godot-type-overrides type-type)
        fetched-size (get size-mappings type-type)]
    (cond
      (nil? m) "void"
      type-meta (get meta-type->java-type type-meta)
      overriden-type overriden-type
      fetched-size fetched-size
      ;; NOTE: It's a C pointer, not handling that
      (str/includes? type-type "*") dont-use-me-class-name
      :else (let [[prefix t] (let [res (str/split (get m "type") #"::" 2)]
                               (if (= (count res) 2)
                                 [(first res) (second res)]
                                 [nil (first res)]))]
              (godot-classname->java-classname
               (case prefix
                 "typedarray" "Array"
                 "enum" (if (global-enum-set t) (str "GlobalScope." t) t)
                 t))))))

(defn parameter-m->memory-info
  "Return a tuple of [typeclass java-type byte-count] for given parameter map.

  typeclass is either:
  - :void -- void
  - :primitive -- basic numeric type
  - :opaque -- an object instance (void pointer)
  - :enum -- Java class, Godot int64
  - :struct-like -- Java class, Godot struct"
  [m]
  (let [type-meta (get m "meta")
        type-type (get m "type")
        overriden-type (get godot-type-overrides type-type)
        fetched-size (get size-mappings type-type)]
    (cond
      (nil? m) [:void "void" 0]
      type-meta (let [t (get meta-type->java-type type-meta)]
                  [:primitive t (get java-type->bytes t)])
      overriden-type (if (= overriden-type "String")
                       [:opaque "String" void-pointer-size]
                       [:primitive overriden-type (get java-type->bytes overriden-type)])
      fetched-size (let [t (godot-classname->java-classname type-type)]
                     (if (struct-like-classes-set t)
                       [:struct-like t fetched-size]
                       [:opaque t fetched-size]))
      ;; NOTE: It's a C pointer, not handling that
      (str/includes? type-type "*") [:opaque dont-use-me-class-name void-pointer-size]
      :else (let [[prefix t] (let [res (str/split (get m "type") #"::" 2)]
                               (if (= (count res) 2)
                                 [(first res) (second res)]
                                 [nil (first res)]))
                  handle-enum (fn [t]
                                [:enum
                                 (godot-classname->java-classname
                                  (if (global-enum-set t) (str "GlobalScope." t) t))
                                 (get java-type->bytes "long")])]
              (case prefix
                "typedarray" [:opaque (godot-classname->java-classname "Array") void-pointer-size]
                "enum" (handle-enum t)
                "bitfield" (handle-enum t)
                [:opaque (godot-classname->java-classname t) void-pointer-size])))))

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

(defn get-native-address-get-set-lines [classname]
  (concat
   ["private Pointer nativeAddress;"]
   (block-lines (str "public " classname "(Pointer p)")
                ["nativeAddress = p;"])
   (block-lines "public Pointer getNativeAddress()"
                ["return nativeAddress;"])))

(defn args-info->s [args-info]
  (str/join ", " (map #(format "%s %s" (:type %) (:name %)) args-info)))

(defn normal-constant-m->line [m]
  (format "public final static long %s = %s;" (get m "name") (get m "value")))

(defn string-name-cache-field-name [s]
  (str "stringNameCache" s))

(defn method-bind-cache-field-name [s]
  (str "methodBindCache" s))

(defn normal-class-m->string-names-to-cache [m]
  (map #(vector (get % "name") (get % "hash")) (get m "methods")))

(def self-class-string-name-cache-field-name "selfClassStringName")

(defn method-m->lines [m]
  (let [[ret-typeclass ret-java-type ret-bytes] (parameter-m->memory-info (get m "return_value"))
        args-memory-info (map parameter-m->memory-info (get m "arguments"))
        args-names (map #(convert-parameter-name (get % "name")) (get m "arguments"))
        arg-count (count (get m "arguments"))
        memory-alloc-string (fn [var-name n]
                              (if (zero? n)
                                (format "Memory %s = null;" var-name)
                                (format "Memory %s = new Memory(%s);" var-name n)))
        method-bind (method-bind-cache-field-name (get m "name"))
        i->arg-mem-var #(str "arg" %)]
    (block-lines (format "%s(%s)"
                         (->> ["public"
                               (when (get m "is_static") "static")
                               (when-not (get m "is_virtual") "final")
                               ret-java-type
                               (sanitize-method-name (get m "name"))]
                              (filter (complement nil?))
                              (str/join " "))
                         (args-info->s (map (fn [[_ java-type _] arg-name]
                                              {:name arg-name
                                               :type java-type})
                                            args-memory-info
                                            args-names)))
                 (do
                   (assert (not (nil? ret-bytes)) (str/join ":" [ret-typeclass ret-java-type ret-bytes]))
                   (concat
                    [(memory-alloc-string "args" (* void-pointer-size arg-count))
                     (memory-alloc-string "res" ret-bytes)]
                    (->
                     (map (fn [i [arg-typeclass arg-java-type byte-count] arg-name]
                            (let [mem-var-name (i->arg-mem-var i)]
                              [(memory-alloc-string mem-var-name byte-count)
                               (format "args.setPointer(%s, %s);"
                                       (* i void-pointer-size) mem-var-name)
                               (case arg-typeclass
                                 :primitive (if (= arg-java-type "boolean")
                                              (format "%s.setByte(0, (byte)(%s ? 1 : 0));"
                                                      mem-var-name
                                                      arg-name)
                                              (format "%s.set%s(0, %s);"
                                                      mem-var-name
                                                      (str/capitalize arg-java-type)
                                                      arg-name))
                                 :struct-like (format "%s.intoMemory(%s, 0);" arg-name mem-var-name)
                                 :enum (format "%s.setLong(0, %s.value);" mem-var-name arg-name)
                                 :opaque (format "%s.setPointer(0, %s);"
                                                 mem-var-name
                                                 (str arg-name ".getNativeAddress()")))]))
                          (range)
                          args-memory-info
                          args-names)
                     flatten)
                    [(format "bridge.pointerCall(%s, %s, args, res);"
                             method-bind
                             ;; NOTE: Static methods shouldn't care about the instance we pass
                             (if (get m "is_static") "null" "nativeAddress"))]
                    (case ret-typeclass
                      :void []
                      [(str ret-java-type
                            " javaResult = "
                            (case ret-typeclass
                              :primitive (if (= ret-java-type "boolean")
                                           "res.getByte(0) != 0;"
                                           (format "res.get%s(0);" (str/capitalize ret-java-type)))
                              :opaque (format "new %s(res.getPointer(0));" ret-java-type)
                              :enum (format "%s.fromValue(res.getLong(0));" ret-java-type)
                              :struct-like (format "new %s(res, 0);" ret-java-type)))])
                    (map (fn [i] (str (i->arg-mem-var i) ".close();")) (range arg-count))
                    (when-not (zero? arg-count) ["args.close();"])
                    (when-not (zero? ret-bytes) ["res.close();"])
                    (when-not (zero? ret-bytes) ["return javaResult;"]))))))

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
                                                     "Value could not be converted to an enum!")])
                               ["return res;"]))))))

(defn normal-class-m->cache-field-lines [m]
  (concat
   ["private static boolean is_initialized = false;"
    (format "private static %s %s = null;"
            string-name-java-classname
            self-class-string-name-cache-field-name)]
   (map #(format "private static %s %s = null;"
                 string-name-java-classname
                 (string-name-cache-field-name (get % "name")))
        (get m "methods"))
   (map #(format "private static Pointer %s = null;"
                 (method-bind-cache-field-name (get % "name")))
        (get m "methods"))))

(defn normal-class-m->build-file-export-m [m]
  (let [classname (godot-classname->java-classname (get m "name"))
        ;; NOTE: Virtual methods don't have hashes and thus cannot be called by users
        methods (filter #(not (get % "is_virtual")) (get m "methods"))
        singleton? (singleton-set classname)]
    {:filename (str classname ".java")
     :lines (concat
             ["import godot_java.GodotBridge;"
              ""]
             (class-lines classname
                          (if-let [inherits (get m "inherits")]
                            (godot-classname->java-classname inherits)
                            "Object")
                          (-> (concat
                               (map normal-constant-m->line (get m "constants"))
                               (normal-class-m->cache-field-lines m)
                               ["private static boolean isInitialized = false;"
                                "private static GodotBridge bridge = null;"
                                "private Pointer nativeAddress;"]
                               (when singleton?
                                 (concat
                                  [(format "private static %s singletonInstance = null;" classname)]
                                  (block-lines (format "public %s getInstance()" classname)
                                               ["return singletonInstance;"])))
                               (block-lines "public Pointer getNativeAddress()"
                                            ["return nativeAddress;"])
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
                                             (when singleton?
                                               [(format
                                                 "Pointer singletonAddress = bridge.loadSingleton(%s);"
                                                 self-class-string-name-cache-field-name)
                                                (format
                                                 "singletonInstance = new %s(singletonAddress);"
                                                 classname)])
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
  (let [classname (godot-classname->java-classname (get m "name"))
        members (as-> api it
                  (get it "builtin_class_member_offsets")
                  (filter #(= (get % "build_configuration") godot-build-configuration) it)
                  (first it)
                  (get it "classes")
                  (filter #(= (get % "name") (get m "name")) it)
                  (first it)
                  (get it "members"))
        members-info (map (fn [member]
                            (let [m-meta (get member "meta")
                                  m-name (get member "member")
                                  offset (get member "offset")

                                  [primitive? typename]
                                  (if-let [t (get meta-type->java-type m-meta)]
                                    [true t]
                                    [false (godot-classname->java-classname m-meta)])]
                              {:member-def-string (format "public %s %s;" typename m-name)
                               :init-string (str m-name " = "
                                                 (format (if primitive?
                                                           "m.get%s(offset + %s);"
                                                           "new %s(m, %s);")
                                                         (if primitive?
                                                           (str/capitalize typename)
                                                           typename)
                                                         offset))
                               :memory-set-string (format (if primitive?
                                                            (format "m.set%s(offset + %s, %s);"
                                                                    (str/capitalize typename)
                                                                    offset
                                                                    m-name)
                                                            (format "%s.intoMemory(m, %s);"
                                                                    m-name
                                                                    offset)))}))
                          members)]
    (concat
     (map :member-def-string members-info)
     (block-lines (format "%s(Memory m, long offset)" classname)
                  (map :init-string members-info))
     (block-lines "public void intoMemory(Memory m, long offset)"
                  (map :memory-set-string members-info))
     (block-lines "public Memory intoMemory()"
                  [(format "Memory m = new Memory(%s);" (get size-mappings (get m "name")))
                   "intoMemory(m, 0);"
                   "return m;"]))))

(defn make-builtin-class-file-export-ms []
  (->> (get api "builtin_classes")
       (filter #(not ((set (keys godot-type-overrides))
                      (get % "name"))))
       (map (fn [m]
              (let [classname (godot-classname->java-classname (get m "name"))
                    filename (str classname ".java")]
                {:filename filename
                 :lines (class-lines
                         classname
                         "Object"
                         (flatten
                          (concat
                           (map enum-m->lines (get m "enums"))
                           (if (struct-like-classes-set classname)
                             (make-struct-like-class-lines m)
                             (get-native-address-get-set-lines classname)))))})))))

(defn make-global-scope-class-file-export-m []
  (let [classname (godot-classname->java-classname "GlobalScope")]
    {:filename (str classname ".java")
     :lines (class-lines classname
                         "Object"
                         (flatten
                          (->> (get api "global_enums")
                               (filter (complement variant-global-enum?))
                               (map enum-m->lines))))}))

(defn make-variant-class-file-export-m []
  (let [classname (godot-classname->java-classname "Variant")]
    {:filename (str classname ".java")
     :lines (class-lines classname
                         "Object"
                         (flatten
                          (concat
                           (->> (get api "global_enums")
                                (filter variant-global-enum?)
                                (map #(update % "name" (fn [s] (subs s (count "Variant.")))))
                                (map enum-m->lines))
                           (get-native-address-get-set-lines classname))))}))

(defn make-godot-bridge-class-file-export-m [gd-classnames]
  (let [classname "GodotBridge"
        preloaded-functions ["object_method_bind_call"
                             "classdb_get_method_bind"
                             "string_name_new_with_utf8_chars"
                             "object_method_bind_ptrcall"
                             "classdb_construct_object"
                             "global_get_singleton"]
        invoke-pointer-lines (fn [method args]
                               (concat
                                [(str method ".invokePointer(new Object[]{")]
                                (map indent-line (map #(str % ",") args))
                                ["});"]))]
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
                             (map #(format "%s.initialize(this);" (godot-classname->java-classname %))
                                  gd-classnames)))
               (block-lines "public Function getGodotFunction(String s)"
                            [(str "return Function.getFunction"
                                  "(pGetProcAddress.invokePointer(new Object[]{ s }));")])
               (block-lines (format "public %s stringNameFromString(String s)"
                                    (godot-classname->java-classname "StringName"))
                            (concat
                             [(format "Memory mem = new Memory(%s);" (get size-mappings "StringName"))]
                             (invoke-pointer-lines "string_name_new_with_utf8_chars" ["mem" "s"])
                             [(format "return new %s(mem);" string-name-java-classname)]))
               (block-lines (format (str "public Pointer getMethodBind"
                                         "(%s classname, %s methodName, long hash)")
                                    string-name-java-classname
                                    string-name-java-classname)
                            (invoke-pointer-lines "return classdb_get_method_bind"
                                                  ["classname" "methodName" "hash"]))
               (block-lines (format "public Pointer getNewInstancePointer(%s string_name)"
                                    string-name-java-classname)
                            (invoke-pointer-lines "return classdb_construct_object"
                                                  ["string_name"]))
               (block-lines (format "public void pointerCall(%s)"
                                    (str/join ", " ["Pointer methodBind"
                                                    "Pointer nativeAddress"
                                                    "Memory args"
                                                    "Memory res"]))
                            (invoke-pointer-lines "object_method_bind_ptrcall"
                                                  ["methodBind"
                                                   "nativeAddress"
                                                   "args.getPointer(0)"
                                                   "res.getPointer(0)"]))
               (block-lines (format "public Pointer loadSingleton(%s name)"
                                    string-name-java-classname)
                            (invoke-pointer-lines "return global_get_singleton"
                                                  ["name.getNativeAddress()"])))))}))

(defn make-dont-use-me-class-file-export-m []
  (let [classname dont-use-me-class-name]
    {:filename (str classname ".java")
     :lines (concat
             ["// This is a dummy class for types that could not be converted (C pointers)"]
             (class-lines classname "Object"
                          (get-native-address-get-set-lines classname)))}))

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
           [(make-dont-use-me-class-file-export-m)
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
