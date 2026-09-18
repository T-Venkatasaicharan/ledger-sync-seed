package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HDFC Bank SMS.
 *
 * Handles the known HDFC transaction message shapes in the corpus.
 */
public final class HdfcSmsParser implements MessageParser {

    public static final String SENDER = "AD-HDFCBK-S";

    private static final Pattern V1 = Pattern.compile(
            "(?<dir>debited from|credited to) a/c \\*\\*(?<acct>\\d{4}) "
                    + "on (?<when>\\d{2}-\\d{2}-\\d{2} at \\d{2}:\\d{2}) "
                    + "(?:to|by) (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "^(?<dir>Sent|Received) .*?\\n(?:To|From): (?<merchant>.+?)\\n"
                    + "On: (?<when>\\d{2} \\w{3} \\d{2} \\d{2}:\\d{2})\\n"
                    + "A/c: XX(?<acct>\\d{4})",
            Pattern.DOTALL);

    private static final Pattern CARD = Pattern.compile(
            "spent on HDFC Bank Card x(?<acct>\\d{4}) at (?<merchant>.+?) "
                    + "on (?<when>\\d{2}-\\d{2}-\\d{2} \\d{2}:\\d{2})\\.");

    private static final Pattern EMANDATE = Pattern.compile(
            "E-mandate!\\s+(?:Rs\\.?|INR)\\s*"
                    + "[0-9,]+(?:\\.[0-9]{2})?\\s+will be deducted from your HDFC Bank "
                    + "A/c XX(?<acct>\\d{4}) on "
                    + "(?<when>\\d{2}-\\d{2}-\\d{2}) at (?<time>\\d{2}:\\d{2}) "
                    + "for (?<merchant>.+?)\\.",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            Direction d = v1.group("dir").startsWith("debited")
                    ? Direction.DEBIT
                    : Direction.CREDIT;

            return build(
                    m,
                    v1.group("acct"),
                    v1.group("when").replace(" at ", " "),
                    d,
                    v1.group("merchant"));
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            Direction d = "Sent".equalsIgnoreCase(v2.group("dir"))
                    ? Direction.DEBIT
                    : Direction.CREDIT;

            return build(
                    m,
                    v2.group("acct"),
                    v2.group("when"),
                    d,
                    v2.group("merchant"));
        }

        Matcher card = CARD.matcher(body);
        if (card.find()) {
            return build(
                    m,
                    card.group("acct"),
                    card.group("when"),
                    Direction.DEBIT,
                    card.group("merchant"));
        }

        Matcher emandate = EMANDATE.matcher(body);
        if (emandate.find()) {
            return build(
                    m,
                    emandate.group("acct"),
                    emandate.group("when") + " " + emandate.group("time"),
                    Direction.DEBIT,
                    emandate.group("merchant"));
        }

        return Optional.empty();
    }

    private Optional<ParsedTxn> build(
            RawMessage m,
            String acct,
            String when,
            Direction dir,
            String merchant) {

        BigDecimal amount = Amounts.first(m.body());
        OffsetDateTime at = Dates.ist(when);

        if (amount == null || at == null) {
            return Optional.empty();
        }

        return Optional.of(
                new ParsedTxn(
                        acct,
                        at,
                        dir,
                        amount,
                        merchant.trim(),
                        Amounts.statedBalance(m.body()),
                        m.messageId()));
    }
}