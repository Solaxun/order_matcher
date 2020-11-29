(ns order-matcher.display
  (:require [order-matcher.protocols :as protocols]))

(defrecord FifoBookTableDisplay [bid ask]
  protocols/Display
  (display-book [this]
    (println "TBD")))