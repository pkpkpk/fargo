(ns fargo.entities
  (:require [malli.core :as m]))

(def ArgoAppIdentity
  [:enum
   "argo-cd"
   "argo-rollouts"
   "argo-workflows"
   "argocd-image-updater"
   "argo-rollouts-gw-plugin"])

(def SemVer
  [:map
   [:major :int]
   [:minor :int]
   [:patch :int]])

(def Version
  [:map
   [:app-version SemVer]
   [:chart-version {:optional true} SemVer]])

(def GitOps
  [:map
   [:branch :string]
   [:remote :string]
   [:root :string]
   [:chart :string]
   [:values {:optional true} :string]])

;(def OpType [:enum :install :upgrade :rollback :adoption-sync])

(def ArgoConfig
  [:map
   [:argo-app ArgoAppIdentity]
   [:k8s-context :string]
   [:argocd-cli
    [:map
     [:binary-path :string]]]
   [:gitops GitOps]
   [:upstream-repo-root {:optional true} :string]])

(def UpgradeType
  [:enum
   :argo-cd.upgrade/minor-major
   :argo-cd.upgrade/minor-minor
   :argo-cd.upgrade/minor-patch
   :argo-cd.upgrade/noop
   :argo-rollouts.upgrade/minor-major
   :argo-rollouts.upgrade/minor-minor
   :argo-rollouts.upgrade/minor-patch
   :argo-rollouts.upgrade/noop
   :argo-workflows.upgrade/minor-major
   :argo-workflows.upgrade/minor-minor
   :argo-workflows.upgrade/minor-patch
   :argo-workflows.upgrade/noop
   :argocd-image-updater.upgrade/minor-major
   :argocd-image-updater.upgrade/minor-minor
   :argocd-image-updater.upgrade/minor-patch
   :argocd-image-updater.upgrade/noop
   :argo-rollouts-gw-plugin.upgrade/minor-major
   :argo-rollouts-gw-plugin.upgrade/minor-minor
   :argo-rollouts-gw-plugin.upgrade/minor-patch
   :argo-rollouts-gw-plugin.upgrade/noop])

(def UpgradeCandidate
  [:map
   [:argo-app ArgoAppIdentity]
   [:current-cluster-version SemVer]
   [:current-repo-version SemVer]
   [:next-minor {:optional true} Version]
   [:highest-patch {:optional true} Version]])

(def Plan
  [:map
   [:id :string]
   [:argo-app ArgoAppIdentity]
   [:pre-upgrade-revision :string]
   [:type UpgradeType]
   [:from-version SemVer]
   [:to-version SemVer]
   [:repo-version SemVer]
   [:chart-version {:optional true} SemVer]
   [:gitops GitOps]
   [:content
    [:map
     [:changelog :string]
     [:upgrade-guide {:optional true} :string]]]])

(defn coerce
  [schema data]
  (m/coerce schema data))