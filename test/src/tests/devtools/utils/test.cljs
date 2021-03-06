(ns devtools.utils.test
  (:require [cljs.test :refer-macros [is]]
            [clojure.walk :refer [postwalk]]
            [goog.array :as garr]
            [goog.json :as json]
            [goog.object :as gobj]
            [devtools.format :as f]
            [devtools.prefs :refer [pref]]))

; taken from https://github.com/purnam/purnam/blob/62bec5207621779a31c5adf3593530268aebb7fd/src/purnam/native/functions.cljs#L128-L145
; Copyright © 2014 Chris Zheng
(defn js-equals [v1 v2]
  (if (= v1 v2) true
                (let [t1 (js/goog.typeOf v1)
                      t2 (js/goog.typeOf v2)]
                  (cond (= "array" t1 t2)
                        (garr/equals v1 v2 js-equals)

                        (= "object" t1 t2)
                        (let [ks1 (.sort (js-keys v1))
                              ks2 (.sort (js-keys v2))]
                          (if (garr/equals ks1 ks2)
                            (garr/every
                              ks1
                              (fn [k]
                                (js-equals (aget v1 k) (aget v2 k))))
                            false))
                        :else
                        false))))

(defn replace-refs [template placeholder]
  (let [filter (fn [key value] (if (= key "object") placeholder value))]
    (json/parse (json/serialize template filter))))

(defn replace-configs [template placeholder]
  (let [filter (fn [key value] (if (= key "config") placeholder value))]
    (json/parse (json/serialize template filter))))

(defn collect-refs [template]
  (let [refs (atom [])
        catch-next (atom false)
        filter (fn [_ value]
                 (when @catch-next
                   (reset! catch-next false)
                   (reset! refs (conj @refs value)))
                 (if (= value "object") (reset! catch-next true))
                 value)]
    (json/serialize template filter)
    @refs))

; note: not perfect just ad-hoc for our cases
(defn plain-js-obj? [o]
  (and (object? o) (not (coll? o))))

(defn want? [value expected]
  (is (= (f/want-value? value)) expected)
  (if expected
    (str (pr-str value) " SHOULD be processed by devtools custom formatter")
    (str (pr-str value) " SHOULD NOT be processed by devtools custom formatter")))

(defn resolve-prefs [v]
  (postwalk #(if (keyword? %) (pref %) %) v))

(defn remove-empty-styles [v]
  (let [empty-style-remover (fn [x]
                              (if (and (map? x) (= (get x "style") ""))
                                (dissoc x "style")
                                x))]
    (postwalk empty-style-remover v)))

(defn unroll-fns [v]
  (if (vector? v)
    (mapcat (fn [item] (if (fn? item) (unroll-fns (item)) [(unroll-fns item)])) v)
    v))

(defn is-template [template expected & callbacks]
  (let [sanitized-template (-> template
                               (replace-refs "##REF##")
                               (replace-configs "##CONFIG##"))
        refs (collect-refs template)
        expected-template (-> expected
                              (unroll-fns)
                              (resolve-prefs)
                              (remove-empty-styles)
                              (clj->js))]
    (is (js-equals sanitized-template expected-template))
    (when-not (empty? callbacks)
      (is (= (count refs) (count callbacks)) "number of refs and callbacks does not match")
      (loop [rfs refs
             cbs callbacks]
        (when-not (empty? cbs)
          (let [rf (first rfs)
                object (aget rf "object")
                config (aget rf "config")
                cb (first cbs)]
            (cb object config)
            (recur (rest rfs) (rest cbs))))))))

(defn patch-circular-references [obj & [parents]]
  (if (goog/isObject obj)
    (if (some #(identical? obj %) parents)
      "##CIRCULAR##"
      (let [new-parents (conj parents obj)]
        (doseq [key (gobj/getKeys obj)]
          (let [val (gobj/get obj key)
                patched-val (patch-circular-references val new-parents)]
            (if-not (identical? val patched-val)
              (gobj/set obj key patched-val))))
        obj))
    obj))

(defn safe-data-fn [f]
  (fn [value]
    (-> value
        (f)
        (patch-circular-references))))

; note: custom formatters api can return circular data structures when feeded with circular input data
;       we are not interested in exploring cycles, so these safe- methods remove cycles early on
(def safe-header (safe-data-fn f/header))
(def safe-body (safe-data-fn f/body))

(defn is-header [value expected & callbacks]
  (apply is-template (safe-header value) expected callbacks))

(defn is-body [value expected & callbacks]
  (apply is-template (safe-body value) expected callbacks))

(defn has-body? [value expected]
  (is (= (f/has-body value) expected)
      (if expected
        (str (pr-str value) " SHOULD return true to hasBody call")
        (str (pr-str value) " SHOULD return false to hasBody call"))))

(defn unroll [& args]
  (apply partial (concat [mapcat] args)))