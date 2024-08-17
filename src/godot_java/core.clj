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
  ["import com.sun.jna.Function;"
   "import com.sun.jna.Memory;"
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

;; NOTE: Some parameters have reserved words as names (e.g. interface, char) and thus I decided
;; that it is easier to prefix all parameters to avoid the problem.
(defn convert-parameter-name [s]
  (str "gd_" s))

(defn block-lines [header lines]
  (concat [(format "%s {" header)]
          (map indent-line lines)
          ["}"]))

(defn args-info->s [args-info]
  (str/join ", " (map #(format "%s %s" (:type %) (:name %)) args-info)))

(defn string-name-cache-field-name [s]
  (str "stringNameCache" s))

(defn method-bind-cache-field-name [s]
  (str "methodBindCache" s))

(def self-class-string-name-cache-field-name
  "selfClassStringName")

(def default-hook-field-map
  {:modifiers #{"private" "static"}
   :value "null"})

(defn native-address-constructor-with-lines [lines]
  {:args [{:type "Pointer" :name "p"}]
   :modifiers #{"public"}
   :lines lines})

(defn native-address-hook [hookmap]
  (update hookmap :classmap
          (fn [classmap]
            (-> classmap
                (update :fields concat
                        [{:modifiers #{"private"}
                          :type "Pointer"
                          :name "nativeAddress"}])
                (update :constructors concat [(native-address-constructor-with-lines
                                               ["nativeAddress = p;"])])
                (update :methods concat [{:return-type "Pointer"
                                          :modifiers #{"public"}
                                          :name "getNativeAddress"
                                          :args []
                                          :lines ["return nativeAddress;"]}])))))
(defn native-address-child-hook [hookmap]
  (update-in hookmap [:classmap :constructors] concat [(native-address-constructor-with-lines
                                                        ["super(p);"])]))

(defn make-method-bind-cacher-hook [class-m]
  (let [godot-classname (get class-m "name")
        method-infos (->> (get class-m "methods")
                          (filter #(not (get % "is_virtual")))
                          (map (fn [{:strs [name hash]}]
                                 (let [sn-field-name (string-name-cache-field-name name)
                                       mb-field-name (method-bind-cache-field-name name)]
                                   {:fields [(merge default-hook-field-map
                                                    {:name sn-field-name
                                                     :type string-name-java-classname})
                                             (merge default-hook-field-map
                                                    {:name mb-field-name
                                                     :type "Pointer"})]
                                    :hook-lines [(format "%s = bridge.stringNameFromString(\"%s\");"
                                                         sn-field-name
                                                         name)
                                                 (format "%s = bridge.getMethodBind(%s, %s, %sL);"
                                                         mb-field-name
                                                         self-class-string-name-cache-field-name
                                                         sn-field-name
                                                         hash)]}))))]
    (fn [hookmap]
      (-> hookmap
          (update-in [:classmap :fields] concat
                     [(merge default-hook-field-map
                             {:name self-class-string-name-cache-field-name
                              :type string-name-java-classname})]
                     (flatten (map :fields method-infos)))
          (update :hook-lines concat
                  [(format "%s = bridge.stringNameFromString(\"%s\");"
                           self-class-string-name-cache-field-name
                           godot-classname)]
                  (flatten (map :hook-lines method-infos)))))))

(defn singleton-hook [hookmap]
  (if-let [classname (singleton-set (get-in hookmap [:classmap :classname]))]
    (-> hookmap
        (update :classmap (fn [classmap]
                            (-> classmap
                                (update :fields concat [(merge default-hook-field-map
                                                               {:type classname
                                                                :name "singletonInstance"})])
                                (update :methods concat [{:modifiers #{"public" "static"}
                                                          :return-type classname
                                                          :name "getInstance"
                                                          :lines ["return singletonInstance;"]}]))))
        (update :hook-lines concat [(format
                                     "Pointer singletonAddress = bridge.loadSingleton(%s);"
                                     self-class-string-name-cache-field-name)
                                    (format
                                     "singletonInstance = new %s(singletonAddress);"
                                     classname)]))
    hookmap))

(defn apply-hook-on-hookmap [{:keys [classmap hook-lines] :as hookmap} [hook & hooks]]
  (if (nil? hook)
    (let [bridge-arg {:type "GodotBridge" :name "bridge"}]
      (-> classmap
          (update :fields concat (map
                                  #(merge default-hook-field-map %)
                                  [{:type "boolean" :name "isInitialized" :value "false"}
                                   bridge-arg]))
          (update :methods concat [{:modifiers #{"public" "static"}
                                    :return-type "void"
                                    :name "initialize"
                                    :args [bridge-arg]
                                    :lines (concat
                                            hook-lines
                                            ["isInitialized = true;"])}])))
    (apply-hook-on-hookmap (hook hookmap) hooks)))

(defn apply-hooks [classmap hooks]
  (apply-hook-on-hookmap {:classmap classmap} hooks))

(defn memory-alloc-string [var-name n]
  (if (zero? n)
    (format "Memory %s = null;" var-name)
    (format "Memory %s = new Memory(%s);" var-name n)))

(defn get-primitive-memory-getter-line [java-type]
  (if (= java-type "boolean")
    "res.getByte(0) != 0;"
    (format "res.get%s(0);" (str/capitalize java-type))))

(defn get-primitive-memory-setter-line [mem-var-name java-type arg-name]
  (if (= java-type "boolean")
    (format "%s.setByte(0, (byte)(%s ? 1 : 0));"
            mem-var-name
            arg-name)
    (format "%s.set%s(0, %s);"
            mem-var-name
            (str/capitalize java-type)
            arg-name)))

(defn get-result-retrieve-memory-lines [mem-var-name res-var-name parameter-m]
  (let [[ret-typeclass ret-java-type ret-bytes]
        (parameter-m->memory-info (get parameter-m "return_value"))]
    (merge
     {:java-type ret-java-type
      :init [(memory-alloc-string mem-var-name ret-bytes)]}
     (when-not (zero? ret-bytes)
       {:retrieve [(format "%s %s = %s;"
                           ret-java-type
                           res-var-name
                           (case ret-typeclass
                             :primitive (get-primitive-memory-getter-line ret-java-type)
                             :opaque (format "new %s(res.getPointer(0));" ret-java-type)
                             :enum (format "%s.fromValue(res.getLong(0));" ret-java-type)
                             :struct-like (format "new %s(res, 0);" ret-java-type)))]
        :deinit [(str mem-var-name ".close();")]
        :return [(format "return %;" res-var-name)]}))))

(defn get-args-memory-lines [args-var-name i->arg-mem-var args]
  (let [args-memory-info (map parameter-m->memory-info args)
        args-names (map #(convert-parameter-name (get % "name")) args)
        args-count (count args)]
    {:init [(memory-alloc-string args-var-name (* void-pointer-size args-count))]
     :store (flatten
             (map
              (fn [i [arg-typeclass arg-java-type byte-count] arg-name]
                (let [mem-var-name (i->arg-mem-var i)]
                  [(memory-alloc-string mem-var-name byte-count)
                   (format "%s.setPointer(%s, %s);" args-var-name (* i void-pointer-size) mem-var-name)
                   (case arg-typeclass
                     :primitive (get-primitive-memory-setter-line mem-var-name arg-java-type arg-name)
                     :struct-like (format "%s.intoMemory(%s, 0);" arg-name mem-var-name)
                     :enum (get-primitive-memory-setter-line
                            mem-var-name
                            "long"
                            (str arg-name ".value"))
                     :opaque (format "%s.setPointer(0, %s.getNativeAddress());"
                                     mem-var-name
                                     arg-name))]))
              (range)
              args-memory-info
              args-names))
     :deinit (concat
              (->> (range args-count) (map i->arg-mem-var) (map #(str % ".close();")))
              (when-not (zero? args-count) ["args.close();"]))}))

(defn method-m->classmap-method [{:strs [name arguments is_static is_virtual]}]
  (let [args-memory (get-args-memory-lines "args" #(str "arg" %) arguments)
        result-memory (get-result-retrieve-memory-lines "resMem" "resValue" arguments)]
    {:name (sanitize-method-name name)
     :return-type (:java-type result-memory)
     :modifiers #{"public" (when is_static "static") (when-not is_virtual "final")}
     :args (map (fn [arg-m]
                  {:name (convert-parameter-name (get arg-m "name"))
                   :type (second (parameter-m->memory-info arg-m))})
                arguments)
     :lines (concat
             (:init result-memory)
             (:init args-memory)
             (:store args-memory)
             [(format "bridge.pointerCall(%s, %s, args, resMem);"
                      (method-bind-cache-field-name name)
                      ;; NOTE: Static methods shouldn't care about the instance we pass
                      (if is_static "null" "getNativeAddress()"))]
             (:retrieve result-memory)
             (:deinit result-memory)
             (:deinit args-memory)
             (:return result-memory))}))

(defn enum-m->lines [m]
  ;; TODO Handle bitfields
  (block-lines (str "public enum " (get m "name"))
               (concat
                (let [n (count (get m "values"))]
                  (->> (get m "values")
                       ;: Make enum member definition line
                       (map (fn [{:strs [name value]}] (format "%s(%sL)" name value)))
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

(defn normal-class-m->classmap [m]
  (let [classname (godot-classname->java-classname (get m "name"))
        inherited-class (get m "inherits")]
    (apply-hooks
     {:classname classname
      :parent-classname (when inherited-class (godot-classname->java-classname inherited-class))
      :class-preamble-lines (flatten (map enum-m->lines (get m "enums")))
      :fields (map (fn [{:strs [name value]}]
                     {:modifiers #{"public" "static" "final"}
                      :type "long"
                      :name name
                      :value value})
                   (get m "constants"))
      :constructors (when (get m "is_instantiable")
                      [{:modifiers #{"public"}
                        :lines [(format "this(bridge.getNewInstancePointer(%s));"
                                        self-class-string-name-cache-field-name)]}])
      :methods (->> (get m "methods")
                    ;; NOTE: Virtual methods don't have hashes and thus cannot be called by users
                    (filter #(not (get % "is_virtual")))
                    (map method-m->classmap-method))}
     (concat
      [(make-method-bind-cacher-hook m)
       singleton-hook
       (if inherited-class native-address-child-hook native-address-hook)]))))

(defn make-struct-like-class-hook [m]
  (let [members (as-> api it
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
                              {:member-map {:modifiers #{"public"} :type typename :name m-name}
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
    (fn [hookmap]
      (update hookmap :classmap
              (fn [classmap]
                (-> classmap
                    (update :fields concat
                            (map :member-map members-info))
                    (update :constructors concat
                            [{:modifiers #{"public"}
                              :args [{:type "Memory" :name "m"}
                                     {:type "long"   :name "offset"}]
                              :lines (map :init-string members-info)}])
                    (update :methods concat
                            [{:return-type "void"
                  :name "intoMemory"
                  :modifiers #{"public" "static"}
                  :args [{:type "Memory" :name "m"}
                         {:type "long"   :name "offset"}]
                  :lines (map :memory-set-string members-info)}
                 {:return-type "Memory"
                  :modifiers #{"public" "static"}
                  :name "intoMemory"
                  :args []
                  :lines [(memory-alloc-string "m" (get size-mappings (get m "name")))
                          "intoMemory(m, 0);"
                          "return m;"]}])))))))

(defn make-builtin-class-classmaps []
  (->> (get api "builtin_classes")
       (filter #(not ((set (keys godot-type-overrides)) (get % "name"))))
       (map (fn [m]
              (let [classname (godot-classname->java-classname (get m "name"))]
                (apply-hooks {:classname classname
                              :class-preamble-lines (flatten (map enum-m->lines (get m "enums")))}
                             (if (struct-like-classes-set)
                               [(make-struct-like-class-hook m)]
                               [native-address-hook])))))))

(defn make-godot-bridge-classmap [gd-classnames]
  (let [classname "GodotBridge"
        constructor-args [{:type "Function" :name "pGetProcAddress"}
                          {:type "Pointer"  :name "pLibrary"}]
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
                                ["});"]))
        with-public (fn [m] (update m :modifiers #(into #{"public"} %)))]
    {:classname classname
     :fields (concat
              (map #(merge default-hook-field-map %) constructor-args)
              (map #(merge default-hook-field-map {:type "Function" :name %}) preloaded-functions))
     :constructors [{:modifiers #{"public"}
                     :args constructor-args
                     :lines (concat
                             (map #(format "this.%s = %s;" % %) (map :name constructor-args))
                             (map #(format "%s = getGodotFunction(\"%s\");" % %)
                                  preloaded-functions)
                             (map #(format "%s.initialize(this);" (godot-classname->java-classname %))
                                  gd-classnames))}]
     :methods (map with-public
                   [{:return-type "Function"
                     :name "getGodotFunction"
                     :args [{:type "String" :name "s"}]
                     :lines [(str "return Function.getFunction"
                                  "(pGetProcAddress.invokePointer(new Object[]{ s }));")]}

                    {:return-type string-name-java-classname
                     :name "stringNameFromString"
                     :args [{:type "String" :name "s"}]
                     :lines (concat
                             [(memory-alloc-string "mem" (get size-mappings "StringName"))]
                             (invoke-pointer-lines "string_name_new_with_utf8_chars" ["mem" "s"])
                             [(format "return new %s(mem);" string-name-java-classname)])}
                    {:return-type "Pointer"
                     :name "getMethodBind"
                     :args [{:type string-name-java-classname :name "classname"}
                            {:type string-name-java-classname :name "methodName"}
                            {:type "long"                     :name "hash"}]
                     :lines (invoke-pointer-lines "return classdb_get_method_bind"
                                                  ["classname" "methodName" "hash"])}
                    {:return-type "Pointer"
                     :name "getNewInstancePointer"
                     :args [{:type string-name-java-classname :name "string_name"}]
                     :lines (invoke-pointer-lines "return classdb_construct_object" ["string_name"])}
                    {:return-type "void"
                     :name "pointerCall"
                     :args [{:type "Pointer" :name "methodBind"}
                            {:type "Pointer" :name "nativeAddress"}
                            {:type "Memory"  :name "args"}
                            {:type "Memory"  :name "res"}]
                     :lines (invoke-pointer-lines "object_method_bind_ptrcall"
                                                  ["methodBind"
                                                   "nativeAddress"
                                                   "args.getPointer(0)"
                                                   "res.getPointer(0)"])}
                    {:return-type "Pointer"
                     :name "loadSingleton"
                     :args [{:type string-name-java-classname :name "name"}]
                     :lines (invoke-pointer-lines "return global_get_singleton"
                                                  ["name.getNativeAddress()"])}])}))

(def dont-use-me-classmap
  (apply-hooks {:classname dont-use-me-class-name} [native-address-hook]))

(def global-scope-classmap
  {:classname (godot-classname->java-classname "GlobalScope")
   :class-preamble-lines (->> (get api "global_enums")
                              (filter (complement variant-global-enum?))
                              (map enum-m->lines)
                              flatten)})

(def variant-classmap
  (apply-hooks
   {:classname (godot-classname->java-classname "Variant")
    :class-preamble-lines (->> (get api "global_enums")
                               (filter variant-global-enum?)
                               (map #(update % "name" (fn [s] (subs s (count "Variant.")))))
                               (map enum-m->lines)
                               flatten)}
   [native-address-hook]))

(defn classmap->exportmap
  [{:keys [imports classname parent-classname fields constructors methods class-preamble-lines]}]
  (let [modifiers->s (fn [modifiers]
                       (if (set? modifiers)
                         (->> [(modifiers "public")
                             (modifiers "private")
                             (modifiers "static")
                             (modifiers "final")]
                            (filter (complement nil?))
                            (str/join " "))
                         ""))
        make-args-s (fn [args]
                      (->> args
                           (map (fn [{:keys [type name]}] (str type " " name)))
                           (str/join ", ")
                           (format "(%s)")))]
    {:filename (str classname ".java")
     :lines (concat
             [(format "package %s;" godot-wrapper-class-package-name)
              ""]
             (map #(format "import %s;" %) imports)
             preamble-lines
             (block-lines (format "public class %s extends %s" classname (or parent-classname "Object"))
                          (concat
                           class-preamble-lines
                           (->> fields
                                (map
                                 (fn [{:keys [modifiers type name value]}]
                                   (str/join " "
                                             [(modifiers->s modifiers)
                                              type name
                                              (if value (format "= %s;" value) ";")]))))
                           (->> constructors
                                (map (fn [{:keys [modifiers args lines]}]
                                       (block-lines
                                        (str (modifiers->s modifiers) " " classname (make-args-s args))
                                        lines)))
                                flatten)
                           (->> methods
                                (map (fn [{:keys [return-type modifiers args name lines]}]
                                       (block-lines
                                        (str/join " "
                                                  [(modifiers->s modifiers)
                                                   return-type
                                                   (str name (make-args-s args))])
                                        lines)))
                                flatten))))}))

(defn generate-and-save-java-classes []
  (.mkdirs godot-wrapper-class-output-dir)
  (let [classes (get api "classes")
        classmaps (concat
                   [dont-use-me-classmap
                    variant-classmap
                    global-scope-classmap
                    (make-godot-bridge-classmap (map #(get % "name") classes))]
                   (map normal-class-m->classmap classes))]
    (doseq [{:keys [filename lines]} (map classmap->exportmap classmaps)]
      (spit (io/file godot-wrapper-class-output-dir filename)
            (str/join "\n" lines)))))

(comment (generate-and-save-java-classes))
