
Currently __fargo__ scripts upgrading helm charts for existing Argo applications:

+ [argocd](https://github.com/argoproj/argo-cd)
+ [argocd-image-updater](https://github.com/argoproj-labs/argocd-image-updater)
+ [argo-rollouts](https://github.com/argoproj/argo-rollouts)
+ [argo-workflows](https://github.com/argoproj/argo-workflows)
+ [rollouts-plugin-trafficrouter-gatewayapi](https://github.com/argoproj-labs/rollouts-plugin-trafficrouter-gatewayapi)

## prereqs

+ kubectl
+ local build of argocd cli
+ helm cli
+ for gw plugin:
  + local clone of [the plugin repo](https://github.com/argoproj-labs/rollouts-plugin-trafficrouter-gatewayapi)
  + values.yaml files 


### define an Argo config map

```clojure
(def argocd-config
  (let [base-dir "/home/user/dev"
        repo-root (str base-dir "/projects/foo")]
    {:argo-app "argo-cd"
     :k8s-context "gke_foo-123_us-east1_foo-123-gke"
     :argocd-cli {:binary-path (str base-dir "/host.org.repo/github.com/argoproj/argo-cd/dist/argocd")}
     :gitops {:branch "main"
              :remote "origin"
              :root repo-root
              :chart (str repo-root "/k8s/vendor/argo-cd")}}))
```

For image-updater/workflows/rollouts only `[:gitops :chart]` vendoring path needs to change. Chart updates track helm only.  

Both the values file and the upstream repo are only relevant for updating the gateway plugin

```clojure
(def gateway-plugin-config
  (let [base-dir   "/home/user/dev"
        repo-root (str base-dir "/projects/foo")]
    {:argo-app "argo-rollouts-gw-plugin"
     :k8s-context "gke_foo-123_us-east1_foo-123-gke"
     :argocd-cli {:binary-path (str base-dir "/host.org.repo/github.com/argoproj/argo-cd/dist/argocd")}
     :upstream-repo-root (str base-dir "/host.org.repo/github.com/argoproj-labs/rollouts-plugin-trafficrouter-gatewayapi")
     :gitops {:root repo-root
              :branch "main"
              :remote "origin"
              :chart (str repo-root "/k8s/vendor/argo-rollouts")
              :values (str repo-root "/k8s/vendor/argo-rollouts/values-foo.yaml")}}))
```

## Upgrading

### 1) discover upgrades `(fargo.updater/discover-upgrades argo) -> Upgrade`

This will return map holding a description of the current version and the next available minor or patch release in the upgrade sequence

### 2) create a plan `(fargo.updater/generate-plan argo, Upgrade, upgrade-key) -> Plan`

Creates a plan for either the next minor release or the highest available patch release.

### 3) approve the plan `(fargo.updater/approval-plan! user argo plan)`

The upgrade process chains content hashes of configs, plans and approvals to commits.
Approvals are written to `.fargo/approvals/<plan-hasch>.edn`; they tie the current repo commit to an upgrade plan. Approved plans cannot proceed if anything has changed, and plans cannot proceed if they have not been approved. New templates will not be staged if the repository has changed after approval.

### 4) apply the upgrade `(fargo.updater/apply-plan! argo plan)`

Stages the planned change, commits and pushes it, then syncs the Argo application and observes the result.

### resuming indeterminate state `(fargo.updater/resume-plan! argo)`

During observation, timeouts or network failures can leave the cluster state indeterminate. Fargo records what is needed to resume observation later. While resumable state exists, new plans and upgrades are blocked.

Call `resume-plan!` to resume observation without creating or applying a new plan.

### rollback `(fargo.updater/rollback-plan! argo plan)`

Use `rollback-plan!` to revert the upgrade commit and sync the application back to the pre-upgrade revision.
