(ns fargo.cli.kubectl
  (:require
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]))

(defn run [& args]
  (let [{:keys [exit out err]} (apply sh "kubectl" args)]
    (if (zero? exit)
      (str/trim out)
      (throw (ex-info (str "Kubectl command failed: " err)
                      {:exit exit :err err :args args})))))

(defn current-context []
  (run "config" "current-context"))

(defn get-resources [kind & {:keys [namespace all-namespaces]}]
  (let [args (cond-> ["get" kind "-o" "json"]
               namespace (concat ["-n" namespace])
               all-namespaces (concat ["-A"]))]
    (apply run args)))

(defn apply-manifest [file-path & {:keys [server-side force-conflicts]}]
  (let [args (cond-> ["apply" "-f" file-path]
               server-side (conj "--server-side")
               force-conflicts (conj "--force-conflicts"))]
    (apply run args)))

(defn get-deployment-image [namespace deployment-name]
  (run "get" "deploy" deployment-name "-n" namespace "-o" "jsonpath={.spec.template.spec.containers[0].image}"))
