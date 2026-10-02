(ns fargo.updater.ops.planner
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [fargo.entities :as entities]
    [fargo.git :as git]
    [fargo.integrity :as integrity]
    [fargo.semver :as semver]
    [fargo.state :as state]))

(defn determine-upgrade-type [argo-app from-v to-v]
  (let [base-type (cond
                    (semver/major-upgrade? from-v to-v) :upgrade/minor-major
                    (semver/minor-upgrade? from-v to-v) :upgrade/minor-minor
                    (semver/patch-upgrade? from-v to-v) :upgrade/minor-patch
                    :else :upgrade/noop)]
    (keyword (str argo-app ".upgrade") (name base-type))))

(defn validate-upgrade! [from-v to-v]
  (semver/validate-sequential-upgrade! from-v to-v))

(defn extract-changelog [repo-root target-version]
  (let [changelog-path (io/file repo-root "CHANGELOG.md")
        target-string  (semver/string target-version)]
    (if (.exists changelog-path)
      (let [lines              (str/split-lines (slurp changelog-path))
            header-prefix-1    (str "## v" target-string)
            header-prefix-2    (str "## [v" target-string)
            is-target-header?  (fn [line]
                                 (or (str/starts-with? line header-prefix-1)
                                     (str/starts-with? line header-prefix-2)))
            is-any-header?     (fn [line]
                                 (or (str/starts-with? line "## v")
                                     (str/starts-with? line "## [v")))
            start-idx          (first (keep-indexed #(when (is-target-header? %2) %1)
                                                    lines))]
        (if start-idx
          (let [end-idx (or (first (keep-indexed
                                     #(when (and (> %1 start-idx)
                                                 (is-any-header? %2))
                                        %1)
                                     lines))
                            (count lines))]
            (str/join "\n" (subvec lines start-idx end-idx)))
          (throw (ex-info "Changelog entry not found for target version."
                          {:target-version target-string}))))
      (throw (ex-info "CHANGELOG.md not found in repo root."
                      {:repo-root repo-root})))))

(defn extract-upgrade-guide [repo-root from-v to-v]
  (let [guide-name (str (:major from-v) "." (:minor from-v) "-"
                        (:major to-v) "." (:minor to-v) ".md")
        guide-path (io/file repo-root "docs/operator-manual/upgrading" guide-name)]
    (if (.exists guide-path)
      (slurp guide-path)
      (throw (ex-info "Upgrade guide not found."
                      {:expected-path (.getAbsolutePath guide-path)})))))

(defn generate-plan [argo upgrade-candidate upgrade-key]
  (let [{:keys [argo-app] :as argo} (entities/coerce entities/ArgoConfig argo)
        upgrade-candidate            (entities/coerce entities/UpgradeCandidate upgrade-candidate)
        target                       (get upgrade-candidate upgrade-key)]
    (when-not (#{:next-minor :highest-patch} upgrade-key)
      (throw (ex-info "Invalid upgrade selection."
                      {:upgrade-key upgrade-key
                       :allowed #{:next-minor :highest-patch}})))
    (when-not target
      (throw (ex-info "Selected upgrade is not available."
                      {:upgrade-key upgrade-key
                       :upgrade upgrade-candidate})))
    (state/check-resumable! (get-in argo [:gitops :root]))
    (integrity/assert-kubectl-context! (:k8s-context argo))
    (let [cluster-version (:current-cluster-version upgrade-candidate)
          repo-version    (:current-repo-version upgrade-candidate)
          target-version  (:app-version target)
          chart-version   (:chart-version target)
          upgrade-type    (determine-upgrade-type
                            argo-app
                            cluster-version
                            target-version)
          content-root    (or (:upstream-repo-root argo)
                              (get-in argo [:gitops :root]))]
      (validate-upgrade! cluster-version target-version)
      (let [changelog-content
            (try
              (extract-changelog content-root target-version)
              (catch Exception _
                (let [minor-prefix (str (:major target-version)
                                        "."
                                        (:minor target-version))]
                  (str "No specific notes for "
                       (semver/string target-version)
                       ". Check "
                       minor-prefix
                       " line."))))
            upgrade-guide-content
            (when (or (= (name upgrade-type) "minor-minor")
                      (= (name upgrade-type) "minor-major"))
              (try
                (extract-upgrade-guide
                  content-root
                  cluster-version
                  target-version)
                (catch Exception _ nil)))]
        (entities/coerce
          entities/Plan
          (cond-> {:id                   (str "upg-plan-" (System/currentTimeMillis))
                   :argo-app             argo-app
                   :pre-upgrade-revision (git/rev-parse (get-in argo [:gitops :root]) "HEAD")
                   :type                 upgrade-type
                   :from-version         cluster-version
                   :to-version           target-version
                   :repo-version         repo-version
                   :gitops               (:gitops argo)
                   :content              (cond-> {:changelog changelog-content}
                                                 upgrade-guide-content
                                                 (assoc :upgrade-guide upgrade-guide-content))}
                  chart-version
                  (assoc :chart-version chart-version)))))))