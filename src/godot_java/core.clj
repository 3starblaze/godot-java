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
  "Return a map of {:keys [typeclass java-type n-bytes]} for given parameter map.

  typeclass is either:
  - :void -- void
  - :primitive -- basic numeric type
  - :opaque -- an object instance (void pointer)
  - :enum -- Java class, Godot int64
  - :struct-like -- Java class, Godot struct"
  [{:strs [meta type] :as m}]
  (let [overriden-type (get godot-type-overrides type)
        fetched-size (get size-mappings type)
        wrap-res (fn [a b c] {:typeclass a :java-type b :n-bytes c})
        wrap-enum (fn [t] (wrap-res :enum
                                    (godot-classname->java-classname
                                     (if (global-enum-set t) (str "GlobalScope." t) t))
                                    (get java-type->bytes "long")))
        wrap-opaque (fn [b c] (wrap-res :opaque b c))
        wrap-opaque-auto (fn [t] (wrap-opaque (godot-classname->java-classname t) void-pointer-size))
        [prefix t] (let [res (str/split (or type "") #"::" 2)]
                     (if (= (count res) 2)
                       [(first res) (second res)]
                       [nil (first res)]))]
    (cond
      (empty? m) (wrap-res :void "void" 0)
      meta (if-let [t (get meta-type->java-type meta)]
             (wrap-res :primitive t (get java-type->bytes t))
             ;; NOTE: If we can't find in the map, we just resolve it as a regular type. This
             ;; is handy for processing struct-like class member info.
             (parameter-m->memory-info {"type" meta}))
      overriden-type (wrap-res :primitive overriden-type (get java-type->bytes overriden-type))
      fetched-size (let [t (godot-classname->java-classname type)]
                     (if (struct-like-classes-set t)
                       (wrap-res :struct-like t fetched-size)
                       (wrap-opaque t fetched-size)))
      ;; NOTE: It's a C pointer, not handling that
      (str/includes? type "*") (wrap-opaque dont-use-me-class-name void-pointer-size)
      (= prefix "typedarray") (wrap-opaque-auto "Array")
      (= prefix "enum") (wrap-enum t)
      (= prefix "bitfield") (wrap-enum t)
      :else (wrap-opaque-auto t))))

(defn get-memory-var-map [memory-var native-var offset-expression type-m]
  (merge {:memory-var memory-var
          :native-var native-var
          :offset-expression offset-expression}
         (parameter-m->memory-info type-m)))

;; NOTE: Some parameters have reserved words as names (e.g. interface, char) and thus I decided
;; that it is easier to prefix all parameters to avoid the problem.
(defn convert-parameter-name [s]
  (str "gd_" s))

(defn block-lines [header lines]
  (concat [(format "%s {" header)]
          (map indent-line lines)
          ["}"]))

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

(defn concat-in [m in-xs xs]
  (update-in m in-xs concat xs))

(defn native-address-hook [hookmap]
  (-> hookmap
      (concat-in [:classmap :fields] [{:modifiers #{"private"}
                                       :type "Pointer"
                                       :name "nativeAddress"}])
      (concat-in [:classmap :constructors] [(native-address-constructor-with-lines
                                             ["nativeAddress = p;"])])
      (concat-in [:classmap :methods] [{:return-type "Pointer"
                                        :modifiers #{"public"}
                                        :name "getNativeAddress"
                                        :args []
                                        :lines ["return nativeAddress;"]}])))

(defn native-address-child-hook [hookmap]
  (concat-in hookmap [:classmap :constructors]
             [(native-address-constructor-with-lines ["super(p);"])]))

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
          (concat-in [:classmap :fields] [(merge default-hook-field-map
                                                 {:name self-class-string-name-cache-field-name
                                                  :type string-name-java-classname})
                                          (flatten (map :fields method-infos))])
          (concat-in [:hook-lines] [(format "%s = bridge.stringNameFromString(\"%s\");"
                                            self-class-string-name-cache-field-name
                                            godot-classname)
                                    (flatten (map :hook-lines method-infos))])))))

(defn singleton-hook [hookmap]
  (if-let [classname (singleton-set (get-in hookmap [:classmap :classname]))]
    (-> hookmap
        (concat-in [:classmap :fields] [(merge default-hook-field-map
                                               {:type classname
                                                :name "singletonInstance"})])
        (concat-in [:classmap :methods] [{:modifiers #{"public" "static"}
                                          :return-type classname
                                          :name "getInstance"
                                          :lines ["return singletonInstance;"]}])
        (concat-in [:hook-lines] [(format
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

(defn alloc-bytes-line [memory-var n-bytes]
  (if (zero? n-bytes)
    (format "Memory %s = null;" memory-var)
    (format "Memory %s = new Memory(%s);" memory-var n-bytes)))

(defn prepend-assignment-left-hand-side [{:keys [java-type native-var]} s]
  (format "%s %s = %s" java-type native-var s))

(defmulti get-memory-alloc-line :typeclass)
(defmethod get-memory-alloc-line :default [{:keys [memory-var n-bytes]}]
  (alloc-bytes-line memory-var n-bytes))

(defmulti get-memory-dealloc-line :typeclass)
(defmethod get-memory-dealloc-line :void [_]
  "")
(defmethod get-memory-dealloc-line :default [{:keys [memory-var]}]
  (str memory-var ".close();"))


(defmulti get-memory-getter-expression :typeclass)

(defmethod get-memory-getter-expression :void
  [_]
  "")

(defmethod get-memory-getter-expression :primitive
  [{:keys [java-type memory-var offset-expression]}]
  (if (= java-type "boolean")
    (format "(%s.getByte(%s) != 0)" memory-var offset-expression)
    (format "(%s.get%s(%s))" memory-var (str/capitalize java-type) offset-expression)))

(defmethod get-memory-getter-expression :opaque
  [{:keys [java-type memory-var offset-expression]}]
  (format "(new %s(%s.getPointer(%s)))" java-type memory-var offset-expression))

(defmethod get-memory-getter-expression :enum
  [{:keys [java-type memory-var offset-expression]}]
  (format "(%s.fromValue(%s.getLong(%s)))" java-type memory-var offset-expression))

(defmethod get-memory-getter-expression :struct-like
  [{:keys [java-type memory-var offset-expression]}]
  (format "(new %s(%s, %s))" java-type memory-var offset-expression))

(defn get-memory-getter-line [{:keys [typeclass java-type native-var] :as m}]
  (if (= typeclass :void)
    ";"
    (format "%s %s = %s;" java-type native-var (get-memory-getter-expression m))))

(defmulti get-memory-setter-line :typeclass)

(defmethod get-memory-setter-line :void [_]
  ";")

(defmethod get-memory-setter-line :primitive
  [{:keys [memory-var java-type native-var offset-expression]}]
  (if (= java-type "boolean")
    (format "%s.setByte(%s, (byte)(%s ? 1 : 0));" memory-var offset-expression native-var)
    (format "%s.set%s(%s, %s);" memory-var (str/capitalize java-type) offset-expression native-var)))

(defmethod get-memory-setter-line :struct-like
  [{:keys [native-var memory-var offset-expression]}]
  (format "%s.intoMemory(%s, %s);" native-var memory-var offset-expression))

(defmethod get-memory-setter-line :enum
  [{:keys [native-var memory-var offset-expression]}]
  (format "%s.setLong(%s, %s.value);" memory-var offset-expression native-var))

(defmethod get-memory-setter-line :opaque
  [{:keys [native-var memory-var offset-expression]}]
  (format "%s.setPointer(%s, %s.getNativeAddress());" memory-var offset-expression native-var))

(defmulti get-return-lines :typeclass)
(defmethod get-return-lines :void [_]
  [])
(defmethod get-return-lines :default [{:keys [native-var]}]
  [(format "return %s;" native-var)])

(defn get-args-lines [memory-var arg-var-maps]
  {:init (concat
          [(alloc-bytes-line memory-var (* void-pointer-size (count arg-var-maps)))]
          (map-indexed (fn [i m]
                         (format "%s.setPointer(%s, %s);"
                                 memory-var
                                 (* void-pointer-size i)
                                 (:memory-var m)))
                       arg-var-maps))
   :deinit (when-not (empty? arg-var-maps) [(str memory-var ".close();")])})

(defn method-m->classmap-method
  [{:strs [name arguments is_static is_virtual return_value]}]
  (let [arg-memory-var-maps (map-indexed
                             (fn [i arg]
                               (get-memory-var-map
                                (str "argMem" i)
                                (convert-parameter-name (get arg "name"))
                                "0"
                                arg))
                             arguments)
        res-mem-var "resMem"
        args-mem-var "argsMem"
        args-lines (get-args-lines args-mem-var arg-memory-var-maps)
        result-memory-var-map (get-memory-var-map res-mem-var "resValue" "0" return_value)]
    {:name (sanitize-method-name name)
     :return-type (:java-type result-memory-var-map)
     :modifiers #{"public" (when is_static "static") (when-not is_virtual "final")}
     :args (map (fn [{:keys [native-var java-type]}] {:name native-var :type java-type})
                arg-memory-var-maps)
     :lines (concat
             (map get-memory-alloc-line arg-memory-var-maps)
             (:init args-lines)
             (map get-memory-setter-line arg-memory-var-maps)
             [(get-memory-alloc-line result-memory-var-map)
              (format "bridge.pointerCall(%s, %s, %s, %s);"
                      (method-bind-cache-field-name name)
                      ;; NOTE: Static methods shouldn't care about the instance we pass
                      (if is_static "null" "getNativeAddress()")
                      args-mem-var
                      res-mem-var)]
             (:deinit args-lines)
             [(get-memory-getter-line result-memory-var-map)
              (get-memory-dealloc-line result-memory-var-map)]
             (map get-memory-dealloc-line arg-memory-var-maps)
             (get-return-lines result-memory-var-map))}))

(defn modifiers->s [modifiers]
  (if (set? modifiers)
    (->> [(modifiers "public")
          (modifiers "private")
          (modifiers "static")
          (modifiers "final")]
         (filter (complement nil?))
         (str/join " "))
    ""))

(defn make-args-s [args]
  (->> args
       (map (fn [{:keys [type name]}] (str type " " name)))
       (str/join ", ")
       (format "(%s)")))

(defn field-m->line [{:keys [modifiers type name value]}]
  (str/join " "
            [(modifiers->s modifiers)
             type name
             (if value (format "= %s;" value) ";")]))

(defn constructor-m->lines [classname {:keys [modifiers args lines]}]
  (block-lines
   (str (modifiers->s modifiers) " " classname (make-args-s args))
   lines))

(defn method-m->lines [{:keys [return-type modifiers args name lines]}]
  (block-lines
   (str/join " " [(modifiers->s modifiers) return-type (str name (make-args-s args))])
   lines))

(defn enum-m->lines [{:strs [values] :as m}]
  ;; TODO Handle bitfields
  (let [value-arg {:type "long" :name "value"}
        classname (get m "name")
        reverse-mapping-field {:modifiers #{"private" "static"}
                               ;; NOTE: Long with big L because we have to use classes for types
                               :type (format "Map<Long, %s>" classname)
                               :name "reverseMapping"}
        ;; NOTE: "L" because of Java long
        get-value-s #(str (get % "value") "L")]
    (block-lines (str "public enum " classname)
                 (concat
                  (let [n (count values)]
                    (->> values
                         ;: Make enum member definition line
                         (map #(str (get % "name") "(" (get-value-s %) ")"))
                         ;; Add a semicolon for the last line and comma for other lines
                         (map-indexed (fn [i s] (str s (if (= i (dec n)) ";" ","))))))
                  [(field-m->line reverse-mapping-field)]
                  (block-lines "static"
                               (concat
                                [(format "%s tmp = new HashMap();" (:type reverse-mapping-field))]
                                (map #(format "tmp.put(%s, %s);" (get-value-s %) classname) values)
                                [(str (:name reverse-mapping-field)
                                      " = Collections.unmodifiableMap(tmp);")]))
                  (concat
                   [(field-m->line (merge value-arg {:modifiers #{"public" "final"}}))
                    ""]
                   (constructor-m->lines (get m "name")
                                         {:modifiers #{"private"}
                                          :args [value-arg]
                                          :lines ["this.value = value;"]})
                   [""]
                   (method-m->lines
                    {:modifiers #{"public" "static"}
                     :return-type classname
                     :args [value-arg]
                     :lines (flatten
                             [(format "%s res = reverseMapping.get(%s);" classname (:name value-arg))
                              (block-lines "if (res == null)"
                                           [(format "throw new IllegalArgumentException(\"%s\");"
                                                    "Value could not be converted to an enum!")])
                              ["return res;"]])}))))))

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
        m-arg {:type "Memory" :name "m"}
        offset-arg {:type "long" :name "offset"}
        member-var-maps (map #(get-memory-var-map
                               (:name m-arg)
                               (get % "member")
                               (str (:name offset-arg) " + " (get % "offset"))
                               %)
                             members)]
    (fn [hookmap]
      (-> hookmap
          (concat-in [:classmap :fields]
                     (map (fn [{:keys [java-type native-var]}]
                            {:modifiers #{"public"}
                             :type java-type
                             :name native-var}) member-var-maps))
          (concat-in [:classmap :constructors]
                     [{:modifiers #{"public"}
                       :args [m-arg offset-arg]
                       ;; NOTE: Since we need to set an instance field,
                       ;; get-memory-getter-expression is used instead of
                       ;; get-memory-getter-line
                       :lines (map #(format "this.%s = %s;"
                                            (:native-var %)
                                            (get-memory-getter-expression %))
                                   member-var-maps)}])
          (concat-in [:classmap :methods]
                     [{:return-type "void"
                       :name "intoMemory"
                       :modifiers #{"public"}
                       :args [m-arg offset-arg]
                       :lines (map get-memory-setter-line member-var-maps)}])))))

(defn make-builtin-class-classmaps []
  (->> (get api "builtin_classes")
       (filter #(not ((set (keys godot-type-overrides)) (get % "name"))))
       (map (fn [m]
              (let [classname (godot-classname->java-classname (get m "name"))]
                (apply-hooks {:classname classname
                              :class-preamble-lines (flatten (map enum-m->lines (get m "enums")))}
                             (if (struct-like-classes-set classname)
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
                             [(alloc-bytes-line "mem" (get size-mappings "StringName"))]
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
  {:filename (str classname ".java")
   :lines (concat
           [(format "package %s;" godot-wrapper-class-package-name)
            ""]
           (map #(format "import %s;" %) imports)
           preamble-lines
           (block-lines (format "public class %s extends %s" classname (or parent-classname "Object"))
                        (concat
                         class-preamble-lines
                         (map field-m->line fields)
                         (flatten (map #(constructor-m->lines classname %) constructors))
                         (flatten (map method-m->lines methods)))))})

(defn generate-and-save-java-classes []
  (.mkdirs godot-wrapper-class-output-dir)
  (let [classes (get api "classes")
        classmaps (concat
                   [dont-use-me-classmap
                    variant-classmap
                    global-scope-classmap
                    (make-godot-bridge-classmap (map #(get % "name") classes))]
                   (map normal-class-m->classmap classes)
                   (make-builtin-class-classmaps))]
    (doseq [{:keys [filename lines]} (map classmap->exportmap classmaps)]
      (spit (io/file godot-wrapper-class-output-dir filename)
            (str/join "\n" lines)))))

(comment (generate-and-save-java-classes))
