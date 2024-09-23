(ns build.recurse-keys)

;; Written mostly by AI,
;; but had to fix the first function. :-)
(defn replace-keywords-in-vector [m v]
  (mapv (fn [x]
          (if (and
               (keyword? x)
               (string? (get m x x))
               )
            (get m x x)
            x)) v))

(defn merge-strings-in-vector [v]
  (if (every? string? v)
    (clojure.string/join "" v)
    v))

(defn process-vector [m v]
  (->> v
       (replace-keywords-in-vector m)
       merge-strings-in-vector))

(defn process-map [m]
  (let [new-m (reduce-kv (fn [acc k v]
                           (if (vector? v)
                             (assoc acc k (process-vector m v))
                             (assoc acc k v)))
                         {} m)]
    new-m))

(defn recurse-keys
  "Until no futher change possible"
  [m]
  (loop [current-m m]
    (let [new-m (process-map current-m)]
      (if (= current-m new-m)
        current-m
        (recur new-m)))))

;; Example usage:
(comment
  (recurse-keys
   {:a "hello" :b ["world" :a] :c [:a :b] :d ["foo" "bar"]})
  ;; =>
  {:a "hello", :b "worldhello", :c "helloworldhello", :d "foobar"}
  ;;
  )
