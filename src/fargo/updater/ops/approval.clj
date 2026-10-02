(ns fargo.updater.ops.approval
  (:require
    [clojure.java.io :as io]
    [fargo.git :as git]
    [fargo.semver :as semver]
    [fargo.state :as state]
    [hasch.core :as hasch]
    [taoensso.telemere :as tel])
  (:import
    (java.time Instant)))

(defn plan-hash [plan]
  (hasch/uuid (dissoc plan :id)))

(defn grant-approval! [user config plan]
  (let [h                (plan-hash plan)
        repo-root        (get-in config [:gitops :root])
        git-remote       (get-in config [:gitops :remote])
        argo-app         (:argo-app config)
        file             (state/approval-file config h)
        current-revision (git/rev-parse repo-root "HEAD")]
    (io/make-parents file)
    (spit file
          (pr-str
            {:approved-at    (Instant/now)
             :user           user
             :plan-hash      h
             :argo-app       argo-app
             :to-version     (semver/string (:to-version plan))
             :repo-revision  current-revision}))
    (git/run-git repo-root "add" "-f" (.getAbsolutePath file))
    (git/commit repo-root (str "chore(" argo-app "): grant approval for upgrade to " (semver/string (:to-version plan)) " [" h "]"))
    (tel/log! :info (str "Pushing approval [" h "] to " git-remote "..."))
    (git/push repo-root git-remote)
    (tel/log! :info "Approval granted and persisted to Git.")))

(defn assert-approval!
  [argo plan]
  (let [repo-root        (.getAbsolutePath
                           (io/file (get-in argo [:gitops :root])))
        gitops-revision  (git/rev-parse repo-root "HEAD")
        plan-hash        (plan-hash plan)
        approval-path    (state/approval-file argo plan-hash)
        approval         (state/read-approval argo plan-hash)
        parent-revision  (git/rev-parse repo-root "HEAD^")]
    (when-not approval
      (throw (ex-info "Plan is not approved!"
                      {:plan-hash plan-hash})))
    (when-not (= parent-revision (:repo-revision approval))
      (throw (ex-info "Repository changed after plan approval."
                      {:approval-revision parent-revision
                       :current-revision gitops-revision})))
    (when-not (= plan-hash (:plan-hash approval))
      (throw (ex-info "Approved plan does not match."
                      {:plan-hash plan-hash
                       :approval-plan-hash (:plan-hash approval)
                       :approval-revision gitops-revision})))
    (.getPath approval-path)))
