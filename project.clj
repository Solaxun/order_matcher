(defproject order-matcher "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clojure.java-time "0.3.2"]
                 [org.clojure/data.priority-map "0.0.10"]
                 [criterium "0.4.6"]
                 [com.clojure-goes-fast/clj-async-profiler "0.4.1"]]
  :main ^:skip-aot order-matcher.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
