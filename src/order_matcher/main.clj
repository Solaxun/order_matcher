(ns order-matcher.main
  (:require [order-matcher.protocols :refer :all]
            [order-matcher.orderbook :as orderbook]
            [order-matcher.display :as display]))

(def bookview (display/->FifoBookTableDisplay (sorted-map) (sorted-map)))
(def aapl-book (orderbook/new-fifo-book "aapl"))

(orderbook/fill-order aapl-book (orderbook/order {:order-type :limit
                                                  :side       :bid
                                                  :price      114.1
                                                  :amount     4
                                                  :ticker     "aapl"}))

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


(defmulti display-book (fn [display-type book] display-type))
(defmethod display-book :table [display-type book]
  nil)

(display-book :table (get book :price->quantity) :depth 5)

(->> bid (map str) (apply max-key count) count)

(def boo
  {:bid (into (sorted-map) (zipmap (range 5 15) (repeatedly 15 #(rand-int 20))))
   :ask (into (sorted-map) (zipmap (range 6 16) (repeatedly 15 #(rand-int 20))))})
(def last-exec {:amount 3 :price 15.1})

(defn right-pad [n text]
  (let [pad (- n (count text))]
    (apply str text (repeat pad " "))))

(let [ask (keys (:ask boo))
      bid (reverse (keys (:bid boo)))
      cell-size 10]
  (doseq [a ask]
    (println (right-pad 10 (str "|" a " X " (get-in boo [:ask a]))) "|"))
  (println (right-pad 10 (str "|" (:amount last-exec) " X " (:price last-exec))) "|")
  (doseq [b bid]
    (println (right-pad 10 (str "|" b " X " (get-in boo [:bid b]))) "|")))
