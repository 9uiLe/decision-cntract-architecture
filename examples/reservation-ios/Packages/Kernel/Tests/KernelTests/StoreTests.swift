import Kernel
import KernelTestSupport
import Testing

@Suite(.timeLimit(.minutes(1)))
struct StoreTests {
    @Test func 購読は現在の値から始まり以後の更新を順に受け取る() async {
        let store = Store<Int, Int>(1) { $0 }
        var iterator = store.updates().makeAsyncIterator()
        #expect(await iterator.next() == 1)
        store.mutate { $0 = 2 }
        #expect(await iterator.next() == 2)
        store.mutate { $0 = 3 }
        #expect(await iterator.next() == 3)
    }

    @Test func 途中から購読した側は購読時点の最新値から始まる() async {
        let store = Store<Int, Int>(1) { $0 }
        store.mutate { $0 = 5 }
        var iterator = store.updates().makeAsyncIterator()
        #expect(await iterator.next() == 5)
    }
}

@Suite(.timeLimit(.minutes(1)))
struct OperationTimeoutTests {
    @Test func 時間切れの前に終わった操作は結果を返す() async {
        let timeout = ManualTimeout()
        let result = await timeout.run { 7 }
        #expect(result == 7)
    }

    @Test func 手動で時間切れにすると応答待ちの操作は結果なしになる() async {
        let timeout = ManualTimeout()
        let gate = Gate()
        async let result: Int? = timeout.run {
            await gate.wait()
            return 1
        }
        await gate.waitUntilWaiting()
        timeout.expire()
        #expect(await result == nil)
    }
}

private final class Gate: Sendable {
    private let stream: AsyncStream<Void>
    private let continuation: AsyncStream<Void>.Continuation
    private let waiting: AsyncStream<Void>
    private let signalWaiting: AsyncStream<Void>.Continuation

    init() {
        (stream, continuation) = AsyncStream.makeStream(of: Void.self)
        (waiting, signalWaiting) = AsyncStream.makeStream(of: Void.self)
    }

    func wait() async {
        signalWaiting.yield()
        for await _ in stream { return }
    }

    func waitUntilWaiting() async {
        for await _ in waiting { return }
    }
}
