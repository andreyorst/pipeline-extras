(ns build
  (:require
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as d]))

(def lib 'io.github.andreyorst/pipeline-extras)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s.jar" (name lib)))

(defn clean [& _]
  (b/delete {:path "target"}))

(defn- pom [opts]
  (b/write-pom
   (merge {:lib lib
           :version version
           :basis basis
           :src-dirs ["src/main/clojurec"]
           :scm {:url "https://github.com/andreyorst/pipeline-extras"}}
          opts)))

(defn jar [& _]
  (clean)
  (pom {:class-dir class-dir})
  (b/copy-dir {:src-dirs ["src/main/clojurec"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [& _]
  (jar)
  (b/install
   {:basis basis
    :lib lib
    :version version
    :jar-file jar-file
    :class-dir class-dir}))

(defn deploy [& _]
  (jar)
  (pom {:target "./"})
  (d/deploy {:artifact jar-file
             :installer :remote
             :pom-file "pom.xml"
             :sign-releases? true}))
