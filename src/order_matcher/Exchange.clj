(ns order-matcher.Exchange)

(defrecord FifoExchange [books customer-trades executed-trades])

(defn attempt-fill
  [exchange order]
  (let [{:keys [ticker price amount user-id] :as trade} (:trade order)
        trade-id (:trade-id order)
        matched-side (other-side trade)
        book (get-in exchange [:books ticker])
        offset-trades (get book matched-side)
        matched (when offset-trades (protocols/get-matching-trades book order))]
    ;; either no book or no trades in other side of book, store trade and return
    (if (empty? matched)
      (add-order exchange order)
      (let [[updated-trades remaining-amt] (reduce allocate-orders [{} amount] matched)

            updated-book
            (reduce-kv (fn [orderbook other-id other-trade]
                         (if (zero? (:amount other-trade))
                           (remove-from-book orderbook other-id other-trade)
                           (update-order-quantity orderbook other-id other-trade)))
                       (if (zero? remaining-amt)
                         (remove-from-book book trade-id trade)
                         (update-order-quantity book trade-id (assoc trade :amount remaining-amt)))
                       updated-trades)]
        (-> exchange)))))

(defn add-order
  [exchange
   {{:keys [side ticker user-id] :as trade} :trade
    trade-id :trade-id}]
  (println "adding order:\n" trade)
  (-> exchange
      (update-in [:books ticker]
                 (fnil assoc-in (new-fifo-book))
                 [side trade-id]
                 trade)
      (update-in [:customer-trades user-id] (fnil merge {}) {trade-id trade})))

(defn dissoc-in [m [k & ks]]
  (if ks
    (assoc m k (dissoc-in (get m k) ks))
    (dissoc m k)))

(defn remove-from-book [orderbook trade-id trade]
  (-> orderbook
      (dissoc-in [(:ticker trade) (:order-type trade) trade-id])
      (dissoc-in [:customer-trades (:user-id trade) trade-id])))

(defn update-order-quantity [orderbook trade-id trade]
  (-> orderbook
      (update-in [(:ticker trade) (:order-type trade) trade-id] merge trade)
      (update-in [:customer-trades (:user-id trade) trade-id] merge trade)))
