(ns order-matcher.protocols)

(defprotocol Matcher
  (fill-order [this trade])
  (get-matching-trades [this trade])
  (update-order [this trade new-order])
  (cancel-order [this trade])
  (bid-ask [this]))
