package bot.domain.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Pure unit tests for the queue ordering arithmetic (append-tail, dense shift-up, bump). */
class QueueOrderingPolicyTest {

  @Test
  void appendGoesToTheTail() {
    assertThat(QueueOrderingPolicy.appendPosition(0)).isEqualTo(1); // first into an empty queue
    assertThat(QueueOrderingPolicy.appendPosition(3)).isEqualTo(4);
  }

  @Test
  void densePositionsAreOneThroughN() {
    assertThat(QueueOrderingPolicy.densePositions(0)).isEmpty();
    assertThat(QueueOrderingPolicy.densePositions(3)).containsExactly(1, 2, 3);
  }

  @Test
  void bumpMovesUpByExactlyOne() {
    assertThat(QueueOrderingPolicy.bumpedPosition(2)).isEqualTo(1);
    assertThat(QueueOrderingPolicy.bumpedPosition(5)).isEqualTo(4);
  }

  @Test
  void bumpingTheTopIsRejected() {
    assertThatThrownBy(() -> QueueOrderingPolicy.bumpedPosition(1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void negativeInputsAreRejected() {
    assertThatThrownBy(() -> QueueOrderingPolicy.appendPosition(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> QueueOrderingPolicy.densePositions(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
