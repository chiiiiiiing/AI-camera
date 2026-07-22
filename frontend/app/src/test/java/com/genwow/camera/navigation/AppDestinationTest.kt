package com.genwow.camera.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationTest {
    @Test
    fun `routes are unique and main destinations are stable`() {
        val routes = AppDestination.entries.map(AppDestination::route)

        assertEquals(routes.size, routes.distinct().size)
        assertEquals(
            listOf(AppDestination.Camera, AppDestination.Feed, AppDestination.Profile),
            AppDestination.mainDestinations,
        )
        assertTrue(AppDestination.Login !in AppDestination.mainDestinations)
    }
}
