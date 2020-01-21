(ns order-matcher.orderbook
  (:require [java-time :as t]
            [clojure.data.priority-map :refer [priority-map priority-map-keyfn]]))
;; also try priority-map-keyfn-by (constructor keyfn <)
(def orderbook
  (atom {:customer-trades {}})) ; {userid: {:order-id trade} for updates to orders

(defn make-new-book []
  {:buy  (priority-map-keyfn (fn [{:keys [price time]}] [(- price) time]))
   :sell (priority-map-keyfn (fn [{:keys [price time]}] [price time]))})

(defn satisfies [order-type]
  (if (= order-type :buy) >= <=))

(defn other-side [order-type]
  (cond (= order-type :buy) :sell
        (= order-type :sell) :buy
        :else (throw (Exception. "Order type must be :buy or :sell"))))

;; build-trade, time and id should be assigned immediately before filling
(defn build-trade [{:keys [ticker order-type amount price user-id] :as trade}]
  ;;{:pre (every? #(m %) [:price :amount :user-id :order-type])}
  (hash-map :trade-id (java.util.UUID/randomUUID)
            :trade (assoc trade :order-time (t/local-date-time))))

(defn allocate-orders [[executed-trades remaining-amt] [c-id c-trade]]
  ;;(println c-id c-trade)
  (if (zero? remaining-amt)
    (reduced [executed-trades remaining-amt])
    [(assoc executed-trades c-id (update c-trade :amount #(max 0 (- % remaining-amt))))
     (max 0 (- remaining-amt (:amount c-trade)))]))

(defn dissoc-in [m [k & ks]]
  (if ks
    (assoc m k (dissoc-in (m k) ks))
    (dissoc m k)))

(defn remove-from-book [orderbook trade-id trade]
  (-> orderbook
      (dissoc-in [(:ticker trade) (:order-type trade) trade-id])
      (dissoc-in [:customer-trades (:user-id trade) trade-id])))

(defn update-order-quantity [orderbook trade-id trade]
  (-> orderbook
      (update-in [(:ticker trade) (:order-type trade) trade-id] merge trade)
      (update-in [:customer-trades (:user-id trade) trade-id] merge trade)))

(defn fill-order
  [orderbook
   {id :trade-id
    {:keys [ticker price amount user-id order-time order-type] :as trade} :trade :as order}
   candidates] ;; candidates = [id {trade} id2 {trade2}]
  (let [[updated-trades remaining-amt] (reduce allocate-orders
                                                [{} amount]
                                                candidates)]
    (reduce-kv (fn [orderbook other-id other-trade]
                 (if (zero? (:amount other-trade))
                   (remove-from-book orderbook other-id other-trade)
                   (update-order-quantity orderbook other-id other-trade)))
               (if (zero? remaining-amt)
                 (remove-from-book orderbook id trade)
                 (update-order-quantity orderbook id (assoc trade :amount remaining-amt)))
               updated-trades)))

(defn add-to-book
  [orderbook
   {id :trade-id
    {:keys [ticker price amount user-id order-time order-type] :as trade} :trade :as order}]
  (-> orderbook
      (update ticker (fnil assoc-in (make-new-book)) [order-type id] trade)
      (update-in [:customer-trades user-id] (fnil merge {}) {id trade})))

(defn price-acceptable [price order-type]
  (fn [[id offset-trade]]
    ((satisfies order-type) price (:price offset-trade))))

(defn add-order
  "fill order if there is an offsetting match, otherwise add to book
  when adding entries, buys are sorted with highest price first, sells
  with lowest price first."
  ;;TODO: guard against zero amount trades. These will auto-remove right now but best to disallow
  [{id :trade-id
    {:keys [ticker price amount user-id order-time order-type] :as trade} :trade :as order}]
  (let [offset-side (other-side order-type)
        candidates  (filter (price-acceptable price order-type)
                            (get-in @orderbook [ticker offset-side]))]
    (if (not-empty candidates)
      (swap! orderbook fill-order order candidates)
      (swap! orderbook add-to-book order))))

;; some order -> all candidates that meet the price
;; reduce over those that meet price  -> no, because changing as we go so doseq

; candidates to what we have now + either update trade if nonzero or pop if filled
;; if the offset is zero'd in the process of filling the trade, pop it
;; if it isn't, then it had enough to fill and have some left over, so modify the
;; offsets amount and consider the trade filled (remove it)

(add-watch
 orderbook
 :trade-notification
 (fn [k r o n] (println "value was" o "and is now" n "!")))

(clojure.pprint/pprint (add-order (build-trade {:order-type :buy :price 120 :amount 1337 :user-id 1 :ticker "aapl"})))
(add-order (build-trade {:order-type :buy :price 120 :amount 1337 :user-id 2}))
(add-order (build-trade {:order-type :buy :price 100 :amount 1337 :user-id 3}))
(add-order (build-trade {:order-type :buy :price 99.4 :amount 1337 :user-id 4}))
(add-order (build-trade {:order-type :buy :price 120 :amount 1337 :user-id 5}))

(doseq [[id trade] (:buy @orderbook)] (println trade))

(add-order (build-trade {:order-type :sell :price 120 :amount 1337 :user-id 6 :ticker "aapl"}))
(add-order (build-trade {:order-type :sell :price 120 :amount 1337 :user-id 7}))
(add-order (build-trade {:order-type :sell :price 110 :amount 1337 :user-id 8}))
(add-order (build-trade {:order-type :sell :price 114 :amount 1337 :user-id 9}))
(add-order (build-trade {:order-type :sell :price 112 :amount 1337 :user-id 10}))
(add-order (build-trade {:order-type :sell :price 90 :amount 1337 :user-id 10}))
(clojure.pprint/pprint @orderbook)
