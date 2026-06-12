package bot.application.queue;

/**
 * Request to configure a server's queue. {@code actorHasManageServer} is the single authorization
 * bar (FR-018/FR-037). {@code proposeCost}/{@code bumpCost} are nullable — a null leaves that cost
 * unchanged. {@code announcement} is nullable — null leaves the channel unchanged; otherwise it
 * sets or clears it.
 */
public record ConfigureQueueRequest(
    long guildId,
    boolean actorHasManageServer,
    Integer proposeCost,
    Integer bumpCost,
    ChannelOp announcement) {

  /** Announcement-channel change: {@code clear} wins; otherwise set to {@code channelId}. */
  public record ChannelOp(boolean clear, Long channelId) {

    public static ChannelOp set(long channelId) {
      return new ChannelOp(false, channelId);
    }

    public static ChannelOp off() {
      return new ChannelOp(true, null);
    }
  }
}
