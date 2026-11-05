package sk.spectrum.analyzer2

/** Sound identification results, session/profile models and constants. */
internal data class SoundSessionEntry(
    val label: String,
    val seconds: Double,
    val percent: Double
)

internal data class SoundCandidate(val label: String, val scorePercent: Int)

internal data class EfficientAtPrediction(
    val label: String,
    val scorePercent: Int,
    val yamnetClassIndex: Int
)

internal data class SoundSessionSummary(
    val totalSeconds: Double,
    val entries: List<SoundSessionEntry>,
    val timeline: List<String> = emptyList()
)

internal val SOUND_SESSION_COLORS = intArrayOf(
    0xff4bc4ff.toInt(), 0xff50dcb0.toInt(), 0xffffbe56.toInt(),
    0xffb78bff.toInt(), 0xffff7c91.toInt()
)

internal val SOUND_SESSION_OTHER_COLOR = 0xff889eb5.toInt()

internal data class SoundIdResult(
    val status: String,
    val bestSound: String,
    val modelScorePercent: Int = 0,
    val alternatives: String = "",
    val sessionSummary: SoundSessionSummary? = null,
    val isPersonalMatch: Boolean = false
)

internal const val PERSONAL_SOUND_FEATURE_COUNT = 585

internal const val PERSONAL_SOUND_SAMPLE_SECONDS = 10

internal const val PERSONAL_SOUND_MATCH_THRESHOLD = 0.72f

internal const val PERSONAL_SOUND_NOTE_THRESHOLD = 0.55f

internal data class PersonalSoundProfile(val id: String, val name: String, val embedding: FloatArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PersonalSoundProfile &&
            id == other.id &&
            name == other.name &&
            embedding.contentEquals(other.embedding))

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
