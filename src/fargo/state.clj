(ns fargo.state
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [fargo.git :as git]
    [fargo.semver :as semver]
    [taoensso.telemere :as tel])
  (:import (java.io File)))

(defn- repo-root
  [argo]
  (get-in argo [:gitops :root]))

(defn- fargo-dir
  [argo]
  (io/file (repo-root argo) ".fargo"))

(defn- resumable-file
  [argo]
  (io/file (fargo-dir argo) "resumable.edn"))

#!----------------------------------------------------------------------------------------------------------------------

(defn approval-file
  [argo plan-hash]
  (io/file (fargo-dir argo) "approvals" (str plan-hash ".approved")))

(defn read-approval
  [argo plan-hash]
  (let [file (approval-file argo plan-hash)]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn clear-approvals!
  [{:keys [argo-app gitops] :as argo}]
  (let [repo-root  (.getAbsolutePath (io/file (repo-root argo)))
        git-remote (:remote gitops)
        dir        (io/file (fargo-dir argo) "approvals")
        files      (when (.exists dir)
                     (filter #(.isFile ^File %)
                             (.listFiles dir)))]
    (when (seq files)
      (doseq [file files]
        (io/delete-file file)
        (git/run-git repo-root
                     "rm"
                     "--cached"
                     "--ignore-unmatch"
                     (.getAbsolutePath file)))
      (git/commit
        repo-root
        (str "chore(" argo-app "): clear approvals"))
      (tel/log! :info
                (str "Pushing cleared approvals to " git-remote "..."))
      (git/push repo-root git-remote))
    (count files)))

#!----------------------------------------------------------------------------------------------------------------------

(defn check-resumable!
  [argo]
  (let [file (resumable-file argo)]
    (when (.exists file)
      (throw (ex-info "Upgrade currently suspended state, resumable present"
                      {:resumable file})))))

(defn write-resumable!
  [argo record]
  (let [path (resumable-file argo)]
    (io/make-parents path)
    (tel/log! :info (str "Writing resumable state to " (.getAbsolutePath path)))
    (spit path (pr-str record))))

(defn read-resumable
  [argo]
  (let [file (resumable-file argo)]
    (when (.exists file)
      (edn/read-string (slurp file)))))

#!----------------------------------------------------------------------------------------------------------------------

(defn delete-success-state!
  [{:keys [argo-app gitops] :as argo} approval target-version]
  (let [repo-root   (.getAbsolutePath (io/file (repo-root argo)))
        resumable   (resumable-file argo)
        approval    (io/file repo-root approval)
        git-remote  (:remote gitops)
        files       (filter #(.exists %) [resumable approval])]
    (when (seq files)
      (doseq [file files]
        (io/delete-file file)
        (git/run-git repo-root
                     "rm"
                     "--cached"
                     "--ignore-unmatch"
                     (.getAbsolutePath file)))
      (git/commit repo-root (str "upgrade(" argo-app "): upgraded to v" (semver/string target-version)))
      (git/push repo-root git-remote)
      (tel/log! :info (str "Upgrade complete: " argo-app " v" (semver/string target-version))))))

(defn clear-all!
  [argo]
  (let [files [(resumable-file argo) (io/file (fargo-dir argo) "approvals")]]
    (doseq [^File file files]
      (when (.exists file)
        (if (.isDirectory file)
          (doseq [child (file-seq file)]
            (when (.isFile child)
              (io/delete-file child)))
          (io/delete-file file))))))