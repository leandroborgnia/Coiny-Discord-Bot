package bot.application.queue;

/** The effective queue configuration after a change ({@code announcementChannelId} null = off). */
public record QueueConfigResult(int proposeCost, int bumpCost, Long announcementChannelId) {}
