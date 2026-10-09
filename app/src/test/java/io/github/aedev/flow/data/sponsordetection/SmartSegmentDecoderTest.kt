package io.github.aedev.flow.data.sponsordetection

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.SponsorBlockSegment
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class SmartSegmentDecoderTest {
    @Test
    fun `decodes a sponsor-only BILOU span`() {
        val logits = logits(3)
        logits[0] = tag(1)
        logits[1] = tag(2)
        logits[2] = tag(3)

        val decoded = decodeSponsorBilou(logits, listOf(0 until 6, 6 until 12, 12 until 18))

        assertThat(decoded).hasSize(1)
        assertThat(decoded.single().category).isEqualTo("sponsor")
        assertThat(decoded.single().startCodePoint).isEqualTo(0)
        assertThat(decoded.single().endCodePoint).isEqualTo(18)
    }

    @Test
    fun `stitches overlapping windows into one sponsor span`() {
        val transcript = AssembledSponsorTranscript("x".repeat(100), listOf(CueRange(0, 100, 0, 10_000)))
        val spans =
            listOf(
                WindowSponsorSpan(0, 10, 22, 0.91),
                WindowSponsorSpan(1, 10, 22, 0.88),
                WindowSponsorSpan(0, 70, 90, 0.92),
            )

        val stitched = stitchSponsorSpans(transcript, spans)

        assertThat(stitched).hasSize(2)
        assertThat(stitched.map { it.category }).containsExactly("sponsor", "sponsor")
        assertThat(stitched[0].confidence).isEqualTo(0.91)
    }

    @Test
    fun `legacy prediction payload defaults category to sponsor`() {
        val oldPayload = """{"span_id":"legacy","start_ms":100,"end_ms":200,"confidence":0.9}"""

        val decoded = Json.decodeFromString<SponsorPredictedSpan>(oldPayload)

        assertThat(decoded.category).isEqualTo("sponsor")
        assertThat(decoded.asSponsorBlockSegment("v").category).isEqualTo("sponsor")
    }

    @Test
    fun `predicted category reaches playback segment and feedback span`() {
        val prediction = SponsorPredictedSpan("p", 100, 500, 0.9, "interaction")

        assertThat(prediction.asSponsorBlockSegment("v").category).isEqualTo("interaction")
        val serializedFeedbackSpan = Json.encodeToString(SponsorSpan(100, 500, prediction.category))

        assertThat(Json.decodeFromString<SponsorSpan>(serializedFeedbackSpan).category).isEqualTo("interaction")
    }

    @Test
    fun `normalization is stable when repeated`() {
        val normalized = normalizeSponsorCue("Visit https://example.com and save 12 today")
        assertThat(normalizeSponsorCue(normalized)).isEqualTo(normalized)
        assertThat(normalized).contains("url_token")
        assertThat(normalized).contains("number_token")
    }

    @Test
    fun `overlapping windows merge and a later event stays separate`() {
        val transcript = AssembledSponsorTranscript("x".repeat(100), listOf(CueRange(0, 100, 0, 10_000)))
        val spans =
            listOf(
                WindowSponsorSpan(0, 0, 11, 0.9),
                WindowSponsorSpan(1, 8, 20, 0.9),
                WindowSponsorSpan(0, 50, 61, 0.9),
            )

        val stitched = stitchSponsorSpans(transcript, spans)

        assertThat(stitched).hasSize(2)
        assertThat(stitched.map { it.category }).containsExactly("sponsor", "sponsor")
    }

    @Test
    fun `API comparison cannot match an overlapping different category`() {
        val predictions =
            listOf(
                SponsorPredictedSpan("s", 100, 500, 0.9, "sponsor"),
                SponsorPredictedSpan("i", 100, 500, 0.9, "interaction"),
            )
        val segments = listOf(SponsorBlockSegment(category = "interaction", segment = listOf(0.1f, 0.5f), uuid = "api-i"))

        val (api, comparison) = compareSponsorSpans(predictions, segments)

        assertThat(api.single().category).isEqualTo("interaction")
        assertThat(comparison.matches.single().predictionId).isEqualTo("i")
    }

    @Test
    fun `nearby sponsor windows merge across the calibrated gap`() {
        val transcript = AssembledSponsorTranscript("x".repeat(200), listOf(CueRange(0, 200, 0, 10_000)))
        val spans =
            listOf(
                WindowSponsorSpan(0, 0, 40, 0.97),
                WindowSponsorSpan(1, 50, 90, 0.95),
            )

        val stitched = stitchSponsorSpans(transcript, spans)

        assertThat(stitched).hasSize(1)
        assertThat(stitched.single().category).isEqualTo("sponsor")
        assertThat(stitched.single().startMs).isEqualTo(0)
    }

    @Test
    fun `a large gap stays two sponsor spans`() {
        val transcript = AssembledSponsorTranscript("x".repeat(200), listOf(CueRange(0, 200, 0, 200_000)))
        val spans =
            listOf(
                WindowSponsorSpan(0, 0, 40, 0.97),
                WindowSponsorSpan(1, 160, 200, 0.95),
            )

        assertThat(stitchSponsorSpans(transcript, spans)).hasSize(2)
    }

    private fun logits(sequenceSize: Int): List<FloatArray> = List(sequenceSize) { tag(0) }

    private fun tag(tag: Int): FloatArray = FloatArray(5) { if (it == tag) 4f else 0f }
}
