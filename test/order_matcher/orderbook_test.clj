(ns order-matcher.orderbook-test
  (:require [clojure.test :refer :all]
            [order-matcher.orderbook :refer :all]
            [order-matcher.protocols :as protocols]))

(deftest limit-then-market
  "Expect a resting limit order to be cleared by market, resulting in empty book"
  (let [b1 (atom (new-fifo-book))
        order1 (order {:order-type :limit
                       :side       :bid
                       :price      103.1
                       :amount     5
                       :ticker     "aapl"})
        order2 (order {:order-type :market
                       :side       :ask
                       :price      104.1
                       :amount     5
                       :ticker     "aapl"})
        _ (do (swap! b1 protocols/fill-order order1)
              (swap! b1 protocols/fill-order order2))]

    (testing
        (is (and (= (:bid @b1) {})
                 (= (:ask @b1) {}))))))


