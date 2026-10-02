(ns fargo.git
  (:require
    [clojure.java.io :as io]
    [clojure.string :as string])
  (:import
    (java.io File InputStream StringWriter)
    (java.lang ProcessBuilder ProcessBuilder$Redirect)
    (java.util List)))

(defn- capture
  "Reads from input-stream until EOF and returns a String (or nil if 0 length)."
  [^InputStream input-stream]
  (let [writer (StringWriter.)]
    (io/copy input-stream writer)
    (let [s (string/trim (.toString writer))]
      (when-not (zero? (.length s))
        s))))

(defmacro background
  [& body]
  `(let [result# (promise)]
     (doto (Thread. (fn [] (deliver result# (do ~@body))))
       (.setDaemon true)
       (.start))
     result#))

(defn run-git
  [dir & args]
  (let [command-args (cons "git" args)
        proc-builder (doto (ProcessBuilder. ^List command-args)
                       (.directory (io/file dir))
                       (.redirectError ProcessBuilder$Redirect/INHERIT))
        proc (.start proc-builder)
        out (background (capture (.getInputStream proc)))
        exit (.waitFor proc)]
    (if (zero? exit)
      @out
      (throw (ex-info (str "Git command failed: " (string/join " " command-args))
                      {:dir dir :args args :exit exit})))))

(defn current-branch
  "Returns the name of the current git branch."
  [repo-path]
  (try
    (run-git repo-path "branch" "--show-current")
    (catch Exception _ nil)))

(defn dirty?
  "Returns true if the git repository at repo-path has uncommitted changes
   OR if the current branch is not 'main'.
   Optionally scopes the uncommitted changes check to a specific path within the repo."
  ([repo-path]
   (dirty? repo-path nil))
  ([repo-path path]
   (if (not= "main" (current-branch repo-path))
     true
     (let [args (cond-> ["status" "--porcelain"]
                        path (conj "--" path))
           out (apply run-git repo-path args)]
       (not (string/blank? out))))))

(defn fetch
  "Fetches updates from the remote."
  [repo-path]
  (run-git repo-path "fetch" "--all" "--tags" "--prune"))

(defn pull
  "Pulls updates from the remote."
  [repo-path]
  (run-git repo-path "pull"))

(defn tags
  "Returns a sorted list of tags."
  [repo-path]
  (let [out (run-git repo-path "tag" "--sort=v:refname")]
    (if (string/blank? out)
      []
      (string/split-lines out))))

(defn current-tag
  "Returns the current tag if the HEAD is exactly on a tag, otherwise nil."
  [repo-path]
  (try
    (run-git repo-path "describe" "--tags" "--exact-match" "HEAD")
    (catch Exception _ nil)))

(defn checkout
  "Checks out the specified tag or revision."
  [repo-path rev]
  (run-git repo-path "checkout" rev))

(defn create-worktree
  "Creates a new worktree for the given tag/rev at the target path."
  [repo-path rev target-path]
  (let [abs-target (.getAbsolutePath (io/file target-path))]
    (run-git repo-path "worktree" "add" "--detach" abs-target rev)))

(defn remove-worktree
  "Removes the worktree at the target path.
   repo-path should be the main repository path."
  [repo-path worktree-path]
  (let [abs-target (.getAbsolutePath (io/file worktree-path))]
    (run-git repo-path "worktree" "remove" "--force" abs-target)))

(defn prune-worktrees
  "Prunes stale worktree information."
  [repo-path]
  (run-git repo-path "worktree" "prune"))

(defn rev-parse
  "Returns the full SHA-1 for the given revision."
  [repo-path rev]
  (run-git repo-path "rev-parse" rev))

(defn- commits-between-impl
  "Returns a list of commit summaries between two revisions, optionally scoped by a path."
  [repo-path old-rev new-rev & [path]]
  (let [args (cond-> ["log" "--format=%h %s" (str old-rev ".." new-rev)]
                     path (conj "--" path))
        out (apply run-git repo-path args)]
    (if (string/blank? out)
      []
      (string/split-lines out))))

(def commits-between (memoize commits-between-impl))

(defn- diff-files-impl
  "Returns a map of file changes between two revisions, optionally scoped by a path."
  [repo-path old-rev new-rev & [path]]
  (let [args (cond-> ["diff" "--name-status" (str old-rev ".." new-rev)]
                     path (conj "--" path))
        out (apply run-git repo-path args)]
    (if (string/blank? out)
      {:added [] :modified [] :deleted [] :renamed [] :copied []}
      (reduce
        (fn [acc line]
          (let [[status file] (string/split line #"\s+" 2)
                status-key (case (first status)
                             \A :added
                             \M :modified
                             \D :deleted
                             \R :renamed
                             \C :copied
                             :modified)]
            (update acc status-key (fnil conj []) file)))
        {:added [] :modified [] :deleted [] :renamed [] :copied []}
        (string/split-lines out)))))

(def diff-files (memoize diff-files-impl))

(defn diff-patch
  "Returns the unified diff patch between two revisions for the given paths."
  [repo-path old-rev new-rev paths]
  (if (empty? paths)
    ""
    (let [args (concat ["diff" (str old-rev ".." new-rev) "--"] paths)
          out (apply run-git repo-path args)]
      out)))

(defn- ls-remote-tags-impl
  "Lists tags from the remote matching a pattern."
  [repo-path pattern]
  (let [out (run-git repo-path "ls-remote" "--tags" "origin" pattern)]
    (if (string/blank? out)
      []
      (->> (string/split-lines out)
           (map #(second (string/split % #"\s+")))
           (remove #(string/ends-with? % "^{}"))
           (map #(string/replace % "refs/tags/" ""))))))

(def ls-remote-tags (memoize ls-remote-tags-impl))

(defn fetch-tag
  "Fetches a specific tag from the remote."
  [repo-path tag]
  (run-git repo-path "fetch" "origin" (str "refs/tags/" tag ":refs/tags/" tag)))

(defn fetch-all
  "Fetches all updates (branches and tags) from the remote."
  [repo-path]
  (run-git repo-path "fetch" "--all" "--tags" "--prune"))

(defn tag
  "Creates a lightweight git tag."
  [repo-path tag-name]
  (run-git repo-path "tag" tag-name))

(defn add
  "Stages specific files."
  [repo-path & paths]
  (apply run-git repo-path "add" paths))

(defn commit
  "Commits staged changes with a message."
  [repo-path message]
  (run-git repo-path "commit" "-m" message))

(defn push
  "Pushes the current branch to the specified remote."
  ([repo-path remote]
   (run-git repo-path "push" remote "HEAD")))

(defn revert-head
  "Reverts the last commit without prompting for an edit message."
  [repo-path]
  (run-git repo-path "revert" "HEAD" "--no-edit"))

(defn revert-expected
  "Reverts HEAD only if it matches the expected commit."
  [repo-path expected-commit]
  (let [current-commit (rev-parse repo-path "HEAD")]
    (when-not (= current-commit expected-commit)
      (throw (ex-info
               "Git HEAD changed unexpectedly; refusing automated rollback."
               {:expected-commit expected-commit
                :current-commit current-commit})))
    (run-git repo-path "revert" expected-commit "--no-edit")))
