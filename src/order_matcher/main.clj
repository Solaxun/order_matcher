(ns order-matcher.main
  (:require [order-matcher.protocols :as protocols]
            [order-matcher.orderbook :as orderbook]
            [order-matcher.display :as display]))

(def bookview (display/->FifoBookTableDisplay (sorted-map) (sorted-map)))
(def aapl-book (orderbook/new-fifo-book "aapl"))

(let [book (atom (orderbook/new-fifo-book "aapl"))
      t1 (orderbook/order {:order-type :limit
                 :side       :bid
                 :price      114.1
                 :amount     4
                 :ticker     "aapl"})
      t2 (orderbook/order {:order-type :limit
                 :side       :bid
                 :price      113.9
                 :amount     1
                 :ticker     "aapl"})
      t3 (orderbook/order {:order-type :limit
                 :side       :bid
                 :price      113.8
                 :amount     1
                 :ticker     "aapl"})
      t4 (orderbook/order {:order-type :limit
                 :side       :ask
                 :amount     10
                 :price      10
                 :ticker     "aapl"})]
  (do
    (swap! book protocols/fill-order t1)
    (swap! book protocols/fill-order t2)
    (swap! book protocols/fill-order t3)
    (swap! book protocols/fill-order t4)))
(protocols/display-book
  #order_matcher.orderbook.FifoBookTableDisplay{:bid {}, :ask {10 4}})