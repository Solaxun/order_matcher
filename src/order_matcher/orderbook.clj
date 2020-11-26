(ns order-matcher.orderbook
  (:require [java-time :as t]
            [clojure.data.priority-map :refer [priority-map priority-map-keyfn priority->set-of-items]]
            [order-matcher.protocols :as protocols])
  (:import (java.util UUID)))

(defn satisfies [side]
  "Determines the comparison function for when a trade will be matched.  For example, incoming
  bids will match resting trades when the incoming price is >= that of the resting trade price."
  (if (= side :bid) >= <=))

(defn other-side [trade]
  "Returns the offsetting side for a given trade."
  (let [side (:side trade)]
    (cond (= side :bid) :ask
          (= side :ask) :bid
          :else (throw (Exception. (str "Side must be :bid or :ask, not " side))))))

(defn dissoc-in [m [k & ks]]
  "Like assoc-in but for dissocing nested keypaths. Can also be done using update-in with dissoc."
  (if ks
    (assoc m k (dissoc-in (get m k) ks))
    (dissoc m k)))

(defn update-or-dissoc-in [m ks removef updatef & updatef-args]
  "Like 'update-in' but takes a unary predicate 'removef' which is called on the return
  value of the update function and dissoc's the key corresponding to the value if the result
  of 'removef' is true."
  ;TODO: make more efficient if needed by doing it all in one pass
  (let [newval (apply updatef (get-in m ks) updatef-args)]
    (if (removef newval)
      (dissoc-in m ks)
      (assoc-in m ks newval))))

(defn order [{:keys [order-type] :as trade}]
  "Constructor for orders, assigns a UUID to each incoming trade which will be used
  to identify the trade, as well as for performing trade modifications and cancellations."
  {:pre [(every? (partial contains? trade)
                 (if (= order-type :limit)
                   [:ticker :side :order-type :amount :price]
                   [:ticker :side :order-type :amount]))]}
  (let [trade-id (UUID/randomUUID)]
    (hash-map :trade-id trade-id
              :trade (assoc trade :order-time (t/local-date-time)
                                  :trade-id trade-id))))

(defn price-acceptable [price side order-type]
  "Returns a predicate which, for a given trade type, will return true if the price
  of the trade the predicate is called on satisfies the current trade price. Market
  trades are always matched."
  (if (= order-type :market)
    identity
    (fn [[id offset-trade]]
      ((satisfies side) price (:price offset-trade)))))

(defn execute-trade [{:keys [trade-id trade] :as order} matching-trades]
  "Given an order and seq of matching trades, returns the trade in such order updated for
  matched trades, and returns executed trades which crossed such order. The amounts in executed-trades
  represent actual amounts filled and the amount in the returned trade represents the amount remaining,
  after all successful matches have been filled"
  (reduce (fn [{{amt-left :amount fills :fills} :trade :as res} [executed-id executed-trade]]
            (if (zero? amt-left)
              (reduced res)
              (let [trade-amt (max 0 (- amt-left (:amount executed-trade)))
                    match-amt (max 0 (- (:amount executed-trade) amt-left))]
                (-> res
                    (update :trade assoc :amount trade-amt
                            :fills (conj fills {:amount (- (:amount executed-trade) match-amt)
                                              :price (:price executed-trade)}))
                    (update :executed-trades assoc
                            executed-id (assoc executed-trade
                                          :amount (- (:amount executed-trade) match-amt)
                                          :price (:price executed-trade)))))))
          {:trade (assoc trade :fills []) :executed-trades {}}
          matching-trades))

(defn add-to-book
  "When an order is placed and there are no matching trades in the other side of the book,
  either add such order to the book if it's a limit order or reject if it's a market order."
  [book {:keys [trade-id side price amount order-type] :as trade}]
  (if (= order-type :limit)
    (-> book
        (assoc :executed-trades {} :trade-status {:status           :pending
                                                  :trade-id         trade-id
                                                  :amount-remaining amount
                                                  :fills             []})
        (assoc-in [side trade-id] trade)
        (update-in [:price->quantity side price]
                   (fnil + 0)
                   amount))
    (-> book
        (assoc :executed-trades {}
               :trade-status {:status           :rejected
                              :reason           "insufficient liquidity"
                              :trade-id         trade-id
                              :amount-remaining amount
                              :fills             []}))))

(defrecord FifoBook [bid ask executed-trades trade-status price->quantity]
  protocols/OrderBook
  (fill-order [this order]
    (let [{trade-id :trade-id original-trade :trade} order]
      (if-let [matched (seq (protocols/get-matching-trades this order))]
        (let [{:keys [trade executed-trades]} (execute-trade order matched)
              {:keys [amount side price]} trade
              fully-executed? (zero? amount)
              new-book (reduce-kv (fn [book executed-id executed-trade]
                                 (let [{executed-amount :amount executed-side :side executed-price :price} executed-trade
                                       resting-amount (get-in book [executed-side executed-id :amount])
                                       new-amount (- resting-amount executed-amount)]
                                   (if (zero? new-amount)
                                     ;; executed-trade completely filled, remove from book
                                     (-> book
                                         (dissoc-in [executed-side executed-id])
                                         (update-or-dissoc-in [:price->quantity executed-side executed-price] zero? - executed-amount))
                                     ;; executed-trade partially filled, adjust amounts in book
                                     (-> book
                                         (assoc-in [executed-side executed-id] (assoc executed-trade :amount new-amount))
                                         (update-or-dissoc-in [:price->quantity executed-side executed-price] zero? - executed-amount)))))
                               this
                               executed-trades)]
          (-> new-book
              (assoc :executed-trades executed-trades
                     :trade-status {:trade-id         trade-id
                                    :status           (if fully-executed? :fully-executed :partial-fill)
                                    :fills             (:fills trade)})
              (update :trade-status (fn [nb] (if fully-executed? nb (assoc nb :amount-remaining amount))))
              (update side (fn [side] (if fully-executed? side (assoc side trade-id (dissoc trade :fills)))))
              (update :price->quantity (fn [pq] (if (or fully-executed?
                                                        (= (:order-type trade) :market))
                                                  pq
                                                  (update-in pq [side price] (fnil + 0) amount))))))
        ;; no matching trades, add to book if it's a limit order, market orders will be rejected due to insufficient liquidity
        (add-to-book this original-trade))))
  (get-matching-trades
    [this order]
    (let [trade (:trade order)]
      (filter (price-acceptable (:price trade)
                                (:side trade)
                                (:order-type trade))
              (get this (other-side trade)))))
  (update-order [this trade-id new-order]
    (let [trade-id (if (string? trade-id) (UUID/fromString trade-id) trade-id)
          trade (get bid trade-id (get ask trade-id))]
      (-> this
          (protocols/cancel-order trade)
          (protocols/fill-order new-order))))
  (cancel-order [this trade-id]
    (let [trade-id (if (string? trade-id) (UUID/fromString trade-id) trade-id)
          trade (get bid trade-id (get ask trade-id))]
      (-> this
          (update-in [(:side trade) (:trade-id trade)] dissoc)
          (update-in price->quantity [(:side trade) (:price trade)] - (:amount trade)))))
  (bid-ask [this]
    (let [bid-price (-> bid peek last :price)
          ask-price (-> ask peek last :price)]
      {:bid {:price bid-price :amount (get-in price->quantity [:bid bid-price])}
       :ask {:price ask-price :amount (get-in price->quantity [:ask ask-price])}})))

(defn best-bid [orderbook]
  (:bid (protocols/bid-ask orderbook)))

(defn best-ask [orderbook]
  (:ask (protocols/bid-ask orderbook)))

(defn new-fifo-book []
  (map->FifoBook
    {:bid             (priority-map-keyfn (fn [{:keys [price order-time]}] [(- price) order-time]))
     :ask             (priority-map-keyfn (fn [{:keys [price order-time]}] [price order-time]))
     :executed-trades {}
     :trade-status    {}
     :price->quantity {:bid (sorted-map) :ask (sorted-map)}}))

(defn test-limit-then-market []
  (let [book (atom (new-fifo-book))
        t1 (order {:order-type :limit
                   :side       :bid
                   :price      114.1
                   :amount     4
                   :ticker     "aapl"})
        t2 (order {:order-type :limit
                   :side       :bid
                   :price      113.9
                   :amount     1
                   :ticker     "aapl"})
        t3 (order {:order-type :limit
                   :side       :bid
                   :price      113.8
                   :amount     1
                   :ticker     "aapl"})
        t4 (order {:order-type :market
                   :side       :ask
                   :amount     10
                   ;:price      112.1
                   :ticker     "aapl"})]
    (do
      (swap! book protocols/fill-order t1)
      (swap! book protocols/fill-order t2)
      (swap! book protocols/fill-order t3)
      (swap! book protocols/fill-order t4))))
(test-limit-then-market)
;;TODO: if first trade is market order, and therefore doens't require a price, need to figure out how to
;;have it rest and take first trade since there are no prices to put in limit book.  Also NPE bc price is nil

;;TODO: for :trade-status, change the structure a bit to represent a "trade result" or something similar
;;showing e.g. avg fill price (or each fill?), filled qty, remaining, etc.

(defn gen-order []
  (order {:ticker     "LTRPA"
          :side       (rand-nth [:bid :ask])
          :order-type (rand-nth [:limit :market])
          :amount     (rand-nth (range 10 50))
          :price      (* 10 (rand))}))
(dotimes [i 10]
  (swap! book protocols/fill-order (gen-order)))

;; questions
;; market order when nothing on other side
;; reject

;; market order when there are other limits, but not enough for full execution
;; reject or partial fill?  If partial, convert remaining to limit at last execution px?

;; two market orders and no limits?
;; not possible bc market rejected if no match in other side for limit trades (see rule 1)

