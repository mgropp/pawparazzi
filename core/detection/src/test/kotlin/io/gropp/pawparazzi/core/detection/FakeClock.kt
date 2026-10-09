package io.gropp.pawparazzi.core.detection

class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}

fun box(cx: Float, cy: Float, w: Float = 0.2f, h: Float = 0.2f) =
    NormBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

fun det(label: Label = Label.CAT, score: Float = 0.9f, box: NormBox = box(0.5f, 0.5f)) = Detection(label, score, box)
