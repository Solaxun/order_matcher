![badge](https://action-badges.now.sh/mwoodworth/order_matcher?action=Run tests)
# order-matcher

An implementation of the FIFO price/time order matching algorithm, [as specified by the CME group](https://www.cmegroup.com/confluence/display/EPICSANDBOX/Supported+Matching+Algorithms#SupportedMatchingAlgorithms-FIFO).  Orders are matched first by price, and if two orders have the same price, then by time on a FIFO basis.  Modifications to orders result in a re-queing. 

## Usage

To place an order first you must create a trade with `build-trade`.  This function should be provided a map consisting of the following keys:

- :order-type (either :buy or :sell)
- :amount (quantity of the purchase)
- :price (either the price the buyer is willing to pay or seller willing to accept)
- :security (ticker of the security to be purchased or sold)
- :user-id (entity executing the trade - this will be used to view a list of trades a customer has placed, and to modify existing trades.)

Additionally, the following keys will be added for you when the trade is created:
- :trade-id (UUID representing an individual trade)
- :order-time (time at which the trade was entered)
 
 Once a trade has been made, execute it by calling `add-order` with the trade as an argument.

## Examples

Make a new trade:

```clojure
(def my-trade
  (build-trade {:price 90 :amount 1003 :user-id "fred" :order-type :sell})
```

```clojure
>>> #'order-matcher.orderbook/build-trade{:trade-id #uuid "21e6a9c3-3918-4614-9c92-ee748a9b90f6", :trade {:price 90, :amount 1003, :user-id "fred", :order-type :sell, :order-time #object[java.time.LocalDateTime 0x1e7dc670 "2020-01-20T17:26:46.673"]}}
```

Place a trade:

`(add-order mytrade)`

As soon as the trade is added, the engine will attempt to find a match.  If a match is found, the trade will be executed immediately, otherwise it will be added to the orderbook until a matching trade is found.


### Future Work
Implement a variety of other order matching strategies as outlined by the CME - [Supported Matching Algorithms](https://www.cmegroup.com/confluence/display/EPICSANDBOX/Supported+Matching+Algorithms)

## License

Copyright © 2019 Mark Woodworth

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
