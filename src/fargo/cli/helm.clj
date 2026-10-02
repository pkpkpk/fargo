(ns fargo.cli.helm
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn- run [& args]
  (let [{:keys [exit out err]} (apply sh "helm" args)]
    (if (zero? exit)
      (str/trim out)
      (throw (ex-info (str "Helm command failed: " err)
                      {:exit exit :err err :args args})))))

(defn template
  [chart-path & extra-args]
  (apply run "template" chart-path extra-args))

(defn repo-add
  "Adds a helm repository."
  [repo-name repo-url]
  (run "repo" "add" repo-name repo-url))

(defn repo-update
  "Updates local helm repository cache."
  []
  (run "repo" "update"))

(defn search-repo
  "Searches a helm repository. Optionally returns output as JSON."
  [query & {:keys [versions json]}]
  (let [args (cond-> ["search" "repo" query]
               versions (conj "--versions")
               json (conj "--output" "json"))]
    (apply run args)))
