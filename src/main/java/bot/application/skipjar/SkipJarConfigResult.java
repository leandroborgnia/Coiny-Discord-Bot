package bot.application.skipjar;

/** The effective skip-jar configuration after a change (US4). */
public record SkipJarConfigResult(int thresholdFloor, long dwellSeconds, boolean gateOn) {}
