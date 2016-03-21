package org.jetbrains.ktor.routing

import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.util.*

/**
 * Represents an application call being handled by [Routing]
 */
open class RoutingApplicationCall(call: ApplicationCall,
                                  val route: RoutingEntry,
                                  private val resolvedValues: ValuesMap) : ApplicationCall by call {
    override val parameters: ValuesMap by lazy {
        ValuesMap.build {
            appendAll(resolvedValues)
            appendAll(request.parameters)
        }
    }
}