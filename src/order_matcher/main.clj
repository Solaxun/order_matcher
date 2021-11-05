(ns order-matcher.main
  (:require [order-matcher.protocols :refer :all]
            [order-matcher.orderbook :as orderbook]
            [order-matcher.display :as display]
            [clojure.data.priority-map :refer [priority-map-keyfn priority-map]]))

(def bookview (display/->FifoBookTableDisplay (sorted-map) (sorted-map)))
(def aapl-book (orderbook/new-fifo-book "aapl"))

(fill-order aapl-book (orderbook/order {:order-type :limit
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
    (swap! book fill-order t1)
    (swap! book fill-order t2)
    (swap! book fill-order t3)
    (swap! book fill-order t4)))

(defn rand-limit-order []
  (orderbook/order {:order-type :limit
                    :side       (rand-nth [ :bid :ask])
                    :amount     (int (* 50 (rand)))
                    :price      (Math/round (* 100 (rand)))
                    :ticker     "aapl"}))

(def sample-orders (for [i (range 1000000)] (rand-limit-order)))
(def sample-orders-2 (for [i (range 200000)] (rand-limit-order)))

;; 10 seconds (just adding) - should be fast as the other-side will always be nil
;; so filtering won't start since the filtered collection is nul.  There should
;; therefore never be a linear scan of the potential matching trades.  Yet this
;; is still 10x slower than the simple conj example to priority map below - why?
(time (let [b (atom (orderbook/new-fifo-book "aapl"))]
        (doseq [o (take (* 1000 200) sample-orders)] (swap! b fill-order o))
        #_(clojure.pprint/pprint @b)
        #_(clojure.pprint/pprint (:ask @b))
        ))

;; 30 seconds for 20k vs 200k (all order types, both buy and sell)
(time (let [b (atom (orderbook/new-fifo-book "aapl"))]
        (doseq [o (take (* 1000 100) sample-orders-2)] (swap! b fill-order o))
        #_(clojure.pprint/pprint @b)
        #_(clojure.pprint/pprint (:ask @b))
        #_(clojure.pprint/pprint (take 100 (:ask @b)))))

;; 0.25 seconds
(let [b {}]
  (time (count (reduce conj
                       b
                       (map vec (partition 2 (range (* 1000 200))))))))
;; 0.8 seconds
(let [b (priority-map)]
  (time (count (reduce conj
                       b
                       (map vec (partition 2 (range (* 1000 200))))))))

(let [b (transient {})
      c (map vec (partition 2 (range 1000000)))]
  (time (count (persistent! (reduce conj! b c)))))


(let [b (orderbook/new-fifo-book "aapl")
      o1 (first sample-orders)
      o2 (nth sample-orders 2)]
  (-> (fill-order b o1)
      (fill-order o2)
      (clojure.pprint/pprint)))

(doseq [i (take 3 sample-orders)]
  (clojure.pprint/pprint i)
  (println))

(first sample-orders)
(nth sample-orders 2)

(let [b (orderbook/new-fifo-book "aapl")
      o1 (orderbook/order {:order-type :limit
                           :side       :bid
                           :amount     100
                           :price      25.20
                           :ticker     "aapl"})
      o2 (orderbook/order {:order-type :limit
                           :side       :bid
                           :amount     25
                           :price      25.30
                           :ticker     "aapl"})
      o3 (orderbook/order {:order-type :limit
                           :side       :ask
                           :amount     75
                           :price      25.20
                           :ticker     "aapl"})]
  (-> b
      (fill-order o1)
      (fill-order o2)
      (fill-order o3)
      (clojure.pprint/pprint)))

(display-book
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
