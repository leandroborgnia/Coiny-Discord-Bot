package bot.application.skipjar;

import bot.domain.queue.AnnouncementView;
import java.util.Optional;

/**
 * Outcome of a skip-jar contribution.
 *
 * @param charged true when a coin was debited (false only on idempotent replay)
 * @param count the jar count for the current run after this contribution
 * @param threshold the threshold at evaluation time
 * @param remaining {@code max(0, threshold - count)}
 * @param skipped true iff this contribution triggered the early skip
 * @param gameName the retired/current game's display name (from the queue slot)
 * @param newGameName the new current game's display name after a skip; null when {@code !skipped}
 *     or the new run is empty (so the skip reply always has {@code {newGame}} even with no
 *     announcement channel — F1)
 * @param announcement the rotation announcement payload: present iff skipped AND a channel is
 *     configured
 */
public record ContributeResult(
    boolean charged,
    int count,
    int threshold,
    int remaining,
    boolean skipped,
    String gameName,
    String newGameName,
    Optional<AnnouncementView> announcement) {}
