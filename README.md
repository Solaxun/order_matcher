# order-matcher

An implementation of various order matching algorithms, initially starting with standard price/time FIFO matching. 

## Installation

Download from http://example.com/FIXME.

## Usage


To place an order, create a trade with `make-trade`.  This function should be provided a map consisting of the following keys:

- :order-type (:buy or :sell)
- :amount (quantity of purchase)
- :price (either the price the buyer is willing to pay or seller willing to accept)
- :security (ticker of the security)
- :user-id (entity executing the trade)
 

## Examples

Main API:
`add-order`
`modify-order`
`view-orders`

Make a new trade:

(def my-trade
  (build-trade {:price 90 :amount 1003 :user-id "fred" :order-type :sell})

```#'order-matcher.orderbook/build-trade{:trade-id #uuid "21e6a9c3-3918-4614-9c92-ee748a9b90f6", :trade {:price 90, :amount 1003, :user-id "fred", :order-type :sell, :order-time #object[java.time.LocalDateTime 0x1e7dc670 "2020-01-20T17:26:46.673"]}}```

Place a trade:

`(add-order mytrade)`

Modify a trade:

`(modify-trade :modification :cancel
               :order-id #uuid "21e6a9c3-3918-4614-9c92-ee748a9b90f6")`

`(modify-trade :modification :update
               :order-id #uuid "21e6a9c3-3918-4614-9c92-ee748a9b90f6"
               :amount 30)`

`(modify-trade :modification :update
               :order-id #uuid "21e6a9c3-3918-4614-9c92-ee748a9b90f6"
               :price 91.25)`


### Future Work
Implement a variety of other order matching strategies as outlined in "TODO CME DOC"

## License

Copyright © 2019 Mark Woodworth

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
