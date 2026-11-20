package example.reservation.conformance

import java.io.File

internal val moduleDirectories: Map<String, File> = checkNotNull(System.getProperty("dca.modules")).split("|")
    .associate { entry -> entry.substringBefore("=") to File(entry.substringAfter("=")) }

internal val policyFile = File(checkNotNull(System.getProperty("dca.policy")))
