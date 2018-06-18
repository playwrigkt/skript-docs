## Core Concepts

Skript models itself after a play.

* Skript: A skript is a linear graph of one or more actions that are
  executed in a non blocking manner. Skripts implement business logic
  in a (mostly) technology agnostic way.
* Troupe: Each skript has a troupe, a troupe has many performers.
* Performer: a performer is part of the Troupe and provides a specific implementation of an application resource
  such as database connections and application configuration properties
* StageManager: A stageManager provides troupes that are capable of performing a Skript.

## What is a skript?

A skript is a set of functions that are run asynchronously and can be
run as many times as desired.  API level skripts should be defined as static variables, either on an `object` or in a `companion object`, but there are some cases where providing a method to build a skript makes sense.

You can think of a skript like a script written by a playwright.  A play has a script that defines what lines an actor should say, and some troupe directions,
but the details of how scripts are actually performed are left up to the directory
and the cast.

Similarly, a skript defines a set of functions, and descriptions of actions to perform.  However, the details of how tasks are performed are left abstract -
for example a script doesn't define how to execute a SQL query or read a file,
only that a query  should be executed or that a file should be read.

A skript offers the following features:

* High readability
* Composability
* Asynchronous execution
* Short circuit error handling
* Low overhead
* Separation of application logic from lower level details

### Skripts all the way down

Skripts are a recursive data structure.  The skript interface exposes methods
that take other skripts as parameters.  For example, a skript can define a
database query or command, but a skript that represents a complex transaction
would likely be composed of several such skripts.

## Troupes and Performers

Skripts wouldn't be very interesting if they only provided functional composition.  One one of the 
major benefit of skript, is the api it provide for interacting with application resources.

A skript can access application resources from a Troupe.  A Troupe is able to provide serveral performers, all of which
perform a special funktion such as SQL interractions, HTTP client calls, Queue Interractions,
or anything else you can imagine.

## StageManagers

A Troupe is managed by a stageManager.  A stage manager handles the
creation and deletion of application resources such as a network
connection or configuration property.


## Venues and Produktions

Skripts are little more than functions that have application resources. The Venue and Produktion model defines an API
for running skripts based on a rule and providing them with said resources.  Venues and Produktions  are data sources.  
You can think of a Venue as a particular data source and a Produktion as a consumer of that data.  Forexample a message
broker and a consumer or an httpServer and an endpoint implementation or a time based schedule.

## Extensibility

Skript is exensible.  Any user can implement their own StageManager, Troupe,
and Skripts to provide custom functionality. Additionally a user could choose to
implement their own Venue and Produktion.

It is both possible and planned that more implementations will be made available as the
development of skript progresses.  Currently there are other modules planned such as

* Caching
* Cassandra

## APIs and Implementations

Currently there are several api's defined in skript that conform to the
core design:
* [http](api/http) -  api for http server and client
* [sql](api/sql) - api for sql interactions
* [serialize](api/serialize) - api for serialization
* [queue](api/queue) - api for queue interactions

A developer should be able to build and test all of their application
logic by only using the classes in the `api` modules.  When it comes time
to run the applicatation, the developer can choose an existing implementation
of an api or write their own, depending on their specific needs.  Currently,
there are two implementations for all of the api's provided by skript:
* Vertx (SQL, Queue, Http, Serialization)
* Couroutine (JDBC, AMQP, Ktor, and Jackson)

There are example applications implemented in the [examples directory](https://github.com/playwrigkt/skript-examples).
