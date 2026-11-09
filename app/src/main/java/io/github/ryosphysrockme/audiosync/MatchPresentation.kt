package io.github.ryosphysrockme.audiosync

import java.util.Locale

/**
 * Short, user-visible wording for live matching. These labels are intentionally
 * distinct from the detailed diagnostics and do not claim that a candidate is
 * a successful synchronization.
 */
internal object MatchPresentation {
    fun status(status: MatchConsensus.Status): String = when (status) {
        MatchConsensus.Status.NO_MATCH -> "音源を探索中"
        MatchConsensus.Status.LOW_SIMILARITY -> "一致度が不足"
        MatchConsensus.Status.AMBIGUOUS -> "候補が複数"
        MatchConsensus.Status.WAITING_FOR_CONFIRMATION -> "位置を確認中"
        MatchConsensus.Status.CANDIDATE_CHANGED -> "候補の位置を確認中"
        MatchConsensus.Status.ACCEPTED -> "同期位置を確認"
        MatchConsensus.Status.TRACKING -> "位置を追跡中"
    }

    fun candidate(positionMs: Long, score: Double): String {
        require(positionMs >= 0L)
        val seconds = positionMs / 1000L
        return "候補 %02d:%02d  ·  一致度 %.3f".format(
            Locale.JAPAN, seconds / 60, seconds % 60, score
        )
    }
}
