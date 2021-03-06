(ns cake.core
  (:use cake cake.utils.useful cake.file
        clojure.contrib.condition
        [cake.reload :only [reloader]]
        [clojure.string :only [join trim]])
  (:require [cake.ant :as ant]
            [cake.server :as server]
            [cake.project :as project])
  (:import [java.io File FileReader InputStreamReader OutputStreamWriter BufferedReader FileNotFoundException]
           [org.apache.tools.ant.taskdefs ExecTask]
           [java.net Socket SocketException]))

(defn newer? [& args]
  (apply > (for [arg args]
             (if (number? arg)
               arg
               (.lastModified (if (string? arg) (file arg) arg))))))

(defn load-tasks [tasks]
  (let [complain? (seq (.listFiles (file "lib")))]
    (doseq [ns tasks]
      (try (require ns)
           (catch Exception e
             (when complain? (server/print-stacktrace e)))))))

(defmacro defproject [name version & opts]
  (let [opts (into-map opts)
        [tasks task-opts] (split-with symbol? (:tasks opts))
        task-opts (into-map task-opts)]
    `(do (alter-var-root #'*project*    (fn [_#] (project/create '~name ~version '~opts)))
         (alter-var-root #'project-root (fn [_#] (project/create '~name ~version '~opts)))
         (require 'cake.tasks.default)
         (load-tasks '~tasks)
         (undeftask ~@(:exclude task-opts)))))

(defmacro defcontext [name & opts]
  (let [opts (into-map opts)]
    `(alter-var-root #'*context* merge-in {'~name '~opts})))

(defn update-task [task deps doc action]
  (let [task (or task {:actions [] :deps #{} :doc []})]
    (-> task
        (update :deps    into deps)
        (update :doc     into doc)
        (update :actions conj action))))

(defonce tasks (atom {}))
(def run? nil)

(def implicit-tasks
  {'repl     ["Start an interactive shell with history and tab completion."]
   'stop     ["Stop cake jvm processes."]
   'start    ["Start cake jvm processes."]
   'restart  ["Restart cake jvm processes."]
   'reload   ["Reload any .clj files that have changed or restart."]
   'upgrade  ["Upgrade cake to the most current version."]
   'ps       ["List running cake jvm processes for all projects."]
   'kill     ["Kill running cake jvm processes. Use -9 to force."]
   'killall  ["Kill all running cake jvm processes for all projects."]
   'eval     ["Eval the given forms in the project JVM." "Read forms from stdin if - is provided."]
   'run      ["Execute a script in the project jvm."]
   'filter   ["Thread each line in stdin through the given forms, printing the results."
              "The line is passed as a string with a trailing newline, and println is called with the result of the final form."]})

(defn parse-task-opts [forms]
  (let [[deps forms] (if (set? (first forms))
                      [(first forms) (rest forms)]
                      [#{} forms])
        deps (map #(if-not (symbol? %) (eval %) %) deps)
        [doc forms] (split-with string? forms)
        [destruct forms] (if (map? (first forms))
                           [(first forms) (rest forms)]
                           [{} forms])
        [pred forms] (if (= :when (first forms))
                       `[~(second forms) ~(drop 2 forms)]
                       [true forms])]
    {:deps deps :doc doc :actions forms :destruct destruct :pred pred}))

(defn- in-ts [ts task-decl]
  (conj (drop 2 task-decl)
        (symbol (str ts "." (second task-decl)))
        (first task-decl)))

(defmacro ts
  "Wrap deftask calls with a task namespace. Takes docstrings for the namespace followed by forms.
   Creates a task named after the namespace that prints a list of tasks in that namespace."
  [ts & forms]
  (let [[doc forms] (split-with string? forms)
        doc (update (vec doc) 0 #(str % " --"))]
    `(do
       (deftask ~ts ~@doc
         (invoke ~'help {:help [~(name ts)]}))
       ~@(map (partial in-ts ts) forms))))

(defmacro deftask
  "Define a cake task. Each part of the body is optional. Task definitions can
   be broken up among multiple deftask calls and even multiple files:
   (deftask foo #{bar baz} ; a set of prerequisites for this task
     \"Documentation for task.\"
     {foo :foo} ; destructuring of *opts*
     (do-something)
     (do-something-else))"
  [name & forms]
  (verify (not (implicit-tasks name)) (str "Cannot redefine implicit task: " name))
  (let [{:keys [deps doc actions destruct pred]} (parse-task-opts forms)]
    `(swap! tasks update '~name update-task '~deps '~doc
            (fn [~destruct] (when ~pred ~@actions)))))

(defn task-run-file [task-name]
  (file ".cake" "run" task-name))

(defn run-file-task? [target-file deps]
  (let [{file-deps true task-deps false} (group-by string? deps)]
    (or (not (.exists target-file))
        (some (partial mtime< target-file)
              (into file-deps
                    (map #(task-run-file %)
                         task-deps)))
        (empty? deps))))

(defmacro defile
  "Define a file task. Uses the same syntax as deftask, however the task name
   is a string representing the name of the file to be generated by the body.
   Source files may be specified in the dependencies set, in which case
   the file task will only be ran if the source is newer than the destination.
   (defile \"main.o\" #{\"main.c\"}
     (sh \"cc\" \"-c\" \"-o\" \"main.o\" \"main.c\"))"
  [name & forms]
  (let [{:keys [deps doc actions destruct pred]} (parse-task-opts forms)]
    `(swap! tasks update '~name update-task '~deps '~doc
            (fn [~destruct]
              (when (and ~pred
                         (run-file-task? *File* '~deps))
                (mkdir (.getParentFile *File*))
                ~@actions)))))

(defmacro undeftask [& names]
  `(swap! tasks dissoc ~@(map #(list 'quote %) names)))

(defmacro remove-dep! [task dep]
  `(swap! tasks update-in ['~task :deps] disj '~dep))

(defn- expand-defile-path [path]
  (file (.replaceAll path "\\+context\\+" (str (:context *project*)))))

(defn run-task
  "Execute the specified task after executing all prerequisite tasks."
  [name]
  (let [task (@tasks name)]
    (if (and (nil? task)
             (not (string? name)))
      (println "unknown task:" name)
      (verify (not= :in-progress (run? name))
              (str "circular dependency found in task: " name)
        (when-not (run? name)
          (set! run? (assoc run? name :in-progress))
          (doseq [dep (:deps task)] (run-task dep))
          (binding [*current-task* name
                    *File* (if-not (symbol? name) (expand-defile-path name))]
            (doseq [action (:actions task)] (action *opts*))
            (set! run? (assoc run? name true))
            (if (symbol? name)
              (touch (task-run-file name) :verbose false))))))))

(defmacro invoke [name & [opts]]
  `(binding [*opts* (or ~opts *opts*)]
     (run-task '~name)))

(defn- bake-connect [port]
  (loop []
    (if-let [socket (try (Socket. "localhost" (int port)) (catch SocketException e))]
      socket
      (recur))))

(defn- quote-if
  "We need to quote the binding keys so they are not evaluated within the bake syntax-quote and the
   binding values so they are not evaluated in the bake* syntax-quote. This function makes that possible."
  [pred bindings]
  (reduce
   (fn [v form]
     (if (pred (count v))
       (conj v (list 'quote form))
       (conj v form)))
   [] bindings))

(defn bake-port []
  (try (Integer/parseInt
        (trim (second (.split (slurp (file ".cake" "bake.pid")) "\n"))))
       (catch FileNotFoundException e
         nil)))

(defn bake* [ns-forms bindings body]
  (let [port (bake-port)]
    (verify port "bake not supported. perhaps you don't have a project.clj")
    (let [body  `(~'let ~(quote-if odd? bindings) ~@body)
          socket (bake-connect port)
          reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
          writer (OutputStreamWriter. (.getOutputStream socket))]
      (doto writer
        (.write (prn-str [*current-task* ns-forms body] *vars*))
        (.flush))
      (loop [line (.readLine reader)]
        (when-not (or (nil? line) (= ":bake.core/result" line))
          (println line)
          (recur (.readLine reader))))
      (let [line   (.readLine reader)
            result (rescue (read-string line) line)]
        (flush)
        (.close socket)
        result))))

(defmacro bake
  "Execute code in a separate jvm with the classpath of your projects. Bindings allow
   passing state to the project jvm. Namespace forms like use and require must be
   specified before bindings."
  {:arglists '([ns-forms* bindings body*])}
  [& forms]
  (let [[ns-forms [bindings & body]] (split-with (complement vector?) forms)]
    `(bake* '~ns-forms ~(quote-if even? bindings) '~body)))

(defn cake-exec [& args]
  (ant/ant ExecTask {:executable "ruby" :dir *root* :failonerror true}
    (ant/args *script* args (str "--project=" *root*))))

(defn bake-restart []
  (project/log "Restarting project jvm")
  (cake-exec "restart" "project"))

(defn git [& args]
  (if (.exists (file ".git"))
    (ant/ant ExecTask {:executable "git" :dir *root* :failonerror true}
      (ant/args args))
    (println "warning:" *root* "is not a git repository")))

(def *readline-marker* nil)

(defn process-command [[task readline-marker]]
  (binding [*readline-marker* readline-marker, run? {}]
    (ant/in-project
     (doseq [dir ["lib" "classes" "build"]]
       (.mkdirs (file dir)))
     (handler-case :type
       (run-task (symbol (name task)))
       (handle :abort-task
         (println (name task) "aborted:" (:message *condition*)))))))

(defn abort-task [& message]
  (raise {:type :abort-task :message (join " " message)}))

(defn repl []
  (binding [*current-task* "repl"]
    (ant/in-project (server/repl))))

(defn prompt-read [prompt & opts]
  (let [opts (apply hash-map opts)
        echo (if (false? (:echo opts)) "@" "")]
    (println (str echo *readline-marker* prompt))
    (read-line)))

(defn start-server [port]
  (let [project-files (project/files ["project.clj" "context.clj" "tasks.clj" "dev.clj"] ["tasks.clj" "dev.clj"])]
    (in-ns 'cake.core)
    (project/load-files project-files)
    (when-not *project* (require '[cake.tasks help new]))
    (when (= "global" (:artifact-id *project*))
      (undeftask test autotest jar uberjar war uberwar install release)
      (require '[cake.tasks new]))
    (server/init-multi-out ".cake/cake.log")
    (server/create port process-command
      :reload (reloader project/classpath project-files (File. "lib/dev"))
      :repl   repl)
    nil))
