[![Build Status](https://img.shields.io/github/workflow/status/Solaxun/order_matcher/Run%20tests.svg)](https://github.com/Solaxun/order_matcher/actions)
# order-matcher

An immutable limit order book implementing FIFO price/time priority matching. Orders are matched first by price, and if two orders have the same price, then by time with the oldest trades matched first.  Modifications to orders result in a re-queing and a new priority for the trade will be set according to the updated price and time of such modification. 

## Usage

Create an order with the `order` function.  The following keys are required:

- :order-type (either :limit or :market)
- :price (*for limit orders only*)
- :amount (quantity of the purchase)
- :ticker (ticker of the security to be purchased or sold)
- :side (either :bid or :ask)

The following keys will be added for you when the Order is created:
- :trade-id (UUID representing an individual trade)
- :order-time (time at which the trade was entered)
 
Once a trade has been made, execute it by calling `add-order` with the trade as an argument.

## Examples

Create a limit order to buy 4 shares of aapl at a price of 114.1 or better:

```clojure
(def my-order1 
  (order {:order-type :limit
          :side       :bid
          :price      114.1
          :amount     4
          :ticker     "aapl"})
```

Create a new limit order book:
```clojure
(def aapl-book 
  (new-fifo-book "aapl"))
```

Submit the order to the limit order book:
```clojure
(protocols/fill-order aapl-book my-order1)
```

As soon as the Order is added, the engine will attempt to find a match.  If a match is found, the trade will be executed immediately, otherwise it will be added to the orderbook until a matching trade occurs.  

After running the above example, the FifoBook records the limit order under the `:bid` key where it will rest until a matching `:ask` is submitted:

```clojure
#order_matcher.orderbook.FifoBook{:ticker "aapl",
                                  :bid {#uuid"586807eb-eb8e-4b36-afd5-f1e393cfba91" {:order-type :limit,
                                                                                     :side :bid,
                                                                                     :price 114.1,
                                                                                     :amount 4,
                                                                                     :ticker "aapl",
                                                                                     :order-time #object[java.time.LocalDateTime
                                                                                                         0x3bbcd70c
                                                                                                         "2020-11-26T20:32:35.494"],
                                                                                     :trade-id #uuid"586807eb-eb8e-4b36-afd5-f1e393cfba91"}},
                                  :ask {},
                                  :last-execution {},
                                  :executed-trades {},
                                  :trade-status {:status :pending,
                                                 :trade-id #uuid"586807eb-eb8e-4b36-afd5-f1e393cfba91",
                                                 :amount-remaining 4,
                                                 :fills []},
                                  :price->quantity {:bid {114.1 4}, :ask {}}}
```
The orderbook Record consists of several fields:
- `:price->quantity`  aggregates limit order amounts on the bid and ask side of the book at each price level, providing a view of the current state of the order book.  
- `:executed-trades` any time an order is placed and matches an existing trade in the book, the matched trades which execute will appear here.  
- `:trade-status` shows the status and details of the entered order.  Within this map the `:fills` vector will show the prices and amounts of each fill in the event a trade results in several partial fills as it runs through the opposite side of the book.  
### Future Work
Implement a variety of other order matching strategies, for example: [Supported Matching Algorithms](https://www.cmegroup.com/confluence/display/EPICSANDBOX/Supported+Matching+Algorithms)

## License

Copyright © 2020Mark Woodworth

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
