(defproject test-example "0.1.0-SNAPSHOT"
  :description "cake example project"
  :tasks [foo :exclude [uberjar jar]]
  :dependencies [[clojure "1.2.0"]
                 [clojure-contrib "1.2.0"]
                 [tokyocabinet "1.23-SNAPSHOT"]]
  :dev-dependencies [[clojure-complete "0.1.0"]
                     [autodoc "0.7.1"]]
  :context development)

(defcontext qa
  :foo 1
  :bar 2)

(defcontext development
  :baz 1
  :bar 8)

(deftask bar {[opt1 opt2 opt3] :optional}
  (println opt1 opt2 opt3)
  (bake (:use useful)
        [foo (prompt-read "enter foo:")
         bar (prompt-read "enter bar:")
         pw  (prompt-read "enter password" :echo false)]
        (println "project:" *project*)
        (println "opts:" *opts*)
        (println "foo:" foo)
        (println "bar:" bar)
        (println "password is" (count pw) "characters long")
        (println "baz!")
        (Thread/sleep 2000)
        (println "done sleeping!")
        (verify true "true is false!"))
  (let [n (bake [] {:foo (rand) :bar (rand)})]
    (println (class n) n)))
