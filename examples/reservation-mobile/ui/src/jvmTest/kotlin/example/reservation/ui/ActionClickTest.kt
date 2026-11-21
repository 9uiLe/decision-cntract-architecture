@file:OptIn(ExperimentalTestApi::class)

package example.reservation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.v2.runComposeUiTest
import example.reservation.core.CreateObservation
import example.reservation.core.LookupObservation
import example.reservation.core.Rejection
import example.reservation.core.RequestId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain

private typealias StatusComponent = @Composable (StatusState, ReservationViewModel) -> Unit

private val productComponent: StatusComponent = { status, endpoint ->
    ReservationStatusScreen(status, endpoint, {}, {}, {}, {}, {})
}

// 以下の部品は、製品の画面が避けている書き方の再現。同じ操作をクリックして、失敗が画面経由で観測されることを確かめる。
@Composable
private fun CachedButton(card: RequestCard, action: RequestAction?) {
    val (heading, label, tag) = when (card) {
        is RequestCard.Unknown -> Triple("結果不明", "状況を照会する", "lookup-button")
        is RequestCard.NotCreated -> Triple("未成立が確定", "再試行する", "retry-button")
        else -> return
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(heading, modifier = Modifier.testTag("request-cached"))
        Text("開催回と参加者、予約の状況を表示する欄。\n".repeat(CACHED_FILLER_LINES))
        Button(onClick = { action?.execute() }, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(label) }
    }
}

private fun RequestCard.actionOrNull(endpoint: ReservationViewModel): RequestAction? = when (this) {
    is RequestCard.Unknown -> boundTo(endpoint)
    is RequestCard.NotCreated -> boundTo(endpoint)
    else -> null
}

private fun ticketOf(card: RequestCard): Any? = when (card) {
    is RequestCard.Unknown -> card.check
    is RequestCard.NotCreated -> card.retry
    else -> null
}

private val cachedByDisplay: StatusComponent = { status, endpoint ->
    val card = checkNotNull(status.request)
    CachedButton(card, remember(card.summary) { card.actionOrNull(endpoint) })
}

private val cachedByTicket: StatusComponent = { status, endpoint ->
    val card = checkNotNull(status.request)
    CachedButton(card, remember(ticketOf(card)) { card.actionOrNull(endpoint) })
}

private data class Shown(val status: StatusState, val endpoint: ReservationViewModel)

private class Host(val shown: MutableState<Shown>, private val dedupeByDisplay: Boolean) {
    // 表示の等価性だけで更新を落とす状態の流れの再現。製品の ViewModel はこれをしない。
    fun show(next: Shown) {
        if (dedupeByDisplay && shown.value.status.request?.summary == next.status.request?.summary &&
            shown.value.status.request?.javaClass == next.status.request?.javaClass &&
            shown.value.endpoint === next.endpoint
        ) {
            return
        }
        shown.value = next
    }
}

// 同じ画面の中で維持されるはずの状態（ホストの remember とスクロール位置）を、更新の前後で比べるための枠。
private fun ComposeUiTest.host(component: StatusComponent, first: Shown, dedupeByDisplay: Boolean = false): Host {
    val shown = mutableStateOf(first)
    setContent {
        var hostCount by remember { mutableIntStateOf(0) }
        Column {
            Button(onClick = { hostCount += 1 }, modifier = Modifier.testTag("host-count")) { Text("host=$hostCount") }
            Box(Modifier.height(HOST_HEIGHT.dp)) { shown.value.let { component(it.status, it.endpoint) } }
        }
    }
    waitForIdle()
    return Host(shown, dedupeByDisplay)
}

private const val HOST_HEIGHT = 140
private const val CACHED_FILLER_LINES = 6

private enum class Input { Mouse, Touch }

private fun ComposeUiTest.click(tag: String, input: Input = Input.Mouse, settle: () -> Unit) {
    onNodeWithTag(tag).performScrollTo()
    when (input) {
        Input.Mouse -> onNodeWithTag(tag).performMouseInput { click() }
        Input.Touch -> onNodeWithTag(tag).performTouchInput { click() }
    }
    waitForIdle()
    settle()
}

private class Kept(val hostLabel: String, val cardTop: Float)

// 更新の前に、ホストの状態を一つ進め、カードまでスクロールしておく。
private fun ComposeUiTest.keepState(cardTag: String, buttonTag: String): Kept {
    onNodeWithTag("host-count").performMouseInput { click() }
    onNodeWithTag(buttonTag).performScrollTo()
    waitForIdle()
    onNodeWithText("host=1").assertIsDisplayed()
    return Kept("host=1", onNodeWithTag(cardTag).getBoundsInRoot().top.value)
}

private fun ComposeUiTest.assertKept(kept: Kept, cardTag: String) {
    onNodeWithText(kept.hostLabel).assertIsDisplayed()
    assertEquals(kept.cardTop, onNodeWithTag(cardTag).getBoundsInRoot().top.value, "スクロール位置を維持")
}

private fun ComposeUiTest.refresh(host: Host, next: Shown, card: String, button: String, heading: String, label: String) {
    val kept = keepState(card, button)
    host.show(next)
    waitForIdle()
    assertShowsLabels(heading, label)
    assertKept(kept, card)
}

private fun ComposeUiTest.assertShowsLabels(heading: String, button: String) {
    onNodeWithText(heading).assertExists()
    onNodeWithText(button).assertExists()
}

private val staleMessage = rejectionMessage(Rejection.Stale)
private val closedMessage = rejectionMessage(Rejection.RegistrationClosed)

class ActionClickTest {
    private class SameDestinationNewTicket(val s: ActionSession, val endpoint: ReservationViewModel, val first: Shown, val second: Shown)

    private fun sameDestinationNewTicket(scope: TestScope): SameDestinationNewTicket {
        val s = ActionSession(scope)
        val endpoint = s.endpoint()
        s.submit(endpoint, CreateObservation.NoResponse)
        val first = Shown(s.status, endpoint)
        s.lookUp(endpoint, LookupObservation.Failed)
        val second = Shown(s.status, endpoint)
        assertEquals(first.status.request?.summary, second.status.request?.summary)
        assertNotEquals(ticketOf(first.status.request!!), ticketOf(second.status.request!!))
        assertEquals(listOf(RequestId("R1")), s.port.lookupCalls)
        return SameDestinationNewTicket(s, endpoint, first, second)
    }

    @Test
    fun `同じ表示で券だけが替わっても、更新後の照会は新しい券で同じ要求へ届く`() = runComposeUiTest {
        val t = sameDestinationNewTicket(TestScope())
        val host = host(productComponent, t.first)
        val before = onNodeWithTag("request-unknown").fetchSemanticsNode().id
        refresh(host, t.second, "request-unknown", "lookup-button", "結果不明", "状況を照会する")
        assertEquals(before, onNodeWithTag("request-unknown").fetchSemanticsNode().id, "券で画面の identity を変えない")

        click("lookup-button") { t.s.settle() }
        assertEquals(listOf(RequestId("R1"), RequestId("R1")), t.s.port.lookupCalls)
        assertNull(t.endpoint.notice.value)
    }

    @Test
    fun `表示だけをキーにした操作の保持は、券が替わった後の照会を拒否される`() = runComposeUiTest {
        val t = sameDestinationNewTicket(TestScope())
        val host = host(cachedByDisplay, t.first)
        refresh(host, t.second, "request-cached", "lookup-button", "結果不明", "状況を照会する")

        click("lookup-button") { t.s.settle() }
        assertEquals(listOf(RequestId("R1")), t.s.port.lookupCalls)
        assertEquals(staleMessage, t.endpoint.notice.value?.message)
    }

    @Test
    fun `表示の等価性で更新を落とす流れは、券が替わった後の照会を拒否される`() = runComposeUiTest {
        val t = sameDestinationNewTicket(TestScope())
        val host = host(productComponent, t.first, dedupeByDisplay = true)
        refresh(host, t.second, "request-unknown", "lookup-button", "結果不明", "状況を照会する")

        click("lookup-button") { t.s.settle() }
        assertEquals(listOf(RequestId("R1")), t.s.port.lookupCalls)
        assertEquals(staleMessage, t.endpoint.notice.value?.message)
    }

    private class NewRequest(val s: ActionSession, val endpoint: ReservationViewModel, val first: Shown, val second: Shown)

    private fun newRequest(scope: TestScope): NewRequest {
        val s = ActionSession(scope)
        val endpoint = s.endpoint()
        s.submit(endpoint, CreateObservation.NoResponse)
        val first = Shown(s.status, endpoint)
        s.lookUp(endpoint, LookupObservation.NotCreated)
        s.retry(endpoint, CreateObservation.NoResponse)
        val second = Shown(s.status, endpoint)
        assertEquals(first.status.request?.summary, second.status.request?.summary)
        assertIs<RequestCard.Unknown>(first.status.request)
        assertIs<RequestCard.Unknown>(second.status.request)
        assertEquals(listOf(RequestId("R1")), s.port.lookupCalls)
        assertEquals(listOf(RequestId("R1"), RequestId("R2")), s.port.createCalls)
        return NewRequest(s, endpoint, first, second)
    }

    @Test
    fun `同じ結果不明の表示でも、再試行で替わった後の照会は新しい要求へ届く`() = runComposeUiTest {
        val t = newRequest(TestScope())
        val host = host(productComponent, t.first)
        refresh(host, t.second, "request-unknown", "lookup-button", "結果不明", "状況を照会する")

        click("lookup-button") { t.s.settle() }
        assertEquals(listOf(RequestId("R1"), RequestId("R2")), t.s.port.lookupCalls)
        assertNull(t.endpoint.notice.value)
    }

    @Test
    fun `表示だけをキーにした操作の保持は、新しい要求ではなく古い要求の券を使って拒否される`() = runComposeUiTest {
        val t = newRequest(TestScope())
        val host = host(cachedByDisplay, t.first)
        refresh(host, t.second, "request-cached", "lookup-button", "結果不明", "状況を照会する")

        click("lookup-button") { t.s.settle() }
        assertEquals(listOf(RequestId("R1")), t.s.port.lookupCalls)
        assertEquals(staleMessage, t.endpoint.notice.value?.message)
    }

    private class NewEndpoint(
        val s: ActionSession,
        val old: ReservationViewModel,
        val renewed: ReservationViewModel,
        val shown: StatusState,
    )

    private fun newEndpoint(scope: TestScope): NewEndpoint {
        val s = ActionSession(scope)
        val old = s.endpoint()
        s.submit(old, CreateObservation.NotCreated)
        s.now = ACTION_START + 1
        val renewed = s.endpoint()
        assertNotSame(old, renewed)
        return NewEndpoint(s, old, renewed, s.status)
    }

    @Test
    fun `同じ券でも実行先が替わったら、更新後の再試行は新しい実行先へ届く`() = runComposeUiTest {
        val t = newEndpoint(TestScope())
        val host = host(productComponent, Shown(t.shown, t.old))
        refresh(host, Shown(t.shown, t.renewed), "request-not-created", "retry-button", "未成立が確定", "再試行する")

        click("retry-button", Input.Touch) { t.s.settle() }
        assertEquals(closedMessage, t.renewed.notice.value?.message)
        assertNull(t.old.notice.value)
        assertEquals(1, t.s.port.createCalls.size)
    }

    @Test
    fun `券だけをキーにした操作の保持は、実行先が替わった後も古い実行先へ届く`() = runComposeUiTest {
        val t = newEndpoint(TestScope())
        val host = host(cachedByTicket, Shown(t.shown, t.old))
        refresh(host, Shown(t.shown, t.renewed), "request-cached", "retry-button", "未成立が確定", "再試行する")

        click("retry-button") { t.s.settle() }
        assertEquals(closedMessage, t.old.notice.value?.message)
        assertNull(t.renewed.notice.value)
    }

}

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelStateClickTest {
    private val mainScheduler = TestScope().testScheduler

    @BeforeTest
    fun useControlledMain() = Dispatchers.setMain(StandardTestDispatcher(mainScheduler))

    @AfterTest
    fun restoreMain() = Dispatchers.resetMain()

    @Test
    fun `ViewModel の状態の流れが中間の表示なしで同じ表示の新しい券へ進んでも、次の照会は新しい券で届く`() = runComposeUiTest {
        val s = ActionSession(TestScope())
        val endpoint = s.endpoint()
        s.submit(endpoint, CreateObservation.NoResponse)
        val first = s.request
        assertIs<RequestCard.Unknown>(first)
        val seen = mutableListOf<String>()
        setContent {
            val state by endpoint.state.collectAsState()
            state.status.request?.let { card -> SideEffect { seen += card::class.simpleName.orEmpty() } }
            ReservationStatusScreen(state.status, endpoint, {}, {}, {}, {}, {})
        }
        mainScheduler.runCurrent()
        waitForIdle()
        assertEquals(listOf("Unknown"), seen)

        s.port.lookupScript += LookupObservation.Failed
        endpoint.checkStatus(first.check)
        s.settle()
        mainScheduler.runCurrent()
        waitForIdle()
        assertEquals(listOf("Unknown", "Unknown"), seen, "照会中の表示を挟まず、結果不明から結果不明へ進む")
        assertNotSame(first.check, (s.request as RequestCard.Unknown).check)

        click("lookup-button") { s.settle() }
        assertEquals(listOf(RequestId("R1"), RequestId("R1")), s.port.lookupCalls)
        assertNull(endpoint.notice.value)
    }
}
