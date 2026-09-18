package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * Multiple raw messages can describe the same underlying bank transaction.
 * Those messages are represented by one normalized transaction with all
 * source message IDs retained as evidence.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {

        List<RawMessage> messages = readCorpus(corpus);

        int parsed = 0;
        int skipped = 0;

        Map<String, NormalizedTxn> transactions = new LinkedHashMap<>();

        for (RawMessage m : messages) {

            Optional<ParsedTxn> p = parsers.parse(m);
if (p.isEmpty()) {
    System.out.println(
            "SKIPPED: " + m.messageId()
                    + " | " + m.body());
    skipped++;
    continue;
}
            parsed++;

            NormalizedTxn txn = toTransaction(p.get());

            String key = transactionKey(txn);

            NormalizedTxn existing = transactions.get(key);

            if (existing == null) {

                transactions.put(key, txn);

            } else {

                List<String> sourceIds =
                        new ArrayList<>(existing.sourceMessageIds());

                if (!sourceIds.contains(p.get().sourceMessageId())) {
                    sourceIds.add(p.get().sourceMessageId());
                }

                sourceIds.sort(String::compareTo);

                transactions.put(
                        key,
                        new NormalizedTxn(
                                existing.accountLast4(),
                                existing.occurredAt(),
                                existing.direction(),
                                existing.amount(),
                                existing.category(),
                                existing.merchant(),
                                sourceIds));
            }
        }

for (NormalizedTxn txn : transactions.values()) {

    if ("4821".equals(txn.accountLast4())) {
        System.out.println(
                "TXN4821 | "
                        + txn.occurredAt()
                        + " | "
                        + txn.direction()
                        + " | "
                        + txn.amount()
                        + " | "
                        + txn.merchant()
                        + " | "
                        + txn.category()
                        + " | "
                        + txn.sourceMessageIds());
    }

    store.save(txn);
}

        return new Stats(
                messages.size(),
                transactions.size(),
                skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {

        List<RawMessage> out = new ArrayList<>();

        try (Stream<String> lines = Files.lines(corpus)) {

            for (String line :
                    (Iterable<String>) lines
                            .filter(s -> !s.isBlank())::iterator) {

                Map<String, Object> o = Json.parseObject(line);

                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse(
                                (String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }

        return out;
    }

    private NormalizedTxn toTransaction(ParsedTxn p) {

        Category c;

        if (p.direction() == Direction.DEBIT
                && p.amount().compareTo(
                        new BigDecimal("100.00")) <= 0
                && p.merchant()
                        .toUpperCase()
                        .contains("UPI")) {

            c = Category.MICRO;

        } else if (isTransfer(p)) {

            c = Category.TRANSFER;

        } else if (p.direction() == Direction.DEBIT) {

            c = Category.SPEND;

        } else {

            c = Category.INCOME;
        }

        return new NormalizedTxn(
                p.accountLast4(),
                p.occurredAt(),
                p.direction(),
                p.amount(),
                c,
                p.merchant(),
                List.of(p.sourceMessageId()));
    }

    private boolean isTransfer(ParsedTxn p) {

        String merchant = p.merchant().toUpperCase();

        return merchant.contains("TRANSFER")
                || merchant.contains("SELF")
                || merchant.contains("OWN ACCOUNT");
    }

    private String transactionKey(NormalizedTxn txn) {

        return txn.accountLast4()
                + "|"
                + txn.occurredAt()
                + "|"
                + txn.direction()
                + "|"
                + txn.amount()
                + "|"
                + txn.merchant();
    }

    public record Stats(
            int messagesRead,
            int transactionsWritten,
            int messagesSkipped) {
    }
}