(ns cake.tasks.icing
  (:use cake cake.core
        [cake.utils.find-namespaces :only [read-file-ns-decl]]
        [cake.project :only [create]])
  (:require [cake.tasks.deps :as deps])
  (:import [java.io File]
           [java.net URL URLClassLoader]))

(defn- fileset-urls-array
  [fileset & paths]
  (let [urls      (map #(.toURL (.getFile %)) (iterator-seq (.iterator fileset)))
        path-urls (map #(.toURL (File. %)) paths)]
    (into-array URL (concat path-urls urls))))

(defn fileset-classloader
  [fileset & paths]
  (let [urls (apply fileset-urls-array fileset paths)]
    (URLClassLoader/newInstance urls (.getContextClassLoader (Thread/currentThread)))))

(defmacro with-classloader [loader & body]
  `(binding [*use-context-classloader* true]
     (let [orig-cl# (.getContextClassLoader (Thread/currentThread))]
       (try
         (.setContextClassLoader (Thread/currentThread) ~loader)
         ~@body
         (catch Throwable e#
           (throw e#))
         (finally
          (.setContextClassLoader (Thread/currentThread) orig-cl#))))))

(defn basename
  [name]
  (subs name 0 (.lastIndexOf name ".")))

(defn invoke-with-loader
  [loader file ns-name f-name]
  (let [name       (.getName file)
        basescript (basename name)]
    (with-classloader loader
      (clojure.lang.RT/load basescript)
      (let [var (clojure.lang.RT/var ns-name f-name)]
        (.invoke var "")))))

(deftask icing
  "Download the dependencies for a .clj file and run it."
  (let [[script] (:icing *opts*)
        file     (File. (str *pwd* "/" script))
        ns-form  (read-file-ns-decl file)
        project  (create 'icing "0.0.1-SNAPSHOT" (:project (meta (second ns-form))))
        f-name   (str (:entry project))
        ns-name  (str (second ns-form))]
    (deps/init-ivy-settings project)
    (deps/make-ivy project "icing-ivy.xml")
    (deps/ivy-resolve "icing-ivy.xml")
    (let [fs     (deps/cache-fileset "icing.default" "default" "jar")
          loader (fileset-classloader fs *pwd*)]
      (invoke-with-loader loader file ns-name f-name))))
