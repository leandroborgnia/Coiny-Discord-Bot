/**
 * Coin economy use cases. These {@code @Transactional} services are the only components that open
 * transactions and call the coin ports; each public method takes a request record and returns a
 * result record.
 */
package bot.application.coin;
