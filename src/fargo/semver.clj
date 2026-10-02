(ns fargo.semver
  (:refer-clojure :exclude [compare])
  (:require [clojure.string :as str]))

(def ^:private version-re
  ;; Strict MAJOR.MINOR.PATCH with optional leading "v".
  ;; No pre-release/build metadata — callers (image tags, Chart.yaml,
  ;; git tags) in this system only ever produce plain versions.
  #"v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)")

(defn parse [v]
  (let [s (str/trim (str v))
        m (re-matches version-re s)]
    (when-not m
      (throw (ex-info "Invalid semantic version."
                      {:version v})))
    {:major (parse-long (nth m 1))
     :minor (parse-long (nth m 2))
     :patch (parse-long (nth m 3))}))

(defn string [{:keys [major minor patch]}]
  (format "%d.%d.%d" major minor patch))

(defn compare [a b]
  (clojure.core/compare
    [(:major a) (:minor a) (:patch a)]
    [(:major b) (:minor b) (:patch b)]))

(defn newer? [a b]
  (pos? (compare a b)))

(defn older? [a b]
  (neg? (compare a b)))

(defn equal? [a b]
  (zero? (compare a b)))

(defn major [v]
  (:major v))

(defn minor [v]
  (:minor v))

(defn patch [v]
  (:patch v))

(defn release-line [v]
  [(:major v) (:minor v)])

(defn same-major? [a b]
  (= (:major a) (:major b)))

(defn same-minor? [a b]
  (= (release-line a) (release-line b)))

(defn next-minor? [from to]
  (and (= (:major from) (:major to))
       (= (:minor to) (inc (:minor from)))))

(defn next-major? [from to]
  (= (:major to) (inc (:major from))))

(defn patch-upgrade? [from to]
  (and (same-minor? from to)
       (> (:patch to) (:patch from))))

(defn minor-upgrade? [from to]
  (and (same-major? from to)
       (> (:minor to) (:minor from))))

(defn major-upgrade? [from to]
  (> (:major to) (:major from)))

(defn upgrade? [from to]
  (newer? to from))

(defn upgrade-type [from to]
  (cond
    (equal? from to) :noop
    (major-upgrade? from to) :major
    (minor-upgrade? from to) :minor
    (patch-upgrade? from to) :patch
    :else :unknown))

(defn validate-sequential-upgrade! [from to]
  (when (> (- (:major to) (:major from)) 1)
    (throw (ex-info "Upgrade skips a major version."
                    {:from from :to to})))
  (when (and (same-major? from to)
             (> (- (:minor to) (:minor from)) 1))
    (throw (ex-info "Upgrade skips a minor version."
                    {:from from :to to})))
  true)