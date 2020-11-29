(ns order-matcher.orderbook
  (:require [java-time :as t]
            [clojure.data.priority-map :refer [priority-map priority-map-keyfn priority->set-of-items]]
            [order-matcher.protocols :as protocols]
            [order-matcher.display :as display])
  (:import (java.util UUID)))

(defn satisfies
  "Determines the comparison function for when a trade will be matched.  For example, incoming
  bids will match resting trades when the incoming price is >= that of the resting trade price."
  [side]
  (if (= side :bid) >= <=))

(defn other-side
  "Returns the offsetting side for a given trade."
  [trade]
  (let [side (:side trade)]
    (cond (= side :bid) :ask
          (= side :ask) :bid
          :else (throw (Exception. (str "Side must be :bid or :ask, not " side))))))

(defn dissoc-in
  "Like assoc-in but for dissocing nested keypaths. Can also be done using update-in with dissoc."
  [m [k & ks]]
  (if ks
    (assoc m k (dissoc-in (get m k) ks))
    (dissoc m k)))

(defn update-or-dissoc-in
  "Like 'update-in' but takes a unary predicate 'removef' which is called on the return
  value of the update function and dissoc's the key corresponding to the value if the result
  of 'removef' is true."
  [m ks removef updatef & updatef-args]
  ;;TODO: make more efficient if needed by doing it all in one pass
  (let [newval (apply updatef (get-in m ks) updatef-args)]
    (if (removef newval)
      (dissoc-in m ks)
      (assoc-in m ks newval))))

(defn order
  "Constructor for orders, assigns a UUID to each incoming trade which will be used
  to identify the trade, as well as for performing trade modifications and cancellations."
  [{:keys [order-type price] :as trade}]
  {:pre [(and (every? (partial contains? trade)
                      (if (= order-type :limit)
                        [:ticker :side :order-type :amount :price]
                        [:ticker :side :order-type :amount]))
              (or (nil? price) (number? price)))]}
  (when (and (= order-type :market)
             (some? price))
    (throw (Exception. "Market trades do not allow a price.")))
  (let [trade-id (UUID/randomUUID)]
    (hash-map :trade-id trade-id
              :trade (assoc trade :order-time (t/local-date-time)
                                  :trade-id trade-id))))

(defn price-acceptable
  "Returns a predicate which, for a given trade type, will return true if the price
  of the trade the predicate is called on satisfies the current trade price. Market
  trades are always matched."
  [price side order-type]
  (if (= order-type :market)
    identity
    (fn [[id offset-trade]]
      ((satisfies side) price (:price offset-trade)))))

(defn execute-trade
  "Given an order and seq of matching trades, returns the trade in such order updated for
  matched trades, and returns executed trades which matched the order. The amounts in executed-trades
  represent actual amounts filled and the amount in the returned trade represents the amount remaining,
  after all successful matches have been filled"
  [{:keys [trade-id trade] :as order} matching-trades]
  (reduce (fn [{{amt-left :amount fills :fills} :trade :as res} [executed-id executed-trade]]
            (if (zero? amt-left)
              (reduced res)
              (let [trade-amt (max 0 (- amt-left (:amount executed-trade)))
                    match-amt (max 0 (- (:amount executed-trade) amt-left))]
                (-> res
                    (update :trade assoc :amount trade-amt
                            :fills (conj fills
                                         {:amount (- (:amount executed-trade) match-amt)
                                          :price  (:price executed-trade)}))
                    (update :executed-trades assoc
                            executed-id (assoc executed-trade
                                          :amount (- (:amount executed-trade) match-amt)
                                          :price (:price executed-trade)))))))
          {:trade (assoc trade :fills []) :executed-trades {}}
          matching-trades))

(defn add-to-book
  "When an order is placed and there are no matching trades in the other side of the book,
  either add such order to the book if it's a limit order or reject if it's a market order.
  Market orders require the other side of the book to have trades present or they will be
  declined due to insufficient liquidity.

  Note, however, that market trades can experience
  partial fills in the event the other side of the book has trades, but not enough to fill
  the entire amount of the market order.  When this happens the remaining amount of the
  market order will be placed on the book as a limit trade at the last executed price."
  [book {:keys [trade-id side price amount order-type] :as trade}]
  (let [new-book (assoc book :executed-trades {}
                             :trade-status {:trade-id         trade-id
                                            :amount-remaining amount
                                            :fills            []})]
    (if (= order-type :limit)
      (-> new-book
          (assoc-in [:trade-status :status] :pending)
          (assoc-in [side trade-id] trade)
          (update-in [:price->quantity side price]
                     (fnil + 0)
                     amount))
      (-> new-book
          (assoc-in  [:trade-status :status] :rejected)
          (assoc-in  [:trade-status :reason] "insufficient liquidity")))))

(defrecord FifoBook
  [ticker bid ask last-execution executed-trades trade-status price->quantity]
  protocols/OrderBook
  (fill-order [this order]
    (when-not (= ticker (-> order :trade :ticker))
      (throw (Exception. (str "Each FifoBook must be used with only one ticker. "
                              "The ticker for this book is " "[" ticker "]" ", the current order's ticker is "
                              "[" (-> order :trade :ticker) "]"))))
    (let [{trade-id :trade-id original-trade :trade} order]
      (if-let [matched (seq (protocols/get-matching-trades this order))]
        (let [{:keys [trade executed-trades]} (execute-trade order matched)
              {:keys [amount side price]} trade
              fully-executed? (zero? amount)
              new-book (reduce-kv (fn [book executed-id executed-trade]
                                    (let [{executed-amount :amount executed-side :side executed-price :price} executed-trade
                                          resting-amount (get-in book [executed-side executed-id :amount])
                                          new-amount (- resting-amount executed-amount)
                                          book-step (update-or-dissoc-in book [:price->quantity executed-side executed-price] zero? - executed-amount)]
                                      (if (zero? new-amount)
                                        ;; executed-trade completely filled, remove from book
                                        (dissoc-in book-step [executed-side executed-id])
                                        ;; executed-trade partially filled, adjust amounts in book
                                        (update-in book-step [executed-side executed-id executed-trade] assoc :amount new-amount))))
                                  this
                                  executed-trades)]
          ;;TODO: extract fill logic into a multimethod or similar for partial fills vs total, market vs limt, etc.
          ;; depends on fill type (right now only partial-fill or fully-executed) and order type (likely extendable in future)
          (-> new-book
              (assoc :executed-trades executed-trades
                     :trade-status {:trade-id trade-id
                                    :status   (if fully-executed? :fully-executed :partial-fill)
                                    :fills    (:fills trade)})
              (assoc :last-execution (-> trade :fills last))
              (update :trade-status (fn [nb]
                                      (if fully-executed?
                                        nb
                                        (assoc nb :amount-remaining amount))))
              (update side (fn [side]
                             (if fully-executed?
                               side
                               ;; unfilled market orders are booked as limits at last execution
                               (if (= (:order-type trade) :market)
                                 (assoc side trade-id (assoc (dissoc trade :fills)
                                                        :order-type :limit
                                                        :price (-> trade :fills last :price)))
                                 (assoc side trade-id (dissoc trade :fills))))))
              (update :price->quantity (fn [pq]
                                         (if fully-executed?
                                           pq
                                           (update-in pq [side
                                                          (if (= (:order-type trade) :market)
                                                            (-> trade :fills last :price)
                                                            price)]
                                                      (fnil + 0) amount))))))
        ;; no matching trades, add to book if it's a limit order, reject market orders due to insufficient liquidity
        (add-to-book this original-trade))))
  (get-matching-trades
    [this order]
    (let [trade (:trade order)]
      (filter (price-acceptable  (:price trade)
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
          (dissoc-in [(:side trade) trade-id])
          (update-or-dissoc-in [:price->quantity (:side trade) (:price trade)] zero? - (:amount trade)))))
  (bid-ask [this]
    (let [bid-price (-> bid peek last :price)
          ask-price (-> ask peek last :price)]
      {:bid {:price bid-price
             :amount (get-in price->quantity [:bid bid-price])}
       :ask {:price ask-price
             :amount (get-in price->quantity [:ask ask-price])}})))

(defn best-bid [orderbook]
  (:bid (protocols/bid-ask orderbook)))

(defn best-ask [orderbook]
  (:ask (protocols/bid-ask orderbook)))

(defn new-fifo-book
  "Constructor for FifoBook"
  ([ticker]
   (new-fifo-book ticker (display/->FifoBookTableDisplay (sorted-map) (sorted-map))))
  ([ticker bookview]
   (map->FifoBook
     {:ticker          ticker
      :bid             (priority-map-keyfn (fn [{:keys [price order-time]}] [(- price) order-time]))
      :ask             (priority-map-keyfn (fn [{:keys [price order-time]}] [price order-time]))
      :last-execution  {}
      :executed-trades {}
      :trade-status    {}
      :price->quantity bookview})))
