package com.genwow.camera.ui.auth

import com.genwow.camera.data.demo.DemoAuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `blank login cannot submit and exposes field errors`() {
        val viewModel = AuthViewModel(DemoAuthRepository())

        assertFalse(viewModel.uiState.value.canSubmitLogin)
        viewModel.submitLogin()

        assertEquals("请输入手机号或邮箱", viewModel.uiState.value.accountError)
        assertEquals("请输入密码", viewModel.uiState.value.passwordError)
    }

    @Test
    fun `valid demo login emits navigation without retaining credentials`() =
        runTest(mainDispatcherRule.dispatcher) {
        val repository = DemoAuthRepository()
        val viewModel = AuthViewModel(repository)
        viewModel.updateAccount("demo@genwow.local")
        viewModel.updatePassword("local-only")
        val effect = async { viewModel.effects.first() }

        viewModel.submitLogin()

        assertEquals(AuthEffect.NavigateToMain, effect.await())
        assertNotNull(repository.currentSession.value)
        assertEquals("", viewModel.uiState.value.password)
    }

    @Test
    fun `register requires all four fields`() {
        val viewModel = AuthViewModel(DemoAuthRepository())
        viewModel.updateUserName("帧好用户")
        viewModel.updatePassword("12345678")
        viewModel.updateAccount("13800000000")

        assertFalse(viewModel.uiState.value.canSubmitRegistration)
        viewModel.submitRegistration()

        assertEquals("请输入验证码", viewModel.uiState.value.verificationCodeError)
    }

    @Test
    fun `valid registration emits navigation and clears sensitive fields`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = DemoAuthRepository()
            val viewModel = AuthViewModel(repository)
            viewModel.updateUserName("帧好用户")
            viewModel.updatePassword("local-only")
            viewModel.updateAccount("13800000000")
            viewModel.updateVerificationCode("123456")
            val effect = async { viewModel.effects.first() }

            viewModel.submitRegistration()

            assertEquals(AuthEffect.NavigateToMain, effect.await())
            assertEquals("帧好用户", repository.currentSession.value?.displayName)
            assertEquals("", viewModel.uiState.value.password)
            assertEquals("", viewModel.uiState.value.verificationCode)
        }

    @Test
    fun `verification countdown is local finite and restart safe`() =
        runTest(mainDispatcherRule.dispatcher) {
        val viewModel = AuthViewModel(DemoAuthRepository())
        viewModel.updateAccount("13800000000")

        viewModel.sendVerificationCode()
        assertEquals(60, viewModel.uiState.value.verificationSecondsRemaining)
        assertTrue(viewModel.uiState.value.verificationCodeSent)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(59, viewModel.uiState.value.verificationSecondsRemaining)

        viewModel.sendVerificationCode()
        assertEquals(59, viewModel.uiState.value.verificationSecondsRemaining)
    }
}
