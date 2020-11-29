(ns order-matcher.orderbook-test
  (:require [clojure.test :refer :all]
            [order-matcher.orderbook :as orderbook]
            [order-matcher.protocols :as protocols]))

(deftest limit-then-market-clear-book
  "Expect a resting limit order that is matched with either an incoming market or limit order of the same quantity
  to clear the book, resuting in no bids or asks (empty book)"
  (let [b1 (orderbook/new-fifo-book "aapl")
        order1 (orderbook/order {:order-type :limit
                                 :side       :bid
                                 :price      103.1
                                 :amount     5
                                 :ticker     "aapl"})
        order2 (orderbook/order {:order-type :market
                                 :side       :ask
                                 :amount     5
                                 :ticker     "aapl"})
        order3 (orderbook/order {:order-type :limit
                                 :side       :ask
                                 :price      103.1
                                 :amount     5
                                 :ticker     "aapl"})
        newbook (-> b1
                    (protocols/fill-order order1)
                    (protocols/fill-order order2))
        newbook2 (-> b1
                    (protocols/fill-order order1)
                    (protocols/fill-order order3))]
    (testing
      (is (and (= (:bid newbook) {})
               (= (:ask newbook) {})))
      (is (and (= (:bid newbook2) {})
               (= (:ask newbook2) {})))
      (is (= (vals (:price->quantity newbook))
             [{} {}]))
      (is (= (vals (:price->quantity newbook2))
             [{} {}])))))

(deftest limit-then-market-resting-book
  "Expect a resting limit order that is matched with either an incoming market or limit order of the same quantity
  to clear the book, resuting in no bids or asks (empty book)"
  (let [b1 (orderbook/new-fifo-book "aapl")
        order1 (orderbook/order {:order-type :limit
                                 :side       :bid
                                 :price      103.1
                                 :amount     5
                                 :ticker     "aapl"})
        order2 (orderbook/order {:order-type :market
                                 :side       :ask
                                 :amount     12
                                 :ticker     "aapl"})
        order3 (orderbook/order {:order-type :limit
                                 :side       :ask
                                 :price      103.1
                                 :amount     12
                                 :ticker     "aapl"})
        newbook (-> b1
                    (protocols/fill-order order1)
                    (protocols/fill-order order2))
        newbook2 (-> b1
                     (protocols/fill-order order1)
                     (protocols/fill-order order3))]
    (testing
      (is (and (= (:bid newbook) {})
               (= ((comp #(get % (:trade-id order2)) :ask) newbook)
                  (-> order2 :trade (dissoc :price) (assoc :amount 7 :order-type :limit :price 103.1)))))
      (is (and (= (:bid newbook2) {})
               (= ((comp #(get % (:trade-id order3)) :ask) newbook2)
                  (-> order3 :trade (assoc :amount 7)))))
      (is (= (seq (:price->quantity newbook))
             (seq {:bid {} :ask {103.1 7}})))
      (is (= (seq (:price->quantity newbook2))
             (seq {:bid {} :ask {103.1 7}}))))))

(defn test-limit-then-market []
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
      (swap! book protocols/fill-order t4)
      (swap! book protocols/cancel-order (:trade-id t4)))))

(defn gen-order []
  (orderbook/order {:ticker     "LTRPA"
                    :side       (rand-nth [:bid :ask])
                    :order-type (rand-nth [:limit :market])
                    :amount     (rand-nth (range 10 50))
                    :price      (* 10 (rand))}))
