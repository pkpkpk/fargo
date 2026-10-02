(ns fargo.cli.argocd
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [jsonista.core :as j]))

;; Now requires binary-path to be passed in, or defaults to standard path if available
(defn- run [binary-path & args]
  (let [actual-binary (or binary-path "argocd")
        {:keys [exit out err]} (apply sh actual-binary args)]
    (if (zero? exit)
      (str/trim out)
      (if (and err (str/includes? err "timeout"))
        (throw (ex-info (str "ArgoCD command timed out: " err)
                        {:reason ::timeout :exit exit :err err :args args}))
        (throw (ex-info (str "ArgoCD command failed: " err)
                        {:exit exit :err err :args args}))))))

(defn app-list-json [{:keys [binary-path]}]
  (j/read-value (run binary-path "app" "list" "--core" "-o" "json") j/keyword-keys-object-mapper))

(defn admin-export
  [{:keys [binary-path output-path]}]
  (let [yaml-output (run binary-path "admin" "export" "--core")]
    (if output-path
      (do
        (io/make-parents output-path)
        (spit output-path yaml-output)
        output-path)
      yaml-output)))

(defn app-diff [{:keys [binary-path]} app-name]
  (run binary-path "app" "diff" app-name "--core"))

(defn app-sync [{:keys [binary-path]} app-name]
  (run binary-path "app" "sync" app-name "--core"))

(defn app-wait [{:keys [binary-path timeout]} app-name]
  (let [timeout-str (str (or timeout 600))]
    (run binary-path "app" "wait" app-name "--core" "--sync" "--health" "--timeout" timeout-str)))

(defn app-get-state [{:keys [binary-path]} app-name]
  (let [json-out (run binary-path "app" "get" app-name "--core" "-o" "json")
        app      (j/read-value json-out j/keyword-keys-object-mapper)]
    {:sync    (get-in app [:status :sync :status])
     :health  (get-in app [:status :health :status])
     :conditions (get-in app [:status :conditions])}))

(defn wait-for-state
  "Blocks until app is synced+healthy or timeout. Returns the final
   observed state: {:sync ... :health ... :conditions ...}."
  [{:keys [binary-path timeout] :as opts} app-name]
  (let [timeout-str (str (or timeout 600))]
    (try
      (let [out (run binary-path "app" "wait" app-name "--core" "--sync" "--health"
                     "--timeout" timeout-str "-o" "json")
            app (j/read-value out j/keyword-keys-object-mapper)]
        {:sync       (get-in app [:status :sync :status])
         :health     (get-in app [:status :health :status])
         :conditions (get-in app [:status :conditions])})
      (catch Exception e
        (if (= ::timeout (:reason (ex-data e)))
          (app-get-state opts app-name)
          (throw e))))))
