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

(defn pom [& _]
  (b/write-pom {:target "./"
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src/main/clojurec"]}))

(defn jar [& _]
  (clean)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src/main/clojurec"]})
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
  (pom)
  (d/deploy {:artifact jar-file
             :installer :remote
             :pom-file "pom.xml"
             :sign-releases? true}))
