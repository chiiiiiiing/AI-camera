package com.genwow.camera.data.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRepositoryTest {
    @Test
    fun `demo login rejects blank input and never stores credentials`() {
        val repository = DemoAuthRepository()

        assertNull(repository.login("", ""))
        val session = repository.login("demo@genwow.local", "not-persisted")

        assertEquals("本地演示用户", session?.displayName)
        assertSame(session, repository.currentSession.value)
        assertTrue(DemoAuthSession::class.java.declaredFields.none { field ->
            field.name.contains("password", ignoreCase = true) ||
                field.name.contains("account", ignoreCase = true)
        })
    }

    @Test
    fun `community content is fixed local demo data`() {
        val repository = DemoCommunityRepository()

        assertEquals(listOf("Helena", "Daniel"), repository.posts.map(DemoPost::author))
        assertEquals(128, repository.profile.works)
        assertEquals(256, repository.profile.followers)
        assertEquals(64, repository.profile.following)
        assertEquals(6, repository.profile.galleryItems.size)
    }

    @Test
    fun `community like state only changes the in memory post`() {
        val repository = DemoCommunityRepository()

        repository.toggleLike("helena-tulips")
        assertEquals(22, repository.posts.first().likes)
        assertTrue(repository.posts.first().liked)

        repository.toggleLike("helena-tulips")
        assertEquals(21, repository.posts.first().likes)
        assertTrue(!repository.posts.first().liked)
    }
}
