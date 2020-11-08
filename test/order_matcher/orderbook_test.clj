(ns order-matcher.orderbook-test
  (:require [clojure.test :refer :all]
            [order-matcher.orderbook :refer :all]
            [order-matcher.protocols :as protocols]))

(deftest limit-then-market
  "Expect a resting limit order to be cleared by market, resulting in empty book"
  (let [b1 (new-fifo-book)
        order1 (order {:order-type :limit
                       :side       :buy
                       :price      103.1
                       :amount     5
                       :ticker     "aapl"})
        order2 (order {:order-type :market
                       :side :sell
                       :price 104.1
                       :amount 5
                       :ticker "aapl"})
        _ (do (swap! book protocols/fill-order order1)
              (swap! book protocols/fill-order order2))]

    (testing
        (is (and (= (:buy b1) {})                           ; book cleared
                 (= (:sell b1) {}))))))                     ; all trades empty
(limit-then-market)

(let [o1 (order {:order-type :limit
                 :side       :sell
                 :price      109.8
                 :amount     5
                 :ticker     "aapl"})
      o2 (order {:order-type :limit
                 :side       :sell
                 :price      109.9
                 :amount     5
                 :ticker     "aapl"})
      o3  (order {:order-type :limit
                  :side       :buy
                  :price      110
                  :amount     12
                  :ticker     "aapl"})]
  (execute-trade o3 (filter (price-acceptable 110 :buy :limit)
                            (map vals [o1 o2]))))