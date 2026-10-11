package io.gropp.pawparazzi.camera

import com.google.common.truth.Truth.assertThat
import io.gropp.pawparazzi.actions.PhotoNaming
import io.gropp.pawparazzi.core.detection.Label
import io.gropp.pawparazzi.core.detection.Trigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureQueueTest {
    private val log = mutableListOf<String>()
    private val events = mutableListOf<CaptureEvent>()
    private var freeBytes = 1_000L
    private var minFreeBytes = 100L
    private var capBytes = 5_000L
    private var wallMs = 1_700_000_000_000L
    private var takeResult: (String) -> Boolean = { true }

    private val storage =
        object : StorageGuard {
            override fun freeBytes(): Long = freeBytes

            override suspend fun enforceCap(capBytes: Long) {
                log += "cap:$capBytes"
            }
        }
    private val photoTaker = PhotoTaker { name ->
        log += "take:$name"
        takeResult(name)
    }

    private fun TestScope.queue() =
        CaptureQueue(
            scope = this,
            storage = storage,
            photoTaker = photoTaker,
            minFreeBytes = { minFreeBytes },
            capBytes = { capBytes },
            nowWallMs = { wallMs },
            onEvent = { events += it },
        )

    private fun request(trackId: Long, label: Label = Label.CAT) =
        CaptureRequest(listOf(Trigger(trackId, label)))

    private fun nameFor(request: CaptureRequest) = PhotoNaming.displayName(request.triggers, wallMs)

    @Test
    fun lowStorageEmitsEventAndTakesNoPhoto() = runTest {
        freeBytes = 99
        val queue = queue()

        queue.enqueue(request(1))
        advanceUntilIdle()

        assertThat(events).containsExactly(CaptureEvent.LowStorage(99L))
        assertThat(log).isEmpty()
        queue.close()
    }

    @Test
    fun freeSpaceEqualToMinimumIsOk() = runTest {
        freeBytes = 100
        val queue = queue()

        queue.enqueue(request(1))
        advanceUntilIdle()

        assertThat(events.first()).isEqualTo(CaptureEvent.StorageOk)
        queue.close()
    }

    @Test
    fun storageOkIsEmittedBeforeCapturing() = runTest {
        val order = mutableListOf<String>()
        val queue =
            CaptureQueue(
                scope = this,
                storage = storage,
                photoTaker =
                    PhotoTaker {
                        order += "take"
                        true
                    },
                minFreeBytes = { minFreeBytes },
                capBytes = { capBytes },
                nowWallMs = { wallMs },
                onEvent = { order += it.toString() },
            )

        queue.enqueue(request(1))
        advanceUntilIdle()

        assertThat(order.take(2)).containsExactly("StorageOk", "take").inOrder()
        queue.close()
    }

    @Test
    fun successfulSaveEmitsSavedWithPhotoNamingNameThenEnforcesCurrentCap() = runTest {
        val request = request(3, Label.DOG)
        val queue = queue()
        capBytes = 7_777

        queue.enqueue(request)
        advanceUntilIdle()

        val name = nameFor(request)
        assertThat(events)
            .containsExactly(CaptureEvent.StorageOk, CaptureEvent.Saved(name))
            .inOrder()
        assertThat(log).containsExactly("take:$name", "cap:7777").inOrder()
        queue.close()
    }

    @Test
    fun failedSaveEmitsNoSavedAndSkipsCapEnforcement() = runTest {
        takeResult = { false }
        val queue = queue()

        queue.enqueue(request(1))
        advanceUntilIdle()

        assertThat(events).containsExactly(CaptureEvent.StorageOk)
        assertThat(log.filter { it.startsWith("cap:") }).isEmpty()
        queue.close()
    }

    @Test
    fun requestsAreProcessedInOrder() = runTest {
        val queue = queue()
        val requests = listOf(request(1), request(2), request(3))

        requests.forEach(queue::enqueue)
        advanceUntilIdle()

        val taken = log.filter { it.startsWith("take:") }
        assertThat(taken)
            .containsExactlyElementsIn(requests.map { "take:" + nameFor(it) })
            .inOrder()
        queue.close()
    }

    @Test
    fun fifthRequestWhileFourAreQueuedIsDropped() = runTest {
        val queue = queue()
        val requests = (1L..5L).map { request(it) }

        requests.forEach(queue::enqueue)
        advanceUntilIdle()

        val taken = log.filter { it.startsWith("take:") }
        assertThat(taken)
            .containsExactlyElementsIn(requests.take(4).map { "take:" + nameFor(it) })
            .inOrder()
        queue.close()
    }

    @Test
    fun exceptionInOneRequestDoesNotStopLaterOnes() = runTest {
        val failing = request(1)
        val following = request(2)
        takeResult = { name -> if (name == nameFor(failing)) error("boom") else true }
        val queue = queue()

        queue.enqueue(failing)
        queue.enqueue(following)
        advanceUntilIdle()

        assertThat(events.filterIsInstance<CaptureEvent.Saved>())
            .containsExactly(CaptureEvent.Saved(nameFor(following)))
        queue.close()
    }

    @Test
    fun closeStopsTheLoopAndLaterRequestsAreIgnored() = runTest {
        val queue = queue()
        queue.enqueue(request(1))
        advanceUntilIdle()
        log.clear()
        events.clear()

        queue.close()
        queue.enqueue(request(2))
        advanceUntilIdle()

        assertThat(log).isEmpty()
        assertThat(events).isEmpty()
    }
}
