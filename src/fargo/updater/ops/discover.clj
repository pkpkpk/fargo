(ns fargo.updater.ops.discover
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [fargo.cli.helm :as helm]
            [fargo.entities :as entities]
            [fargo.integrity :as integrity]
            [fargo.semver :as semver]
            [jsonista.core :as json]))

(defn- parse-versions [versions]
  (->> versions
       (map semver/parse)
       (reduce (fn [versions version]
                 (assoc versions (semver/string version) version))
               {})
       vals
       (sort semver/compare)
       vec))

(defn candidate-versions-from-source [repo-root]
  (let [changelog-path     (io/file repo-root "CHANGELOG.md")
        changelog-versions (if (.exists changelog-path)
                             (->> (str/split-lines (slurp changelog-path))
                                  (filter #(or (str/starts-with? % "## v")
                                               (str/starts-with? % "## [v")
                                               (re-find #"## .*Release v" %)))
                                  (keep #(re-find #"\bv?\d+\.\d+\.\d+" %))
                                  distinct)
                             [])
        git-versions       (let [res (sh/sh "git" "tag" "-l" :dir repo-root)]
                             (when-not (= 0 (:exit res))
                               (throw (ex-info "Failed to discover Git tags."
                                               {:repo-root repo-root
                                                :exit (:exit res)
                                                :err  (:err res)})))
                             (str/split-lines (:out res)))]
    (parse-versions (concat changelog-versions git-versions))))

(defn discover-plugin-upgrades
  "Sources versions from the upstream Git repository."
  [repo-root from-v]
  (let [from             (semver/parse from-v)
        versions         (candidate-versions-from-source repo-root)
        next-minor-line  [(:major from) (inc (:minor from))]
        next-minor       (some->> versions
                                  (filter #(= (semver/release-line %)
                                              next-minor-line))
                                  last
                                  (hash-map :app-version))
        highest-patch    (some->> versions
                                  (filter #(and (= (semver/release-line %)
                                                   (semver/release-line from))
                                                (semver/newer? % from)))
                                  last
                                  (hash-map :app-version))]
    {:next-minor next-minor
     :highest-patch highest-patch}))

(defn discover-chart-upgrades
  "Focused on application versions exposed by the Helm repository."
  [from-v argo-app]
  (let [from          (semver/parse from-v)
        chart-name    (str "argo/" argo-app)
        _             (helm/repo-add "argo" "https://argoproj.github.io/argo-helm")
        _             (helm/repo-update)
        json-out      (helm/search-repo chart-name :versions true :json true)
        entries       (json/read-value json-out json/keyword-keys-object-mapper)
        helm-versions (->> entries
                           (map (fn [entry]
                                  {:chart-version (semver/parse (:version entry))
                                   :app-version   (semver/parse (:app_version entry))}))
                           (sort (fn [a b]
                                   (let [app-cmp (semver/compare (:app-version a)
                                                                 (:app-version b))]
                                     (if (zero? app-cmp)
                                       (semver/compare (:chart-version a)
                                                       (:chart-version b))
                                       app-cmp)))))
        next-minor-line [(:major from) (inc (:minor from))]
        next-minor      (some->> helm-versions
                                 (filter #(= (semver/release-line (:app-version %))
                                             next-minor-line))
                                 last
                                 (select-keys [:app-version :chart-version]))
        highest-patch   (some->> helm-versions
                                 (filter #(and (= (semver/release-line (:app-version %))
                                                  (semver/release-line from))
                                               (semver/newer? (:app-version %) from)))
                                 last
                                 (select-keys [:app-version :chart-version]))]
    {:next-minor next-minor
     :highest-patch highest-patch}))

(defn get-repo-version [config]
  (if (= (:argo-app config) "argo-rollouts-gw-plugin")
    (let [values-path (get-in config [:gitops :values])
          content     (slurp values-path)
          match       (re-find
                        #"(?m)^\s*image:\s*ghcr\.io/argoproj-labs/rollouts-plugin-trafficrouter-gatewayapi:v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)\s*$"
                        content)]
      (if match
        (semver/parse (str (second match)
                           "." (nth match 3)
                           "." (nth match 4)))
        (throw (ex-info
                 "Plugin image tag not found in values file"
                 {:path values-path}))))
    (let [chart-path (get-in config [:gitops :chart])]
      (semver/parse
        (:app-version
          (integrity/get-repo-chart-versions chart-path))))))

(defn discover-upgrades [argo]
  (let [argo            (entities/coerce entities/ArgoConfig argo)
        argo-app        (:argo-app argo)
        cluster-version (integrity/get-cluster-version argo-app)
        repo-version    (get-repo-version argo)
        upstream-root   (:upstream-repo-root argo)
        {:keys [next-minor highest-patch]}
        (if (= argo-app "argo-rollouts-gw-plugin")
          (discover-plugin-upgrades upstream-root cluster-version)
          (discover-chart-upgrades cluster-version argo-app))]
    (entities/coerce
      entities/UpgradeCandidate
      {:argo-app                 argo-app
       :current-cluster-version  cluster-version
       :current-repo-version     repo-version
       :next-minor               next-minor
       :highest-patch            highest-patch})))