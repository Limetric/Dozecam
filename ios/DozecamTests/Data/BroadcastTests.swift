import Testing

@testable import Dozecam

struct BroadcastTests {
    @Test func aStreamStartsWithTheCurrentValue() async {
        let broadcast = Broadcast(1)
        broadcast.send(2)
        var values = broadcast.stream().makeAsyncIterator()
        #expect(await values.next() == 2)
        broadcast.send(3)
        #expect(await values.next() == 3)
    }

    @Test func anEventStreamCarriesOnlyLaterSends() async {
        let broadcast = Broadcast(1)
        var values = broadcast.stream(replayingCurrent: false).makeAsyncIterator()
        broadcast.send(2)
        #expect(await values.next() == 2)
    }

    @Test func aSlowReaderGetsTheNewestValue() async {
        let broadcast = Broadcast(0)
        var values = broadcast.stream().makeAsyncIterator()
        for value in 1...5 { broadcast.send(value) }
        #expect(await values.next() == 5)
    }

    @Test func sendIfChangedSkipsAnEqualValue() async {
        let broadcast = Broadcast(1)
        var values = broadcast.stream(replayingCurrent: false).makeAsyncIterator()
        broadcast.sendIfChanged(1)
        broadcast.sendIfChanged(2)
        #expect(await values.next() == 2)
    }

    @Test func aStreamThatEndsUnsubscribes() async {
        let broadcast = Broadcast(1)
        let reader = Task {
            for await _ in broadcast.stream() {}
        }
        while broadcast.subscriberCount == 0 { await Task.yield() }
        reader.cancel()
        await reader.value
        #expect(broadcast.subscriberCount == 0)
    }

    @Test func releasingTheBroadcastEndsItsStreams() async {
        var broadcast: Broadcast<Int>? = Broadcast(1)
        let stream = broadcast!.stream()
        broadcast = nil
        #expect(await take(2, from: stream) == [1])
    }
}
