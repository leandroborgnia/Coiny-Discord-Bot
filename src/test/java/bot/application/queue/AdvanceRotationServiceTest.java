package bot.application.queue;

import static org.assertj.core.api.Assertions.assertThat;

import bot.support.AbstractPostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exercises the weekly rotation against real Postgres: pop + shift + designate, idempotent advance,
 * empty weeks, the "wait N games" cooldown fix + decrement, multi-period downtime catch-up, the
 * single-announcement guarantee (C1), and per-guild isolation (C2).
 */
class AdvanceRotationServiceTest extends AbstractPostgresIntegrationTest {

  @Autowired private AdvanceRotationService service;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void popDesignatesTopRecordsItAndShiftsTheRestUp() {
    long guild = uid();
    long a = uid();
    long b = uid();
    long slotA = insertSlot(guild, a, 1);
    insertSlot(guild, b, 2);
    setupRotation(guild, daysAgo(8)); // one period due

    AdvanceResult result = service.advanceDue(guild, Instant.now());

    assertThat(result.advancesApplied()).isEqualTo(1);
    assertThat(queuedMembers(guild)).containsExactly(b);
    assertThat(queuedPositions(guild)).containsExactly(1);
    assertThat(designationSlot(guild, 1)).isEqualTo(slotA);
    assertThat(currentSlot(guild)).isEqualTo(slotA);
    assertThat(currentWeek(guild)).isEqualTo(1);
  }

  @Test
  void emptyWeekDesignatesNothingAndTouchesNoCooldown() {
    long guild = uid();
    setupRotation(guild, daysAgo(8));

    AdvanceResult result = service.advanceDue(guild, Instant.now());

    assertThat(result.advancesApplied()).isEqualTo(1);
    assertThat(designationSlot(guild, 1)).isNull();
    assertThat(currentSlot(guild)).isNull();
    assertThat(cooldownRowCount(guild)).isZero();
  }

  @Test
  void advancingAgainForTheSameInstantIsANoOp() {
    long guild = uid();
    insertSlot(guild, uid(), 1);
    setupRotation(guild, daysAgo(8));
    Instant now = Instant.now();

    AdvanceResult first = service.advanceDue(guild, now);
    AdvanceResult second = service.advanceDue(guild, now);

    assertThat(first.advancesApplied()).isEqualTo(1);
    assertThat(second.advancesApplied()).isZero();
    assertThat(designationCount(guild)).isEqualTo(1);
  }

  @Test
  void cooldownIsFixedToNOthersWaitingAtThePop() {
    long guild = uid();
    long a = uid();
    long b = uid();
    long c = uid();
    insertSlot(guild, a, 1);
    insertSlot(guild, b, 2);
    insertSlot(guild, c, 3);
    setupRotation(guild, daysAgo(8));

    service.advanceDue(guild, Instant.now()); // pop A only

    assertThat(cooldownOf(guild, a)).isEqualTo(2); // B and C were waiting
    assertThat(cooldownOf(guild, b)).isZero();
    assertThat(cooldownOf(guild, c)).isZero();
  }

  @Test
  void multiPeriodCatchUpAppliesEachOnceAndCountsCooldownsDown() {
    long guild = uid();
    long a = uid();
    long b = uid();
    long c = uid();
    insertSlot(guild, a, 1);
    insertSlot(guild, b, 2);
    insertSlot(guild, c, 3);
    setupRotation(guild, daysAgo(22)); // three periods due

    AdvanceResult result = service.advanceDue(guild, Instant.now());

    assertThat(result.advancesApplied()).isEqualTo(3);
    assertThat(queuedMembers(guild)).isEmpty();
    assertThat(designationCount(guild)).isEqualTo(3);
    // Each became eligible only after exactly N further games played (SC-005).
    assertThat(cooldownOf(guild, a)).isZero();
    assertThat(cooldownOf(guild, b)).isZero();
    assertThat(cooldownOf(guild, c)).isZero();
  }

  @Test
  void announcesOnlyTheFinalGameOnceWhenAChannelIsConfigured() {
    long guild = uid();
    long a = uid();
    long b = uid();
    long c = uid();
    insertSlot(guild, a, 1);
    insertSlot(guild, b, 2);
    insertSlot(guild, c, 3);
    configureChannel(guild, uid());
    setupRotation(guild, daysAgo(22)); // three periods -> A, B, C popped

    AdvanceResult result = service.advanceDue(guild, Instant.now());

    assertThat(result.advancesApplied()).isEqualTo(3);
    assertThat(result.finalAnnouncement()).isPresent();
    assertThat(result.finalAnnouncement().get().currentGameName()).isEqualTo("Game" + c);

    // A re-advance for the same instant yields nothing to announce.
    assertThat(service.advanceDue(guild, Instant.now()).finalAnnouncement()).isEmpty();
  }

  @Test
  void noAnnouncementWhenNoChannelConfigured() {
    long guild = uid();
    insertSlot(guild, uid(), 1);
    setupRotation(guild, daysAgo(8));

    AdvanceResult result = service.advanceDue(guild, Instant.now());

    assertThat(result.advancesApplied()).isEqualTo(1);
    assertThat(result.finalAnnouncement()).isEmpty();
  }

  @Test
  void advancingOneGuildLeavesAnotherUntouched() {
    long guildA = uid();
    long guildB = uid();
    long memberB = uid();
    insertSlot(guildA, uid(), 1);
    insertSlot(guildB, memberB, 1);
    setupRotation(guildA, daysAgo(8));
    setupRotation(guildB, daysAgo(2)); // not yet due

    service.advanceDue(guildA, Instant.now());

    assertThat(queuedMembers(guildB)).containsExactly(memberB);
    assertThat(currentWeek(guildB)).isZero();
    assertThat(designationCount(guildB)).isZero();
  }

  // --- setup helpers ---

  private long insertSlot(long guild, long member, int position) {
    Long id =
        jdbc.queryForObject(
            "INSERT INTO queue_entry (guild_id, proposer_member_id, status, position,"
                + " game_identity, game_name, coins_spent, propose_interaction_id)"
                + " VALUES (?, ?, 'QUEUED', ?, ?, ?, 1, ?) RETURNING id",
            Long.class,
            guild,
            member,
            position,
            "name:game" + member,
            "Game" + member,
            uid());
    return id;
  }

  private void setupRotation(long guild, Instant lastPop) {
    jdbc.update(
        "INSERT INTO queue_rotation_state (guild_id, current_slot_id, current_week_number,"
            + " last_pop_at) VALUES (?, NULL, 0, ?)",
        guild,
        OffsetDateTime.ofInstant(lastPop, ZoneOffset.UTC));
  }

  private void configureChannel(long guild, long channelId) {
    jdbc.update(
        "INSERT INTO guild_queue_config (guild_id, propose_cost, bump_cost, announcement_channel_id)"
            + " VALUES (?, 1, 1, ?)",
        guild,
        channelId);
  }

  // --- queries ---

  private List<Long> queuedMembers(long guild) {
    return jdbc.queryForList(
        "SELECT proposer_member_id FROM queue_entry WHERE guild_id = ? AND status = 'QUEUED'"
            + " ORDER BY position",
        Long.class,
        guild);
  }

  private List<Integer> queuedPositions(long guild) {
    return jdbc.queryForList(
        "SELECT position FROM queue_entry WHERE guild_id = ? AND status = 'QUEUED' ORDER BY position",
        Integer.class,
        guild);
  }

  private Long designationSlot(long guild, int week) {
    return jdbc.queryForObject(
        "SELECT slot_id FROM weekly_designation WHERE guild_id = ? AND week_number = ?",
        Long.class,
        guild,
        week);
  }

  private int designationCount(long guild) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM weekly_designation WHERE guild_id = ?", Integer.class, guild);
    return n == null ? 0 : n;
  }

  private int cooldownOf(long guild, long member) {
    List<Integer> rows =
        jdbc.queryForList(
            "SELECT games_remaining FROM queue_cooldown WHERE guild_id = ? AND member_id = ?",
            Integer.class,
            guild,
            member);
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  private int cooldownRowCount(long guild) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM queue_cooldown WHERE guild_id = ?", Integer.class, guild);
    return n == null ? 0 : n;
  }

  private Long currentSlot(long guild) {
    return jdbc.queryForObject(
        "SELECT current_slot_id FROM queue_rotation_state WHERE guild_id = ?", Long.class, guild);
  }

  private int currentWeek(long guild) {
    Integer w =
        jdbc.queryForObject(
            "SELECT current_week_number FROM queue_rotation_state WHERE guild_id = ?",
            Integer.class,
            guild);
    return w == null ? 0 : w;
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static long uid() {
    return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
  }
}
