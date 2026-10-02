(ns fargo.updater
  (:require
    [clojure.java.io :as io]
    [fargo.state :as state]
    [fargo.updater.ops.approval :as approval]
    [fargo.updater.ops.apply :as apply]
    [fargo.updater.ops.discover :as discover]
    [fargo.updater.ops.planner :as planner]
    [taoensso.telemere :as tel]
    [taoensso.telemere.files :as tel-files]))

(defn log-to-file!
  "Persists logs to a specified file, creating required directories."
  ([file-path]
   (log-to-file! file-path ::global-logger nil))
  ([file-path handler-id]
   (log-to-file! file-path handler-id nil))
  ([file-path handler-id dispatch-opts]
   (io/make-parents file-path)
   (let [opts    (merge-with merge
                             {:ns-filter {:allow #{"fargo.*"}}}
                             dispatch-opts)
         handler (tel-files/handler:file {:path file-path})]
     (tel/add-handler! handler-id handler opts)
     (tel/log! {:level :info
                :data  {:file       file-path
                        :handler-id handler-id}}
               "Fargo file logging initialized"))))

(defn discover-upgrades
  "Discovers available upgrades for an Argo application."
  [argo]
  (discover/discover-upgrades argo))

(defn generate-plan
  "Generates an upgrade plan from a discovered upgrade.

   upgrade-key selects the target version:
   :next-minor selects the next minor release;
   :highest-patch selects the highest available patch release."
  [argo upgrade upgrade-key]
  (planner/generate-plan argo upgrade upgrade-key))

(defn approval-plan!
  "Grants and persists approval for an upgrade plan."
  [user argo plan]
  (approval/grant-approval! user argo plan))

(defn clear-approvals
  "Clears persisted upgrade approvals."
  [argo]
  (state/clear-approvals! argo))

(defn check-plan-approval
  "Returns true when an upgrade plan has valid approval."
  [argo plan]
  (try
    (approval/assert-approval! argo plan)
    true
    (catch Exception _
      false)))

(defn stage!
  "Stages an approved upgrade in the GitOps repository."
  [argo plan]
  (apply/stage-upgrade! argo plan))

(defn commit!
  "Commits and pushes a staged upgrade."
  [argo plan]
  (apply/commit-upgrade! argo plan))

(defn apply-plan!
  "Stages, commits, pushes, and observes an approved upgrade."
  [argo plan]
  (apply/apply-upgrade! argo plan))

(defn resume-plan!
  "Resumes an upgrade with previously recorded resumable state."
  [argo]
  (apply/resume-upgrade! argo))

(defn rollback-plan!
  "Rolls back a failed upgrade and observes the rollback."
  [argo plan]
  (apply/rollback-upgrade! argo plan))

(defn clear-state!
  "Clears all local Fargo state."
  [argo]
  (state/clear-all! argo))

(comment
  (log-to-file!
    (io/file (System/getProperty "user.home") "fargo.logs")))