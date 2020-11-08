(ns order-matcher.orderbook
  (:require [java-time :as t]
            [clojure.data.priority-map :refer [priority-map priority-map-keyfn]]
            [order-matcher.protocols :as protocols])
  (:import (java.util UUID)))

(defn satisfies [side]
  (if (= side :buy) >= <=))

(defn other-side [trade]
  (let [side (:side trade)]
    (cond (= side :buy) :sell
          (= side :sell) :buy
          :else (throw (Exception. (str "Side must be :buy or :sell, not " side))))))

;; order, time and id should be assigned immediately before filling
(defn order [{:keys [ticker side order-type amount price] :as trade}]
  {:pre [(every? (partial contains? trade)
                 [:ticker :side :order-type :amount :price])]}
  (let [trade-id (UUID/randomUUID)]
    (hash-map :trade-id trade-id
              :trade (assoc trade :order-time (t/local-date-time)
                                  :trade-id trade-id))))

(defn price-acceptable [price side order-type]
  (if (= order-type :market)
    identity
    (fn [[id offset-trade]]
      ((satisfies side) price (:price offset-trade)))))
;; TODO: status field: pending, partially-executed, fully-executed
;; TOD: make executed trades fill at the best price, e.g. limit buy 109 match limit sell 108
;; should execute on the sell side at 109 too, not just buy side
(defn execute-trade [{:keys [trade-id trade] :as order} matching-trades]
  "Given an order and seq of matching trades, returns a new trade and seq of
  executed trades. The amounts in executed-trades represent actual amounts filled
  and the amount in the returned trade represents the amount remaining, after
  all successful matches have been filled"
  (reduce (fn [{{amt-left :amount} :trade :as res} [executed-id executed-trade]]
            (if (zero? amt-left)
              (reduced res)
              (let [trade-amt (max 0 (- amt-left (:amount executed-trade)))
                    match-amt (max 0 (- (:amount executed-trade) amt-left))]
                (-> res
                    (update :trade assoc :amount trade-amt)
                    (update :executed-trades assoc
                            executed-id (assoc executed-trade
                                          :amount (- (:amount executed-trade) match-amt)
                                          :price (:price trade))) ;TODO: part1, still need exec status
                    (update :executed-trades assoc
                            trade-id
                            (assoc trade
                              :amount (- (:amount trade) trade-amt)))))))
          {:trade trade :executed-trades {}}
          matching-trades))

(defrecord FifoBook [buy sell executed-trades current-trade]
  protocols/Matcher
  (fill-order [this order]
    (if-let [matched (seq (protocols/get-matching-trades this order))]
      (let [{:keys [trade executed-trades]} (execute-trade order matched)]
        (assoc (reduce (fn [book [executed-id executed-trade]]
                         (update book (:side executed-trade)
                                 ;; executed the full amount, remove from book
                                 #(if (or (and (= executed-id (:trade-id trade))
                                               ;; if executed trade is the current trade being processed
                                               ;; check if amount executed covers the full amt of original order
                                               (= (:amount executed-trade) (get-in order [:trade :amount])))
                                          (= (:amount executed-trade)
                                             (get-in book [(:side executed-trade)
                                                           executed-id
                                                           :amount])))
                                    (do (println "removing: " executed-id) (dissoc % executed-id))
                                    (assoc % executed-id
                                             (update executed-trade :amount
                                                     ;; partial fill, adjust amount remaining in book
                                                     ;; don't worry about exec-amt exceeding what's
                                                     ;; in book, that's handled in execute-trades
                                                     (fn [exec-amt]
                                                       ;; if the executed trade is the current trade being
                                                       ;; processed, it doesn't exist in the order book yet
                                                       ;; so we can't get the amount resting in the book
                                                       (if (= executed-id (:trade-id trade))
                                                         (:amount trade)
                                                         (- (get-in book [(:side executed-trade)
                                                                          executed-id
                                                                          :amount])
                                                            exec-amt))))))))
                       this
                       executed-trades)
          :executed-trades executed-trades
          :current-trade trade))
      ;; no matching trades, add to book

      (-> this
          (assoc :executed-trades {})
          (assoc :current-trade {})
          (assoc-in
            [(-> order :trade :side) (:trade-id order)]
            (:trade order)))))
  (get-matching-trades [this order]
    (let [trade (:trade order)]
      (filter (price-acceptable  (:price trade)
                                (:side trade)
                                (:order-type trade))
              (get this (other-side trade)))))
  (update-order [this trade new-order]
    (-> this
        (protocols/cancel-order trade)
        (protocols/fill-order new-order)))
  (cancel-order [this trade]
    (update-in this [(:side trade) (:trade-id trade)] dissoc))
  (bid-ask [this]
    {:bid (-> buy peek last :price) :ask (-> sell peek last :price)}))

(defn best-bid [orderbook]
  (:bid (protocols/bid-ask orderbook)))

(defn best-ask [orderbook]
  (:ask (protocols/bid-ask orderbook)))

(defn new-fifo-book []
  (map->FifoBook
   {:buy  (priority-map-keyfn (fn [{:keys [price order-time]}] [(- price) order-time]))
    :sell (priority-map-keyfn (fn [{:keys [price order-time]}] [price order-time]))
    :executed-trades {}
    :current-trade {}}))

(def book (atom (new-fifo-book)))
(let [t1 (order {:order-type :limit
                 :side :buy
                 :price 112.1
                 :amount 19
                 :ticker "aapl"})
      t2 (order {:order-type :limit
                 :side :sell
                 :price 109.9
                 :amount 5
                 :ticker "aapl"})]
  (do
    (swap! book protocols/fill-order t1)
    #_(swap! book protocols/fill-order t2)))

(protocols/bid-ask @book)
(peek (:buy @book))
(execute-trade (order {:order-type :limit
                       :side       :buy
                       :price      109.2
                       :amount     15
                       :ticker     "aapl"})
               (filter (price-acceptable 109.2 :buy :limit) (:sell @book)))

;;@book
#_(let [b @book
      order (order {:order-type :limit
                    :side :buy
                    :price 105
                    :amount 50
                    :user-id 6
                    :ticker "aapl"})
      matches (protocols/get-matching-trades b order)]
  (clojure.pprint/pprint (execute-trade order matches)))
#_(clojure.pprint/pprint (protocols/fill-order @book (order {:order-type :limit
                                                           :side :sell
                                                           :price 104
                                                           :amount 102
                                                           :user-id 3
                                                           :ticker "aapl"})))
