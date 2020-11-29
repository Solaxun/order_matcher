(ns order-matcher.protocols)

(defprotocol OrderBook
  "Defines the implementation methods for a limit order book"
  (fill-order [this order] "Attempts to match the order, if no match is found or not fully matched, the order is booked")
  (get-matching-trades [this order] "Returns a seq of trades that match with the current order")
  (update-order [this trade-id new-order] "Replaces an existing order in the book, the result is re-queued with a new priority")
  (cancel-order [this trade-id] "Removes an order from the book")
  (bid-ask [this] "Returns the top of the book bid/ask price and quantities"))

(defprotocol Display
  (display-book [this] "Show the orderbook in a human readable format"))