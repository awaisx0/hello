package app.locjournal

import app.locjournal.tracking.DeciderConfig
import app.locjournal.tracking.Decision
import app.locjournal.tracking.Sample
import app.locjournal.tracking.VisitDecider
import app.locjournal.tracking.VisitSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitDeciderTest {
    private val config = DeciderConfig(distanceThresholdMeters = 150, maxAccuracyMeters = 200, filterGlitches = true)
    private val min = 60_000L

    // ~0.001 deg latitude = 111 m
    private val home = 52.0 to 13.0

    private fun visit(
        id: Long,
        lat: Double = home.first,
        lon: Double = home.second,
        start: Long = 0,
        end: Long = start,
        samples: Int = 1,
        acc: Float? = 20f,
        placeId: Long? = null,
        edited: Boolean = false,
    ) = VisitSnapshot(id, lat, lon, acc, start, end, samples, placeId, edited)

    private fun sample(lat: Double, lon: Double = home.second, time: Long, acc: Float? = 20f) = Sample(lat, lon, acc, time)

    @Test
    fun firstSampleStartsVisit() {
        assertEquals(Decision.StartNew, VisitDecider.decide(sample(52.0, time = 0), null, null, null, config))
    }

    @Test
    fun smallDriftExtendsVisit() {
        val current = visit(1, start = 0, end = 15 * min)
        // ~89 m away: within the 150 m threshold
        val d = VisitDecider.decide(sample(52.0008, time = 30 * min), current, null, null, config)
        assertTrue(d is Decision.Extend)
        assertEquals(1L, (d as Decision.Extend).visitId)
    }

    @Test
    fun moveBeyondThresholdStartsNewVisit() {
        val current = visit(1, start = 0, end = 15 * min)
        // ~333 m away
        val d = VisitDecider.decide(sample(52.003, time = 30 * min), current, null, null, config)
        assertEquals(Decision.StartNew, d)
    }

    @Test
    fun inaccurateFixIsIgnored() {
        val current = visit(1, start = 0, end = 15 * min)
        val d = VisitDecider.decide(sample(52.01, time = 30 * min, acc = 900f), current, null, null, config)
        assertTrue(d is Decision.Ignore)
    }

    @Test
    fun poorAccuracyRaisesTheBarForMoving() {
        val current = visit(1, start = 0, end = 15 * min)
        // ~180 m away but the fix itself is only accurate to 190 m: treat as the same place
        val d = VisitDecider.decide(sample(52.0016, time = 30 * min, acc = 190f), current, null, null, config)
        assertTrue(d is Decision.Extend)
    }

    @Test
    fun betterFixRefinesCenter() {
        val current = visit(1, start = 0, end = 15 * min, acc = 50f)
        val d = VisitDecider.decide(sample(52.0002, time = 30 * min, acc = 8f), current, null, null, config) as Decision.Extend
        assertTrue(d.refineCenter)
        val worse = VisitDecider.decide(sample(52.0002, time = 30 * min, acc = 80f), current, null, null, config) as Decision.Extend
        assertTrue(!worse.refineCenter)
    }

    @Test
    fun userEditedVisitIsNotRefined() {
        val current = visit(1, start = 0, end = 15 * min, acc = 50f, edited = true)
        val d = VisitDecider.decide(sample(52.0002, time = 30 * min, acc = 8f), current, null, null, config) as Decision.Extend
        assertTrue(!d.refineCenter)
    }

    @Test
    fun oneOffJumpThatReturnsIsReverted() {
        val previous = visit(1, start = 0, end = 60 * min, samples = 5)
        val glitch = visit(2, lat = 52.01, start = 75 * min, end = 75 * min, samples = 1)
        val d = VisitDecider.decide(sample(52.0001, time = 90 * min), glitch, previous, null, config)
        assertEquals(Decision.RevertGlitch(glitchVisitId = 2, previousVisitId = 1), d)
    }

    @Test
    fun glitchFilterOffKeepsShortStop() {
        val previous = visit(1, start = 0, end = 60 * min, samples = 5)
        val stop = visit(2, lat = 52.01, start = 75 * min, end = 75 * min, samples = 1)
        val d = VisitDecider.decide(sample(52.0001, time = 90 * min), stop, previous, null, config.copy(filterGlitches = false))
        assertEquals(Decision.StartNew, d)
    }

    @Test
    fun stopWithSeveralSamplesIsNotAGlitch() {
        val previous = visit(1, start = 0, end = 60 * min, samples = 5)
        val stop = visit(2, lat = 52.01, start = 75 * min, end = 90 * min, samples = 2)
        val d = VisitDecider.decide(sample(52.0001, time = 105 * min), stop, previous, null, config)
        assertEquals(Decision.StartNew, d)
    }

    @Test
    fun savedPlaceMatchOverridesDistance() {
        // Big campus: 400 m apart, but both inside saved place 7
        val current = visit(1, start = 0, end = 15 * min, placeId = 7)
        val same = VisitDecider.decide(sample(52.0036, time = 30 * min), current, null, 7L, config)
        assertTrue(same is Decision.Extend)
        // Next door but inside a different saved place: new visit
        val other = VisitDecider.decide(sample(52.0005, time = 30 * min), current, null, 8L, config)
        assertEquals(Decision.StartNew, other)
    }

    @Test
    fun longGapStartsNewVisit() {
        val current = visit(1, start = 0, end = 15 * min)
        val d = VisitDecider.decide(sample(52.0, time = 15 * min + VisitDecider.MAX_GAP_MS + 1), current, null, null, config)
        assertEquals(Decision.StartNew, d)
    }

    @Test
    fun outOfOrderSampleIsIgnored() {
        val current = visit(1, start = 0, end = 30 * min)
        val d = VisitDecider.decide(sample(52.0, time = 20 * min), current, null, null, config)
        assertTrue(d is Decision.Ignore)
    }
}
