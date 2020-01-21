(ns order-matcher.orderbook-test
  (:require [clojure.test :refer :all]
            [order-matcher.orderbook :refer :all]))

(deftest alloc-trades
  (let [[updated-trades remain-amt]

        (allocate-orders
         [{} 150]
         [999 (:trade (build-trade {:order-type :sell :price 120 :amount 140 :user-id 6}))])]
    (testing
        (is (= 10 remain-amt)))))
