package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Parses HDFC transaction alert emails from the corpus.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern TRANSACTION = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been "
                    + "(?<dir>debited|credited) with "
                    + "(?:Rs\\.?|INR)\\s*(?<amount>[0-9,]+(?:\\.[0-9]{2})?)\\."
                    + "\\s*Merchant / Remarks: (?<merchant>[^\\r\\n]+)"
                    + "\\s*Transaction reference:",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE = Pattern.compile(
            "Date:\\s*[^,]+,\\s*"
                    + "(?<date>\\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2})"
                    + "\\s+(?<offset>[+-]\\d{4})",
            Pattern.CASE_INSENSITIVE);

    @Override
public boolean supports(RawMessage m) {
    if ("email".equalsIgnoreCase(m.channel())) {
        System.out.println(
                "EMAIL FOUND: " + m.messageId()
                        + " | sender=[" + m.sender() + "]"
        );
    }

    return "email".equalsIgnoreCase(m.channel())
            && "alerts@hdfcbank.net".equalsIgnoreCase(m.sender());
}
    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher tx = TRANSACTION.matcher(m.body());

      if (!tx.find()) {
    System.out.println("EMAIL TX REGEX FAILED: " + m.messageId());
    return Optional.empty();
}

        Matcher date = DATE.matcher(m.body());

    if (!date.find()) {
    System.out.println("EMAIL DATE REGEX FAILED: " + m.messageId());
    return Optional.empty();
}

        BigDecimal amount;

        try {
            amount = new BigDecimal(
                    tx.group("amount").replace(",", ""))
                    .setScale(2);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        OffsetDateTime occurredAt;

        try {
    String dateText = date.group("date");
    String offset = date.group("offset");

    DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern(
                    "dd MMM yyyy HH:mm:ss xx",
                    Locale.ENGLISH);

    occurredAt = OffsetDateTime.parse(
            dateText + " " + offset,
            formatter);

} catch (RuntimeException e) {
    return Optional.empty();
}
        Direction direction =
                "debited".equalsIgnoreCase(tx.group("dir"))
                        ? Direction.DEBIT
                        : Direction.CREDIT;

        return Optional.of(
                new ParsedTxn(
                        tx.group("acct"),
                        occurredAt,
                        direction,
                        amount,
                        tx.group("merchant").trim(),
                        null,
                        m.messageId()));
    }
}