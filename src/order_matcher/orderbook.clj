(ns order-matcher.orderbook
  (:require [java-time :as t]
            [clojure.data.priority-map :refer [priority-map priority-map-keyfn priority->set-of-items]]
            [order-matcher.protocols :as protocols])
  (:import (java.util UUID)))

(defn satisfies [side]
  (if (= side :bid) >= <=))

(defn other-side [trade]
  (let [side (:side trade)]
    (cond (= side :bid) :ask
          (= side :ask) :bid
          :else (throw (Exception. (str "Side must be :bid or :ask, not " side))))))

(defn dissoc-in [m [k & ks]]
  (if ks
    (assoc m k (dissoc-in (get m k) ks))
    (dissoc m k)))

(defn update-or-dissoc-in [m ks removef updatef & updatef-args]
  ;TODO: make more efficient if needed by doing it all in one pass
  (let [newval (apply updatef (get-in m ks) updatef-args)]
    (if (removef newval)
      (dissoc-in m ks)
      (assoc-in m ks newval))))

;; order, time and id should be assigned immediately before filling
(defn order [{:keys [order-type] :as trade}]
  {:pre [(every? (partial contains? trade)
                 (if (= order-type :limit)
                   [:ticker :side :order-type :amount :price]
                   [:ticker :side :order-type :amount]))]}
  (let [trade-id (UUID/randomUUID)]
    (hash-map :trade-id trade-id
              :trade (assoc trade :order-time (t/local-date-time)
                                  :trade-id trade-id))))

(defn price-acceptable [price side order-type]
  (if (= order-type :market)
    identity
    (fn [[id offset-trade]]
      ((satisfies side) price (:price offset-trade)))))

(defn execute-trade [{:keys [trade-id trade] :as order} matching-trades]
  "Given an order and seq of matching trades, returns a new trade and seq of
  executed trades. The amounts in executed-trades represent actual amounts filled
  and the amount in the returned trade represents the amount remaining, after
  all successful matches have been filled"
  (reduce (fn [{{amt-left :amount fills :fills} :trade :as res} [executed-id executed-trade]]
            (if (zero? amt-left)
              (reduced res)
              (let [trade-amt (max 0 (- amt-left (:amount executed-trade)))
                    match-amt (max 0 (- (:amount executed-trade) amt-left))]
                (-> res
                    (update :trade assoc :amount trade-amt
                            :fills (conj fills {:amount     (- (:amount executed-trade) match-amt)
                                                          :price (:price executed-trade)}))
                    (update :executed-trades assoc
                            executed-id (assoc executed-trade
                                          :amount (- (:amount executed-trade) match-amt)
                                          ;; trades should transact at the limit price in the book
                                          ;; but if the resting trade is a market order, then transact
                                          ;; at the crossing trade's price
                                          :price (:price executed-trade)))
                    (update :executed-trades assoc
                            trade-id
                            (assoc trade
                              :amount (- (:amount trade) trade-amt)
                              :price (:price executed-trade)))))))
          {:trade (assoc trade :fills []) :executed-trades {}}
          matching-trades))

(defn add-to-book
  [book {:keys [trade-id side price amount order-type] :as trade}]
  (if (= order-type :limit)
    (-> book
        (assoc :executed-trades {} :trade-status trade)
        (assoc-in [side trade-id] trade)
        (update-in [:price->quantity side price]
                   (fnil + 0)
                   amount))
    (-> book
        (assoc :executed-trades {}
               :trade-status {:status           :rejected
                              :reason           "insufficient liquidity"
                              :trade-id         trade-id
                              :amount-remaining (:amount trade)
                              :fills            []}))))

(defn update-price->quantity
  [price->quantity {:keys [trade-id side amount price fill-type original-trade-id new-amount]}]
  (cond (= trade-id original-trade-id)
        (if (= fill-type :partial-fill)
          (assoc-in price->quantity [side price] new-amount)
          price->quantity)

        (some? (get-in price->quantity [side price]))
        (update-or-dissoc-in price->quantity [side price] zero? - amount)
        #_(update-in price->quantity [side price] - amount)

        :else
        (do (println "****")
            (assoc-in price->quantity [side price] new-amount))))

(defrecord FifoBook [bid ask executed-trades trade-status price->quantity]
  protocols/Matcher
  (fill-order [this order]
    (let [{trade-id :trade-id original-trade :trade} order
          {:keys [amount price side]} original-trade]
      (if-let [matched (seq (protocols/get-matching-trades this order))]
        (let [{:keys [trade executed-trades]} (execute-trade order matched)
              new-book (reduce (fn [book [executed-id executed-trade]]
                                 (let [{executed-amount :amount executed-side :side executed-price :price} executed-trade
                                       resting-amount (if (= trade-id executed-id)
                                                        amount
                                                        (get-in book [executed-side executed-id :amount]))
                                       new-amount (- resting-amount executed-amount)]
                                   (if (zero? new-amount)
                                     ;; whole trade filled, remove from book if resting limit, don't add to book if incoming order
                                     ;; dissoc-ing the trade-id is ok even though it's not part of the book, as dissocing a key
                                     ;; that doesn't exist just gives the map back
                                     (-> book
                                         (dissoc-in [executed-side executed-id])
                                         (update :price->quantity update-price->quantity
                                                 (assoc executed-trade :fill-type :complete-fill
                                                                       :original-trade-id trade-id
                                                                       :new-amount new-amount)))
                                     ;; if it's the current trade, that's not in the book so need to add the remaining amt
                                     ;; if it's any other resting trade, it's already in book so deduct executed amt
                                     (-> book
                                         ;;TODO: partial fill of market order... cancel rest, limit rest, cancel whole thing    ?
                                         (assoc-in [executed-side executed-id] (assoc executed-trade :amount new-amount))
                                         (update :price->quantity update-price->quantity
                                                 (assoc executed-trade :fill-type :partial-fill
                                                                       :original-trade-id trade-id
                                                                       :new-amount new-amount))))))
                               this
                               executed-trades)]
          (assoc new-book :executed-trades executed-trades
                          :trade-status {:trade-id         trade-id
                                         :status           (if (zero? (:amount trade)) :fully-executed :partial-fill)
                                         :amount-remaining (:amount trade)
                                         :fills             (:fills trade)}))
        ;; no matching trades, add to book
        (add-to-book this original-trade))))
  (get-matching-trades [this order]
    (let [trade (:trade order)]
      (filter (price-acceptable (:price trade)
                                (:side trade)
                                (:order-type trade))
              (get this (other-side trade)))))
  (update-order [this trade-id new-order]
    ;; TODO: update and cancel won't have access to the trade once it's in the book, so have
    ;; to use trade-id after all instead.  Also need to cancel out amounts in price->quantity
    (let [trade (get bid trade-id (get ask trade-id))]      ; uh-oh, don't know side from only trade-id :(
      (-> this
          (protocols/cancel-order trade)
          (protocols/fill-order new-order)
          (update-in price->quantity [(:side trade) (:price trade)] - (:amount trade)))))
  (cancel-order [this trade-id]
    (let [trade (get bid trade-id (get ask trade-id))]
      (update-in this [(:side trade) (:trade-id trade)] dissoc)))
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
                   :amount     8
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
(protocols/bid-ask @book)
(peek (:bid @book))
(peek (:ask @book))

(defn gen-order []
  (order {:ticker     "LTRPA"
          :side       (rand-nth [:bid :ask])
          :order-type (rand-nth [:limit :market])
          :amount     (rand-nth (range 10 50))
          :price      (* 10 (rand))}))
(dotimes [i 10]
  (swap! book protocols/fill-order (gen-order)))
@book

;; questions
;; market order when nothing on other side
;; reject

;; market order when there are other limits, but not enough for full execution
;; reject or partial fill?  If partial, convert remaining to limit at last execution px?

;; two market orders and no limits?
;; not possible bc market rejected if no match in other side for limit trades (see rule 1)

