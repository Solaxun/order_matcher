(ns order-matcher.orderbook
  (:require [java-time :as t]
            [clojure.data.priority-map :refer [priority-map priority-map-keyfn]]))
;; also try priority-map-keyfn-by (constructor keyfn <)
(def orderbook
  ({atom :buy  (priority-map-keyfn (fn [{:keys [price time]}] [(- price) time]))
    :sell (priority-map-keyfn (fn [{:keys [price time]}] [price time]))
    :customer-trades {}})) ; {userid: [trades...]} for updates to orders

(defn satisfies [order-type]
  (if (= order-type :buy) >= <=))

(defn other-side [order-type]
  (if (= order-type :buy) :sell :buy))

;; build-trade, time and id should be assigned immediately before filling
(defn build-trade [{:keys [order-type amount price user-id] :as trade}]
  ;;{:pre (every? #(m %) [:price :amount :user-id :order-type])}
  (hash-map :trade-id (java.util.UUID/randomUUID)
            :trade (assoc trade :order-time (t/local-date-time))))
;;(build-trade {:price 90 :amount 1003 :user-id "fred" :order-type :sell})

(defn allocate-orders [[executed-trades remaining-amt] [c-id c-trade]]
  ;;(println c-id c-trade)
  (if (<= remaining-amt 0)
    (reduced [executed-trades remaining-amt])
    [(assoc executed-trades c-id (update c-trade :amount #(max 0 (- % remaining-amt))))
     (max 0 (- remaining-amt (:amount c-trade)))]))

(defn dissoc-in [m [k & ks]]
  (if ks
    (assoc m k (dissoc-in (m k) ks))
    (dissoc m k)))

(allocate-orders
 [{} 100]
 [999 (:trade (build-trade {:order-type :sell :price 120 :amount 140 :user-id 6}))])

(defn remove-from-book [orderbook [id trade]]
  (-> orderbook
      (dissoc-in [(:order-type trade) id])
      (dissoc-in [:customer-trades (:customer-id trade)])))

(defn fill-order!
  [{id :trade-id
    {:keys [price amount user-id order-time order-type] :as trade} :trade
    :as order} candidates] ;; candidates = [id {trade} id2 {trade2}]
  (let [[executed-trades remaining-amt] (reduce allocate-orders
                                                [{} amount]
                                                candidates)]
    (doseq [[other-id other-trade] executed-trades]
      (if (zero? (:amount other-trade))
        ;; TODO: every change to  buy/sell needs to be mirrored in customer-trades
        ;; i've done that for clearing trades when zero remain, but not yet for modifying
        ;; trades when there are still some unfilled amounts remaining
        (swap! orderbook #(remove-from-book % [other-id other-trade]))
        ;;TODO:
        (swap! orderbook
               assoc-in
               [(other-side order-type) other-id :amount]
               (:amount other-trade))))
    (if (zero? remaining-amt)
      (swap! orderbook #(remove-from-book % [id trade]))
      ;;TODO:
      (swap! orderbook assoc-in [order-type id :amount] remaining-amt))))

(defn add-to-book
  [orderbook
   {id :trade-id
    {:keys [price amount user-id order-time order-type] :as trade} :trade :as order}]
  (-> orderbook
      (update order-type assoc id trade)
      (update-in [:customer-trades user-id] (fnil merge {}) order)))

(defn possible-matching-trades [price order-type]
  (fn [[id offset-trade]]
    ((satisfies order-type) price (:price offset-trade))))

(defn add-order
  "fill order if there is an offsetting match, otherwise add to book
  when adding entries, buys are sorted with highest price first, sells
  with lowest price first."
  [{id :trade-id {:keys [price amount user-id order-time order-type] :as trade} :trade :as order}]
  (let [offset-side (other-side order-type)
        candidates  (filter (possible-matching-trades price order-type)
                            (@orderbook offset-side))]
    (if (not-empty candidates)
      (fill-order! order candidates)
      (swap! orderbook #(add-to-book % order)))))

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


(add-order (build-trade {:order-type :buy :price 120 :amount 1337 :user-id 1}))
(add-order (build-trade {:order-type :buy :price 120 :amount 1337 :user-id 2}))
(add-order (build-trade {:order-type :buy :price 100 :amount 1337 :user-id 3}))
(add-order (build-trade {:order-type :buy :price 99.4 :amount 1337 :user-id 4}))
(add-order (build-trade {:order-type :buy :price 120 :amount 1337 :user-id 5}))

(doseq [[id trade] (:buy @orderbook)] (println trade))

(add-order (build-trade {:order-type :sell :price 120 :amount 1337 :user-id 6}))
(add-order (build-trade {:order-type :sell :price 120 :amount 1337 :user-id 7}))
(add-order (build-trade {:order-type :sell :price 110 :amount 1337 :user-id 8}))
(add-order (build-trade {:order-type :sell :price 114 :amount 1337 :user-id 9}))
(add-order (build-trade {:order-type :sell :price 112 :amount 1337 :user-id 10}))
(add-order (build-trade {:order-type :sell :price 90 :amount 1337 :user-id 10}))

(peek (@orderbook :buy))
(peek (@orderbook :sell))

;;;; core api ;;;;

;; add-trade
;; modify-trade
;; view trades
((juxt (comp count :buy) (comp count :sell))
 @orderbook)
