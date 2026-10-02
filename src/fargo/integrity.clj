(ns fargo.integrity
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [fargo.cli.argocd :as argocd]
    [fargo.cli.kubectl :as kubectl]
    [fargo.git :as git]
    [fargo.semver :as semver]
    [taoensso.telemere :as tel]))

(defn- extract-version-from-image [image-str]
  (let [s   (str/trim image-str)
        tag (if (str/includes? s "@")
              ;; Digest reference — no tag present; caller must handle.
              (throw (ex-info "Image reference uses a digest, not a tag."
                              {:image s}))
              (last (str/split s #":")))]
    (semver/parse tag)))

(defn get-cluster-version
  [argo-app]
  (if (= argo-app "argo-rollouts-gw-plugin")
    (let [jsonpath "{.spec.template.spec.initContainers[?(@.name=='copy-gwapi-plugin')].image}"
          image    (kubectl/run "get" "deploy" "argo-rollouts" "-n" "argo-rollouts" "-o" (str "jsonpath=" jsonpath))]
      (extract-version-from-image image))
    (let [[namespace deployment] (case argo-app
                                   "argo-cd"              ["argocd" "argocd-server"]
                                   "argo-rollouts"        ["argo-rollouts" "argo-rollouts"]
                                   "argo-workflows"       ["argo-workflows" "argo-workflows-server"]
                                   "argocd-image-updater" ["argocd" "argocd-image-updater"]
                                   (throw (ex-info "Unknown argo-app" {:argo-app argo-app})))
          image (kubectl/get-deployment-image namespace deployment)]
      (extract-version-from-image image))))

(defn get-repo-chart-versions
  [chart-path]
  (let [file (io/file chart-path "Chart.yaml")]
    (if (.exists file)
      (let [content (slurp file)
            lines   (str/split-lines content)
            get-val (fn [k]
                      (some->> lines
                               (filter #(str/starts-with? % (str k ":")))
                               first
                               (#(str/split % #":" 2))
                               second
                               str/trim
                               (#(str/replace % #"^\"|\"$" ""))
                               semver/parse))]
        {:chart-version (get-val "version")
         :app-version   (get-val "appVersion")})
      (throw (ex-info "Chart.yaml not found"
                      {:path (.getAbsolutePath file)})))))

(defn assert-kubectl-context!
  [expected-context]
  (let [ctx (kubectl/current-context)]
    (when-not (= ctx expected-context)
      (throw (ex-info "SAFETY ABORT: Wrong Kubernetes context!"
                      {:current ctx :expected expected-context})))))

(defn assert-git-state!
  [{:keys [gitops]}]
  (let [repo-root   (:root gitops)
        curr-branch (git/current-branch repo-root)
        is-dirty?   (not (str/blank? (git/run-git repo-root "status" "--porcelain")))]
    (when-not (= curr-branch (:branch gitops))
      (throw (ex-info "SAFETY ABORT: Wrong Git branch!"
                      {:current curr-branch
                       :expected (:branch gitops)
                       :repo repo-root})))
    (when is-dirty?
      (throw (ex-info "SAFETY ABORT: Local Git repository is dirty!"
                      {:repo repo-root})))))

(defn assert-version-agreement!
  [{:keys [argo-app gitops]}]
  (let [repo-version    (:app-version (get-repo-chart-versions (:chart gitops)))
        cluster-version (get-cluster-version argo-app)]
    (when-not (semver/equal? repo-version cluster-version)
      (throw (ex-info "SAFETY ABORT: Version Mismatch (Shadow Desync)!"
                      {:argo-app        argo-app
                       :repo-version    repo-version
                       :cluster-version cluster-version
                       :hint            "Your local Chart.yaml and live cluster are out of agreement. Did a previous upgrade fail or was it never synced?"})))))

(defn check-cluster-health!
  "Verifies the target Argo CD application is Healthy and Synced.
   With no app-name, falls back to checking all apps (legacy behavior).
   Degraded *unrelated* apps are logged as warnings, not fatal —
   only the target app can fail the check."
  ([argocd-opts]
   (check-cluster-health! argocd-opts nil))
  ([argocd-opts app-name]
   (let [apps      (argocd/app-list-json argocd-opts)
         target    (if app-name
                     (filter #(= app-name (get-in % [:metadata :name])) apps)
                     apps)
         _         (when (and app-name (empty? target))
                     (throw (ex-info "Target application not found in Argo CD."
                                     {:app-name app-name})))
         unhealthy (filter #(not= "Healthy" (get-in % [:status :health :status]))
                           target)
         unsynced  (filter #(not= "Synced" (get-in % [:status :sync :status]))
                           target)
         errored   (filter #(seq (get-in % [:status :conditions])) target)]
     (when app-name
       (doseq [app (remove #(= app-name (get-in % [:metadata :name])) apps)
               :when (or (not= "Healthy" (get-in app [:status :health :status]))
                         (seq (get-in app [:status :conditions])))]
         (tel/log! {:level :warn
                    :data {:app    (get-in app [:metadata :name])
                           :health (get-in app [:status :health :status])}}
                   "Unrelated application is degraded; proceeding anyway.")))
     (when (or (seq unhealthy) (seq unsynced) (seq errored))
       (throw (ex-info "Cluster Health Check Failed: target application is degraded, unsynced, or has error conditions."
                       {:app-name       app-name
                        :unhealthy      (map #(get-in % [:metadata :name]) unhealthy)
                        :out-of-sync    (map #(get-in % [:metadata :name]) unsynced)
                        :with-conditions
                        (map (fn [app]
                               {:name       (get-in app [:metadata :name])
                                :conditions (get-in app [:status :conditions])})
                             errored)}))))))

(defn pre-flight-check!
  [{:keys [k8s-context gitops argocd-cli argo-app] :as config}]
  (when k8s-context
    (assert-kubectl-context! k8s-context))
  (when gitops
    (assert-git-state! config))
  (when (and argo-app gitops)
    (assert-version-agreement! config))
  (check-cluster-health! argocd-cli argo-app))