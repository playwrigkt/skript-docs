import arrow.core.Try
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.sql.SQLClient
import io.vertx.ext.sql.SQLConnection
import playwrigkt.skript.Skript
import playwrigkt.skript.ex.join
import playwrigkt.skript.result.AsyncResult
import playwrigkt.skript.sql.SqlCommand
import playwrigkt.skript.sql.SqlSkript
import playwrigkt.skript.sql.SqlStatement
import playwrigkt.skript.sql.transaction.SqlTransactionSkript
import playwrigkt.skript.troupe.SqlTroupe
import playwrigkt.skript.troupe.VertxSqlTroupe


fun main() {
    val vertx = Vertx.vertx()
    val eventBus = vertx.eventBus()
    val sqlClient = JDBCClient.createShared(vertx, JsonObject())


}

val authQuery = "SELECT session_key, user_id, expiration FROM user_session where session_key = ? and expiration > now()"

fun <I, O> handdleConnection(input: I, sqlClient: SQLClient, fn: (I, SQLConnection) -> Future<O>): Future<O> {
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

fun queryUser(sessionKey: String, userId: String, sqlClient: SQLClient): Future<UserProfile> {
    return handdleConnection(Pair(sessionKey, userId), sqlClient) { (sessionKey, userId), sqlConnection ->
        authorize(sqlConnection, sessionKey)
                .compose { authenticate(sqlConnection, userId, "read.user")}
                .compose { getUser(sqlConnection, userId) }

    }
}


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

data class SessionKeyAndInput<T>(val sessionKey: String, val input:  T)
val queryUser: Skript<SessionKeyAndInput<String>, UserProfile, SqlTroupe> =
        SqlTransactionSkript.transaction(Skript.identity<SessionKeyAndInput<String>, SqlTroupe>()
                .split(Skript.identity<SessionKeyAndInput<String>, SqlTroupe>()
                        .map { it.sessionKey }
                        .compose(authenticate)
                        .map { Pair(it, "read.user") }
                        .compose(authorize))
                .join { sessionKeyAndInput, unit -> sessionKeyAndInput.input }
                .compose(getUser))

val sqlClient: SQLClient = TODO("create sql client")
val troupe = VertxSqlTroupe(sqlClient)
val result = queryUser.run(SessionKeyAndInput("sessionKey", "userId"), troupe)

fun execute(sqlTroupe: SqlTroupe, sessionKey: String, userId: String): AsyncResult<UserProfile> {
    return queryUser.run(SessionKeyAndInput(sessionKey, userId), sqlTroupe)
}
//fun queryUser(sessionKey: String, userId: String, sqlClient: SQLClient): Future<UserProfile> {
//    return getConnection(sqlClient)
//            .compose { sqlConnection ->
//                val result = authorize(sqlConnection, sessionKey)
//                        .compose { authenticate(sqlConnection, userId, "read.user")}
//                        .compose { getUser(sqlConnection, userId) }
//
//                result
//                        .recover { error ->
//                            rollback(sqlConnection)
//                                    .compose { close(sqlConnection) }
//                                    .compose { Future.failedFuture<UserProfile>(error)}
//                        }
//                        .compose { userProfile ->
//                            close(sqlConnection)
//                                    .map { userProfile }
//                        }
//            }
//}

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

fun getConnection(sqlClient: SQLClient, onSuccess: (SQLConnection) -> Unit, onFailure: (Throwable) -> Unit) {
    sqlClient.getConnection { it
            .map(onSuccess)
            .otherwise(onFailure)
    }
}

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

data class UserProfile(val id: String, val name: String)
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


class AuthorizationService(val sqlClient: SQLClient) {
    val authQuery = "SELECT session_key, user_id, expiration FROM user_session where session_key = ? and expiration > now()"
    fun authorize(token: String, onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
        sqlClient.getConnection {
            it.map {
                it.queryWithParams(authQuery, JsonArray(listOf(token))) {
                    it.map {
                        if(it.rows.size == 1 ) {
                            onSuccess()
                        } else {
                            onFailure(RuntimeException("not authorized"))
                        }
                    }.otherwise { throwable ->
                        onFailure(throwable)
                    }
                }
                Unit
            }.otherwise { throwable ->
                onFailure(throwable)
            }
        }
    }

    fun authorize(token: String): Future<Unit> {
        val future = Future.future<Unit>()
        sqlClient.getConnection {
            it.map {
                it.queryWithParams(authQuery, JsonArray(listOf(token)), {
                    it.map {
                        if(it.rows.size == 1 ) {
                            future.complete(Unit)
                        } else {
                            future.fail(RuntimeException("not authorized"))
                        }
                    }.otherwise { throwable ->
                        future.fail(throwable)
                    }
                })
                Unit
            }.otherwise { throwable ->
                future.fail(throwable)
            }
        }
        return future
    }

    fun authorizeClean(token: String): Future<Unit> {
        val future = Future.future<Unit>()
        sqlClient.getConnection {
            it.map {
                it.queryWithParams(authQuery, JsonArray(listOf(token)), {
                    it.map {
                        when(it.rows.size) {
                            1 -> future.complete(Unit)
                            else -> future.fail(RuntimeException("not authorized"))
                        }
                    }.otherwise(future::fail)
                })
                Unit
            }.otherwise(future::fail)
        }
        return future
    }
}