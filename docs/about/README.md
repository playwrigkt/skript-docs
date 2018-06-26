Asynchronous programs offer resiliency under load and better use of hardware resources for I/O bound tasks, which are 
attractive to developers who are writing web APIs.  Unfortunately, asynchronous applications also introduce code 
complexity and bring the potential of a very messy code base.  This document walks through the development of a
clear,concise, and asynchronous api method to explain the purpose and use case for skript.

## Callbacks

Lets take a look at a simple example with the [vertx sql client](https://vertx.io/blog/using-the-asynchronous-sql-client/). An implementation of an asynchronous method may look 
something like this:


```kotlin:ank
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.sql.SQLClient
import io.vertx.ext.sql.SQLConnection

val authQuery = "SELECT session_key, user_id, expiration FROM user_session where session_key = ? and expiration > now()"

fun authorize(sqlClient: SQLClient, token: String, onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
    sqlClient.getConnection {
        it.map {
            it.queryWithParams(authQuery, JsonArray(listOf(token)), {
                it.map {
                    if(it.rows.size == 1 ) {
                        onSuccess()
                    } else {
                        onFailure(RuntimeException("not authorized"))
                    }
                }.otherwise { throwable ->
                    onFailure(throwable)
                }
            })
            Unit
        }.otherwise { throwable ->
            onFailure(throwable)
        }
    }
}
```

This method is straightforward enough: look for a user's non expired auth token, and run a function if it is found.  However,
What if we want to do more work that uses the same database connection? Lets pull out a method that acquires the connection,
 and pass the connection into queries, and build up a real world example: a user authenticating, authorizing, and  reading
 a user profile.
 
```kotlin:ank
data class UserProfile(val id: String, val name: String)

//get the sql connection, and handle it asynchronously
fun getConnection(sqlClient: SQLClient, onSuccess: (SQLConnection) -> Unit, onFailure: (Throwable) -> Unit) {
    sqlClient.getConnection { it
            .map(onSuccess)
            .otherwise(onFailure)
    }
}

//authorize a sessionKey
fun authorize(sqlConnection: SQLConnection, sessionKey: String, onSuccess: (String) -> Unit, onFailure: (Throwable) -> Unit) {
    sqlConnection.queryWithParams("SELECT user_id FROM user_session where session_key = ? and expiration > now()", JsonArray(listOf(sessionKey))) {
        it.map {
            try {
                it.rows.firstOrNull()
                        ?.getString("user_id")
                        ?.let(onSuccess)
                        ?: onFailure(RuntimeException("not authorized"))
            } catch(exception: RuntimeException) {
                onFailure(exception)
            }
        }.otherwise(onFailure)
    }
}

//authenticatte a user for an action
fun authenticate(sqlConnection: SQLConnection, userId: String, permission: String, onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
    sqlConnection.queryWithParams("SELECT * from user_priviledge where user_id = ? AND permission = ?", JsonArray(listOf(userId, permission))) {
        it.map {
            if(it.rows.size == 1) {
                onSuccess()
            } else {
                onFailure(RuntimeException("not authenticated"))
            }
        }.otherwise(onFailure)
    }
}


//get a user profile
fun getUser(sqlConnection: SQLConnection, userId: String, onSuccess: (UserProfile) -> Unit, onFailure: (Throwable) -> Unit) {
    sqlConnection.queryWithParams("SELECT * from user_profile where user_id = ?", JsonArray(listOf(userId))) {
        it.map {
            try {
                it.rows.firstOrNull()
                        ?.let { UserProfile(userId, it.getString("name"))}
                        ?.let(onSuccess)
                        ?:onFailure(RuntimeException("not found"))
            } catch(exception: RuntimeException) {
                onFailure(exception)
            }
        }.otherwise(onFailure)
    }
}
```

We now have a set of callback based methods that can run our queries, 
now lets put them together:


```kotlin:ank
fun queryUser(sessionKey: String, userId: String, sqlClient: SQLClient, onSuccess: (UserProfile) -> Unit, onFailure: (Throwable) -> Unit) {
    getConnection(sqlClient, { sqlConnection ->
        authorize(sqlConnection, sessionKey,
                onSuccess = {
                    authenticate(sqlConnection, userId, "read.user",
                            onSuccess = {
                                getUser(sqlConnection, userId,
                                        onSuccess =onSuccess,
                                        onFailure = {
                                            sqlConnection.close()
                                            onFailure(it)
                                        })
                            },
                            onFailure = {
                                sqlConnection.close()
                                onFailure(it)
                            })
                },
                onFailure = {
                    sqlConnection.close()
                    onFailure(it)
                })
    }, onFailure)
}
```

This code has a few bad smells. Firstly the call to onFailure is repeated several times.  What if the implementor forgot
one of the calls to close the connection, or forgot to  invoke onFailure? These are the type of mistakes that are very
easy to make, and result in asynchronous results never being completed. Dealing with all of these callbacks is tedious,
error prone, and verbose.

## Futures to the rescue

Vertx includes an implementation of a future that can clean up api's like this significantly.  By using futures, we
don't have to pass in functions that define how the api responds, rather we get a future that is much nicer to work with
and helps to eliminate some code completion.

Lets rewrite all of  the above methods with futures as well as a couple  of  util methods to wrap close and rollback in 
functions:


```kotlin:ank
fun getConnection(sqlClient: SQLClient): Future<SQLConnection> {
    val future = Future.future<SQLConnection>()
    sqlClient.getConnection(future.completer())
    return future
}

fun authorize(sqlConnection: SQLConnection, sessionKey: String): Future<String> {
    val future = Future.future<String>()
    sqlConnection.queryWithParams("SELECT user_id FROM user_session where session_key = ? and expiration > now()", JsonArray(listOf(sessionKey))) {
        it.map {
            try {
                it.rows.firstOrNull()
                        ?.getString("user_id")
                        ?.let(future::complete)
                        ?: future.fail(RuntimeException("not authorized"))
            } catch(exception: RuntimeException) {
                future.fail(exception)
            }
        }.otherwise(future::fail)
    }
    return future
}

fun authenticate(sqlConnection: SQLConnection, userId: String, permission: String): Future<Unit> {
    val future = Future.future<Unit>()
    sqlConnection.queryWithParams("SELECT * from user_priviledge where user_id = ? AND permission = ?", JsonArray(listOf(userId, permission))) {
        it.map {
            if(it.rows.size == 1) {
                future.complete(Unit)
            } else {
                future.fail(RuntimeException("not authenticated"))
            }
        }.otherwise(future::fail)
    }
    return future
}

fun getUser(sqlConnection: SQLConnection, userId: String): Future<UserProfile> {
    val future  = Future.future<UserProfile>()
    sqlConnection.queryWithParams("SELECT * from user_profile where user_id = ?", JsonArray(listOf(userId))) {
        it.map {
            try {
                it.rows.firstOrNull()
                        ?.let { UserProfile(userId, it.getString("name"))}
                        ?.let(future::complete)
                        ?:future.fail(RuntimeException("not found"))
            } catch(exception: RuntimeException) {
                future.fail(exception)
            }
        }.otherwise(future::fail)
    }
    return future
}

fun close(sqlConnection: SQLConnection): Future<Unit> {
    val future  = Future.future<Unit>()
    sqlConnection.close {
        it.map {
            future.complete(Unit)
        }.otherwise(future::fail)
    }
    return future
}


fun rollback(sqlConnection: SQLConnection): Future<Unit> {
    val future  = Future.future<Unit>()
    sqlConnection.rollback {
        it.map {
            future.complete(Unit)
        }.otherwise(future::fail)
    }
    return future
}

```

And an example that strings all of these methods together:

```kotlin:ank
fun queryUser(sessionKey: String, userId: String, sqlClient: SQLClient): Future<UserProfile> {
    return getConnection(sqlClient)
            .compose { sqlConnection ->
                val result = authorize(sqlConnection, sessionKey)
                        .compose { authenticate(sqlConnection, userId, "read.user")}
                        .compose { getUser(sqlConnection, userId) }

                result
                        .recover { error ->
                            rollback(sqlConnection)
                                    .compose { close(sqlConnection) }
                                    .compose { Future.failedFuture<UserProfile>(error)}
                        }
                        .compose { userProfile ->
                            close(sqlConnection)
                                    .map { userProfile }
                        }
            }
}
```

As compared to the previous iteration, this method has a lot less code duplication.  Error handling for the connection
is encapsulated entirely within this method, but the errors themselves are still exposed to the future. This allows 
the user to define what happens when there is an error without forcing them to handle the specific logic around the
sql connection.

However,  this method still is far more verbose than it  needs to be.  The only logic unique to this method is the chain
from authorize to authentiate to getUser.  The error handling, and the code that gets the sql connection are all common
to any function that uses the sql client.  We can pull out the logic to actuallly handlle acquiring  and releasing a sql 
connection:

```kotlin:ank
fun <I, O> handleConnection(input: I, sqlClient: SQLClient, fn: (I, SQLConnection) -> Future<O>): Future<O> {
    return getConnection(sqlClient)
            .compose { sqlConnection ->
                fn(input, sqlConnection)
                        .recover { error ->
                            rollback(sqlConnection)
                                    .compose { close(sqlConnection) }
                                    .compose { Future.failedFuture<O>(error)}
                        }
                        .compose { userProfile ->
                            close(sqlConnection)
                                    .map { userProfile }
                        }
            }
}
```

Now, the business logic of the api method can be exressed much more directly.  It simpy specifies to handle a sql 
connection, authorize, authenticate, and get a user.  Our other methods that make sql queries or commands can use the 
same `handleConnection` method to handle acquiring and releasing the connection.  This paves the road to a reduced 
amount of code duplication and concise api methods.

```kotlin:ank
fun queryUser(sessionKey: String, userId: String, sqlClient: SQLClient): Future<UserProfile> {
    return handleConnection(Pair(sessionKey, userId), sqlClient) { (sessionKey, userId), sqlConnection ->
        authorize(sqlConnection, sessionKey)
                .compose { authenticate(sqlConnection, userId, "read.user")}
                .compose { getUser(sqlConnection, userId) }

    }
}
```

This method is a lot more concise, and just by reading  the code  its quite a bit easier to understand what is happening
at  a high level.  Unfortunately, there is a lot of code just to support this that developers don't necessarily want to 
rewrite for each api.

## Skript

Skript provides an api to build clean, asynchronous code.  The exact same  example can be implemented with  skript.

First, lets implement a sql query with skript:

```kotlin:ank
import arrow.core.Try
import playwrigkt.skript.Skript
import playwrigkt.skript.ex.join
import playwrigkt.skript.result.AsyncResult
import playwrigkt.skript.sql.SqlCommand
import playwrigkt.skript.sql.SqlSkript
import playwrigkt.skript.sql.SqlStatement
import playwrigkt.skript.sql.transaction.SqlTransactionSkript
import playwrigkt.skript.troupe.SqlTroupe

val authenticate: Skript<String, String, SqlTroupe> = SqlSkript.query(
        toSql = Skript.map {
            SqlCommand.Query(SqlStatement.Parameterized(
                    query = "SELECT user_id FROM user_session where session_key = ? and expiration > now()",
                    params= listOf(it)))
        },
        mapResult = Skript.mapTry {
            if(!it.result.hasNext()) {
                Try { it.result.next().getString("user_id") }
            } else {
                Try.Failure<String>(RuntimeException("not authorized"))
            }
        })
```

The query is  itself a skript.  A skript is a function, with an input, and output, and a `Troupe` which  can perform
application resources.  If an application were a performance of a play, a skript is the script of that play (or  a
part of that script).  This particular skript defines a sql query.  The first  argument to `query` is another skript
that maps to  a `SqlCommand`, which the `SQLTroupe` knows  how to execute.  The second skript defines how to map from
the result of the sqlQuery - in this case it just gets a single field from the resultSet.

The other two queries can also be implemented in a similar manner:

```kotlin:ank
val authorize: Skript<Pair<String, String>, Unit, SqlTroupe> = SqlSkript.query(
        toSql = Skript.map {
            SqlCommand.Query(SqlStatement.Parameterized(
                    query = "SELECT * from user_priviledge where user_id = ? AND permission = ?",
                    params = listOf(it.first, it.second)))
        },
        mapResult = Skript.mapTry {
            if(it.result.hasNext()) {
                Try.Success(Unit)
            } else {
                Try.Failure(RuntimeException("not authenticated"))
            }
        })

val getUser: Skript<String, UserProfile, SqlTroupe> = SqlSkript.query(
        toSql = Skript.map {
            SqlCommand.Query(SqlStatement.Parameterized(
                    query = "SELECT * from user_profile where user_id = ?",
                    params =    listOf(it)))
        },
        mapResult = Skript.mapTry {
            if(it.result.hasNext()) {
                Try { it.result.next() }
                        .map { UserProfile(
                                it.getString("user_id"),
                                it.getString("name")) }
            } else {
                Try.Failure(RuntimeException("not found"))
            }
        })
```

Finally, skript allows these functions to be chained together in a convenient way:


```kotlin:ank
data class SessionKeyAndInput<T>(val sessionKey: String, val input:  T)

val queryUser: Skript<SessionKeyAndInput<String>, UserProfile, SqlTroupe> =
        SqlTransactionSkript.transaction(Skript.identity<SessionKeyAndInput<String>, SqlTroupe>()
                .split(Skript.identity<SessionKeyAndInput<String>, SqlTroupe>()
                        .map { it.sessionKey }
                        .compose(authenticate)
                        .map { Pair(it, "read.user") }
                        .compose(authorize))
                .join { sessionKeyAndInput, unit -> sessionKeyAndInput.sessionKey }
                .compose(getUser))
```
#### Skript Breakdown
Breaking this down line by line:

First, we declare the property, and the input, output, and troupe of the skript.

```kotlin
val queryUser: Skript<SessionKeyAndInput<String>, UserProfile, SqlTroupe> =
```

Second, we declalre that we are going to perform a sql transaction.  This will automatically handle
acquiring a sql connection, and commiting, rolling back, and closing in  the appropriate cases. (i.e. always close,
commit on success, and rollback on failure)

This line also creates an "identity" skript.  An identity  skript performs no transformation, and is a  good way
to start a skript chain.

```kotlin
//declare that  we will start  a trasnaction
SqlTransactionSkript.transaction(Skript.identity<SessionKeyAndInput<String>, SqlTroupe>(
```

Then, we "split" the skript. Split is an alias for "both", which runs two skripts in parallel.
Split is equivalent to running both with an identity skript and the skript that is  passed in.  Split is used here
because we need the original input to actuallly get the user later, but the authentication would swallow that value
otherwise.

Since split takes in a skript as input, that skript is initiallized with `identity` to clarify a new
skript has been created.
 
```kotlin
    .split(Skript.identity<SessionKeyAndInput<String>, SqlTroupe>()
```

Transform the input  into the sessionKey, and then run the authenticate query:

```kotlin
            .map { it.sessionKey }
            .compose(authenticate)
```

Then create a pair of  the user_id (result  from authorize), and the permission string to authorize, then run the 
authorize query:

```kotlin
            .map { Pair(it, "read.user") }
            .compose(authorize))
```

Join back to the parent skript and map to the input value.  Here `unit` is the output of the authorization and
authentication skript passed into `split`, and `sessionKeyAndInput` was the value passed into `split`.

```kotlin
    //join the skript - get the input before split, and the result after spit finishes
    .join { sessionKeyAndInput, unit -> sessionKeyAndInput.input }
```

Finallly, get the user with the id that was passed in:

```kotlin
    //run the getUser skript
    .compose(getUser))
```

By breaking this skript down line by line, the business logic of the application is readily apparent.  Even though IO
is executed asynchronously, the business logic is expressed in a sequential series of actions and is relatively
free of clutter.

In addition to readability,  errors are handled cleanly - if any part of the skript throws an exception, it will be 
exposed via the result and execution will short circuit.

## Executing a  skript

Executing a skript