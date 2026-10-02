(ns fargo.updater.ops.apply
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [fargo.cli.argocd :as argocd]
    [fargo.entities :as entities]
    [fargo.git :as git]
    [fargo.integrity :as integrity]
    [fargo.semver :as semver]
    [fargo.state :as state]
    [fargo.updater.ops.approval :as approval]
    [taoensso.telemere :as tel]))

(defn- update-chart-yaml [chart-path new-version target-version]
  (let [file    (io/file chart-path "Chart.yaml")
        _       (assert (.isAbsolute file)
                        (str "Chart.yaml path must be absolute: " (.getAbsolutePath file)))
        content (slurp file)
        _       (assert (= 1 (count (re-seq #"(?m)^version:" content)))
                        (str "Expected exactly one 'version:' key in " (.getAbsolutePath file)))
        _       (assert (= 1 (count (re-seq #"(?m)^appVersion:" content)))
                        (str "Expected exactly one 'appVersion:' key in " (.getAbsolutePath file)))
        updated (-> content
                    (str/replace #"(?m)^version:\s*.*$"
                                 (str "version: " (semver/string new-version)))
                    (str/replace #"(?m)^appVersion:\s*.*$"
                                 (str "appVersion: v" (semver/string target-version))))]
    (spit file updated)
    (tel/log! {:level :info
               :data  {:file              (.getAbsolutePath file)
                       :new-chart-version (semver/string new-version)
                       :new-app-version   (semver/string target-version)}}
              "Updated Chart.yaml")))

(defn- update-values-yaml
  [values-path target-version]
  (let [file    (io/file values-path)
        _       (assert (.isAbsolute file)
                        (str "values.yaml path must be absolute: " (.getAbsolutePath file)))
        content (slurp file)
        pattern #"(?m)^(\s*)image:\s*ghcr\.io/argoproj-labs/rollouts-plugin-trafficrouter-gatewayapi:v.*$"
        matches (re-seq pattern content)
        _       (assert (= 1 (count matches))
                        (str "Expected exactly one plugin image line in "
                             (.getAbsolutePath file) ", found " (count matches)))
        updated (str/replace
                  content
                  pattern
                  (str "$1image: ghcr.io/argoproj-labs/rollouts-plugin-trafficrouter-gatewayapi:v"
                       (semver/string target-version)))]
    (spit file updated)
    (tel/log! {:level :info
               :data  {:file               (.getAbsolutePath file)
                       :new-plugin-version (semver/string target-version)}}
              "Updated values.yaml plugin image")))

(defn- succeed!
  [{:as argo} approval target-app-version]
  (tel/log! :info "Sync Complete. Executing Post-Flight Integrity Checks...")
  (integrity/pre-flight-check! argo)
  (tel/log! :info "Upgrade Successful. Cluster is healthy. Cleaning up...")
  (state/delete-success-state! argo approval target-app-version)
  (tel/log! :info "Done."))

(defn- abort-resumable!
  [{:as argo} plan upgrade-commit pre-upgrade-revision cluster-state]
  (let [record {:plan                 plan
                :argo                 argo
                :upgrade-commit       upgrade-commit
                :pre-upgrade-revision pre-upgrade-revision
                :cluster-state        cluster-state
                :recorded-at          (System/currentTimeMillis)}]
    (state/write-resumable! argo record)
    record))

(defn- observe-cluster
  [{:keys [argo-app argocd-cli] :as argo}
   plan
   approval
   pre-upgrade-revision
   upgrade-commit]
  (try
    (let [{:keys [health] :as cluster-state}
          (do
            (argocd/app-sync argocd-cli argo-app)
            (tel/log! :info "Sync initiated. Observing until determinate...")
            (argocd/wait-for-state argocd-cli argo-app))]
      (if (= "Healthy" health)
        (succeed! argo approval (:to-version plan))
        (abort-resumable! argo plan upgrade-commit pre-upgrade-revision cluster-state)))
    (catch Exception e
      (tel/log! {:level :error
                 :data {:exception e}}
                "Error syncing, cluster is in unknown state, writing resumable")
      (abort-resumable!
        argo
        plan
        upgrade-commit
        pre-upgrade-revision
        {:sync ::unknown
         :health ::unknown
         :error (.getMessage e)}))))

(defn rollback-upgrade!
  [{:keys [argo-app argocd-cli]
    {:keys [root remote]} :gitops
    :as argo}
   plan]
  (let [repo-root            (.getAbsolutePath (io/file root))
        git-remote            remote
        upgrade-commit        (:upgrade-commit plan)
        target-app-version    (:to-version plan)
        pre-upgrade-revision  (:pre-upgrade-revision plan)]
    (when-not upgrade-commit
      (throw (ex-info "Cannot rollback plan without upgrade commit."
                      {:plan plan})))
    (git/run-git repo-root "revert" "--no-commit" upgrade-commit)
    (git/commit repo-root
                (str "revert(" argo-app "): rollback failed upgrade to v"
                     (semver/string target-app-version)))
    (git/push repo-root git-remote)
    (tel/log! :info
              (str "Rollback committed and pushed for "
                   argo-app " v" (semver/string target-app-version)))
    (let [{:keys [health] :as rollback-state}
          (do
            (argocd/app-sync argocd-cli argo-app)
            (tel/log! :info "Sync initiated. Observing until determinate...")
            (argocd/wait-for-state argocd-cli argo-app))]
      (when-not (= "Healthy" health)
        (throw
          (ex-info
            "Rollback did not restore a healthy cluster."
            {:state rollback-state
             :pre-upgrade-revision pre-upgrade-revision})))
      (tel/log! :info
                (str "Rollback complete: "
                     argo-app
                     " restored to revision "
                     pre-upgrade-revision)))))

(defn stage-upgrade!
  [argo plan]
  (let [{:keys [argo-app] :as argo} (entities/coerce entities/ArgoConfig argo)
        {:keys [to-version] :as plan} (entities/coerce entities/Plan plan)
        {:keys [root chart values]} (:gitops argo)
        repo-root (.getAbsolutePath (io/file root))]
    (state/check-resumable! argo)
    (approval/assert-approval! argo plan)
    (integrity/pre-flight-check! argo)
    (tel/log! ["Staging GitOps Upgrade for "
               argo-app
               " from "
               (:from-version plan)
               " to "
               to-version])
    (if (str/ends-with? argo-app "plugin")
      (let [values-path (.getAbsolutePath (io/file values))]
        (update-values-yaml values-path to-version)
        (git/run-git repo-root "add" "-f" values-path))
      (let [chart-path    (.getAbsolutePath (io/file chart))
            chart-version (:chart-version plan)
            chart-file    (.getAbsolutePath (io/file chart-path "Chart.yaml"))]
        (update-chart-yaml chart-path chart-version to-version)
        (git/run-git repo-root "add" "-f" chart-file)))
    (tel/log! :info "Upgrade staged for review.")))

(defn commit-upgrade!
  [{:keys [gitops] :as argo} plan]
  (let [repo-root (.getAbsolutePath (io/file (:root gitops)))
        git-remote (:remote gitops)]
    (assert (= (:pre-upgrade-revision plan)
               (git/rev-parse repo-root "HEAD"))
            "Repository HEAD has changed since the upgrade plan was created.")
    (assert (not= (git/run-git repo-root "write-tree")
                  (git/rev-parse repo-root "HEAD^{tree}"))
            "No changes are staged.")
    (git/commit repo-root
                (str "upgrade("
                     (:argo-app plan)
                     "): v"
                     (semver/string (:from-version plan))
                     " -> v"
                     (semver/string (:to-version plan))))
    (git/push repo-root git-remote)
    (git/rev-parse repo-root "HEAD")))

(defn apply-upgrade!
  "given approval, stage -> commit -> watch"
  [argo plan]
  (let [approval (approval/assert-approval! argo plan)]
    (stage-upgrade! argo plan)
    (let [upgrade-commit (commit-upgrade! argo plan)]
      (observe-cluster
        argo
        plan
        approval
        (:pre-upgrade-revision plan)
        upgrade-commit))))

(defn resume-upgrade!
  "resync and observe, on success cleans up state files"
  [argo]
  (let [resumable (state/read-resumable argo)]
    ;; TODO vet hashes
    (when-not resumable
      (throw (ex-info "No resumable upgrade state found." {})))
    (let [{:keys [plan upgrade-commit pre-upgrade-revision]} resumable
          approval (approval/assert-approval! argo plan)]
      (observe-cluster
        argo
        plan
        approval
        pre-upgrade-revision
        upgrade-commit))))