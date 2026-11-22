package com.rupeepulse;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupeepulse.Models.AccountKind;
import com.rupeepulse.Models.Direction;
import com.rupeepulse.Models.NoticeIntent;
import com.rupeepulse.Models.ParsedNotice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NoticeAiParser {

    private static final Pattern MASKED_ACCT = Pattern.compile(
        "(?:A/C|A/c|account|Card|card|ending|XX+|\\*\\*+)\\s*(?:no\\.?\\s*)?(?:[X*•]+)?(\\d{4})\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern FALLBACK_LAST4 = Pattern.compile("[X*•]{2,}(\\d{4})\\b");

    private static final Pattern DEBIT_AMT = Pattern.compile(
        "(?:debited\\s+(?:by|for|with)?|spent|payment\\s+of|paid|charged)\\s*(?:INR|Rs\\.?|₹)\\s*([0-9,]+(?:\\.\\d{1,2})?)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern CREDIT_AMT = Pattern.compile(
        "(?:credited\\s+(?:with|by|for)?|received)\\s*(?:INR|Rs\\.?|₹)\\s*([0-9,]+(?:\\.\\d{1,2})?)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern ANY_INR_AMT = Pattern.compile(
        "(?:INR|Rs\\.?|₹)\\s*([0-9,]+(?:\\.\\d{1,2})?)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern BALANCE_AMT = Pattern.compile(
        "(?:New\\s+balance\\s+is|Avl\\.?\\s*Bal(?:ance)?(?:\\s+is)?|Available\\s+Balance(?:\\s+is)?|Clear\\s+Bal)\\s*[:\\-]?\\s*(?:INR|Rs\\.?|₹)\\s*([0-9,]+(?:\\.\\d{1,2})?)(?:\\s*(CR|DR))?",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern REVISED_DUE_AMT = Pattern.compile(
        "(?:adjusted\\s+total\\s+amount\\s+due|revised\\s+(?:auto-?debit|automatic\\s+payment|due)|now\\s+debit|scheduled\\s+automatic\\s+payment)(?:(?!minimum|refund|INR|Rs\\.?|₹).){0,100}?(?:INR|Rs\\.?|₹)\\s*([0-9,]+(?:\\.\\d{1,2})?)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_DUE = Pattern.compile("total\\s+(?:amount\\s+)?due(?:(?!minimum|INR|Rs\\.?|₹).){0,100}?(?:INR|Rs\\.?|₹)\\s*([0-9,]+(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern REF_ID = Pattern.compile(
        "(?:UPI\\s*Ref(?:\\s*No\\.?|\\s*ID)?|Txn\\s*ID|Ref\\s*No\\.?|UTR)\\s*[:\\-#]?\\s*([A-Za-z0-9]{6,22})",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern TO_PARTY = Pattern.compile(
        "(?:towards\\s+UPI/|towards\\s+|to\\s+|at\\s+|by\\s+)([A-Za-z0-9 .&_\\-]{2,32}?)(?:\\s+from\\b|\\s+on\\b|\\.\\s|\\.$|,|$)",
        Pattern.CASE_INSENSITIVE);

    private final ObjectMapper json;
    private final URI azureEndpoint;
    private final AzureModelAccess access;
    private final String azureDeployment;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public NoticeAiParser(
            ObjectMapper json,
            @Value("${AZURE_OPENAI_ENDPOINT:}") String baseEndpoint,
            AzureModelAccess access,
            @Value("${AZURE_OPENAI_DEPLOYMENT:gpt-5.4-mini}") String deployment) {
        this.json = json;
        this.access = access;
        this.azureDeployment = (deployment == null || deployment.isBlank()) ? "gpt-5.4-mini" : deployment.trim();
        this.azureEndpoint = resolveAzureEndpoint(baseEndpoint, access.configured());
    }

    static URI resolveAzureEndpoint(String base, boolean enabled) {
        if (base == null || base.isBlank() || !enabled) {
            return null;
        }
        URI uri = URI.create(base.trim());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !(host.endsWith(".openai.azure.com") ||
                host.endsWith(".cognitiveservices.azure.com") || host.endsWith(".services.ai.azure.com"))
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPort()==-1 || uri.getPort()==443) || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Set the HTTPS resource root of your Azure OpenAI deployment.");
        return uri.resolve("/openai/v1/responses");
    }

    public boolean isLiveAzureConfigured() {
        return azureEndpoint != null;
    }

    public String aiModelLabel() {
        return isLiveAzureConfigured()
            ? "Azure OpenAI (" + azureDeployment + ") + Deterministic BigDecimal Math"
            : "Built-in Indian Bank/Card NLP Extractor + Deterministic BigDecimal Math (Set AZURE_OPENAI_API_KEY for live GPT)";
    }

    public ParsedNotice parse(String sender, String rawText, boolean preferLiveAi) {
        String cleaned = rawText == null ? "" : rawText.trim();
        if (cleaned.length()>10000) throw new IllegalArgumentException("Paste one notice of at most 10,000 characters.");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Paste a bank or credit card email/SMS first.");
        }
        if (preferLiveAi && isLiveAzureConfigured()) {
            ParsedNotice live = tryAzureParse(sender, cleaned);
            if (live != null) {
                return live;
            }
        }
        return parseDeterministically(sender, cleaned);
    }

    private ParsedNotice tryAzureParse(String sender, String rawText) {
        var schema = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of(
                "intent", "institution", "accountKind", "maskedNumber", "direction",
                "amount", "reportedBalance", "counterparty", "category",
                "referenceId", "recurring", "summary", "evidenceQuote"
            ),
            "properties", Map.ofEntries(
                Map.entry("intent", Map.of("type", "string", "enum", List.of(
                    "BANK_TXN", "CARD_TXN", "CARD_BILL_SUMMARY", "MERCHANT_REFUND_PROMISE", "ISSUER_REVISED_DEBIT"))),
                Map.entry("institution", Map.of("type", "string")),
                Map.entry("accountKind", Map.of("type", "string", "enum", List.of("BANK", "CREDIT_CARD"))),
                Map.entry("maskedNumber", Map.of("type", "string")),
                Map.entry("direction", Map.of("type", "string", "enum", List.of("DEBIT", "CREDIT"))),
                Map.entry("amount", Map.of("type", "number")),
                Map.entry("reportedBalance", Map.of("type", List.of("number", "null"))),
                Map.entry("counterparty", Map.of("type", "string")),
                Map.entry("category", Map.of("type", "string")),
                Map.entry("referenceId", Map.of("type", "string")),
                Map.entry("recurring", Map.of("type", "boolean")),
                Map.entry("summary", Map.of("type", "string")),
                Map.entry("evidenceQuote", Map.of("type", "string"))
            )
        );

        String instructions = """
            You parse Indian bank alerts, UPI notifications, credit card bills, merchant refund emails, and card-issuer revised auto-debit notices.
            Critical rules:
            1. In an alert like 'debited by INR 250 ... New balance is INR 1000CR', 'CR' on the balance means positive balance, NOT a credit transaction. Direction is DEBIT, amount is 250, reportedBalance is 1000.
            2. If a shop/merchant (like Myntra, Amazon, Flipkart) says 'refund initiated / allow 5-7 days', intent MUST be MERCHANT_REFUND_PROMISE because the card auto-debit has NOT changed yet.
            3. Only when the card issuer/bank explicitly confirms refund credited to the credit card AND states a revised/adjusted due or auto-debit amount, intent MUST be ISSUER_REVISED_DEBIT and amount is the new revised debit amount.
            4. maskedNumber should be formatted as '••1234' using the last 4 digits if present, else '••0000'.
            5. evidenceQuote MUST be an exact verbatim substring copied from the input message.
            """;

        String inputText = (sender != null && !sender.isBlank() ? "Sender: " + sender + "\n" : "") + rawText;
        var payload = Map.of(
            "model", azureDeployment,
            "store", false,
            "max_output_tokens", 700,
            "instructions", instructions,
            "input", inputText,
            "text", Map.of("format", Map.of(
                "name", "indian_finance_notice",
                "type", "json_schema",
                "strict", true,
                "schema", schema
            ))
        );

        try {
            HttpRequest req = access.authorize(HttpRequest.newBuilder(azureEndpoint))
                .timeout(Duration.ofSeconds(18))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode root = json.readTree(resp.body());
            for (JsonNode output : root.path("output")) {
                for (JsonNode content : output.path("content")) {
                    if (!"output_text".equals(content.path("type").asText())) continue;
                    JsonNode node = json.readTree(content.path("text").asText());
                    String quote = node.path("evidenceQuote").asText("");
                    if (quote.isBlank() || !rawText.contains(quote)) {
                        return null; // An ungrounded model response isn't evidence.
                    }
                    BigDecimal reportedBal = node.path("reportedBalance").isNull() || node.path("reportedBalance").isMissingNode()
                        ? null
                        : node.path("reportedBalance").decimalValue();
                    return new ParsedNotice(
                        NoticeIntent.valueOf(node.path("intent").asText("BANK_TXN")),
                        node.path("institution").asText("Unknown Bank"),
                        AccountKind.valueOf(node.path("accountKind").asText("BANK")),
                        normalizeLast4(node.path("maskedNumber").asText("")),
                        Direction.valueOf(node.path("direction").asText("DEBIT")),
                        node.path("amount").decimalValue(),
                        reportedBal,
                        node.path("counterparty").asText("Unknown"),
                        node.path("category").asText("General"),
                        transactionDate(rawText),
                        node.path("referenceId").asText(""),
                        node.path("recurring").asBoolean(false),
                        "AZURE_GPT (" + azureDeployment + ")",
                        node.path("summary").asText(""),
                        quote
                    );
                }
            }
        } catch (DailyModelBudget.Limit ex) {
            throw ex;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    public ParsedNotice parseDeterministically(String sender, String rawText) {
        String lower = ((sender == null ? "" : sender + " ") + rawText).toLowerCase(Locale.ROOT);
        String institution = detectInstitution(lower);
        String last4 = extractLast4(rawText);
        boolean mentionsCard = lower.contains("credit card") || lower.contains("regalia")
            || lower.contains("amazon pay icici card");

        // 1. Check Merchant Refund Promise vs Card Issuer Revised Debit
        boolean mentionsRefund = lower.contains("refund");
        boolean isMerchantPromise = mentionsRefund && (
            lower.contains("initiated") || lower.contains("allow 5") || lower.contains("business days")
            || lower.contains("being processed") || lower.contains("myntra") && !lower.contains("hdfc bank credit card"));
        boolean isIssuerRevision = (lower.contains("adjusted total amount due")
            || lower.contains("revised auto-debit")
            || lower.contains("scheduled automatic payment")
            || lower.contains("now debit")) && (mentionsCard || lower.contains("hdfc") || lower.contains("icici"));

        if (isIssuerRevision) {
            BigDecimal revisedAmt = extractAmount(REVISED_DUE_AMT, rawText);
            if (revisedAmt == null) {
                throw new IllegalArgumentException("No revised INR debit amount is stated clearly enough to update this card.");
            }
            return new ParsedNotice(
                NoticeIntent.ISSUER_REVISED_DEBIT,
                institution,
                AccountKind.CREDIT_CARD,
                last4,
                Direction.CREDIT,
                revisedAmt != null ? revisedAmt : BigDecimal.ZERO,
                null,
                "Card Issuer Auto-Debit Revision",
                "Credit Card Adjustment",
                nowFormatted(),
                extractGroup(REF_ID, rawText, ""),
                false,
                "Local rules",
                "Card issuer confirmed refund & revised scheduled auto-debit to ₹" + revisedAmt,
                bestEvidenceSnippet(rawText)
            );
        }

        if (isMerchantPromise && !lower.contains("credited to your a/c")) {
            BigDecimal refundAmt = extractAmount(ANY_INR_AMT, rawText);
            return new ParsedNotice(
                NoticeIntent.MERCHANT_REFUND_PROMISE,
                detectMerchantName(lower),
                AccountKind.CREDIT_CARD,
                last4,
                Direction.CREDIT,
                refundAmt != null ? refundAmt : BigDecimal.ZERO,
                null,
                detectMerchantName(lower),
                "Pending Refund",
                nowFormatted(),
                extractGroup(REF_ID, rawText, ""),
                false,
                "Local rules",
                "Merchant promised refund (5–7 days); card issuer has NOT reduced Wednesday's auto-debit yet.",
                bestEvidenceSnippet(rawText)
            );
        }

        // 2. Credit Card Bill Summary
        if (mentionsCard && (lower.contains("total amount due") || lower.contains("statement is generated"))) {
            BigDecimal totalDue = extractAmount(TOTAL_DUE, rawText);
            if (totalDue == null) throw new IllegalArgumentException("Could not identify the total due separately from the minimum due.");
            return new ParsedNotice(
                NoticeIntent.CARD_BILL_SUMMARY,
                institution,
                AccountKind.CREDIT_CARD,
                last4,
                Direction.DEBIT,
                totalDue != null ? totalDue : BigDecimal.ZERO,
                null,
                institution + " Statement",
                "Credit Card Bill",
                nowFormatted(),
                extractGroup(REF_ID, rawText, ""),
                true,
                "Local rules",
                "Credit card bill detected: Total Due ₹" + totalDue,
                bestEvidenceSnippet(rawText)
            );
        }

        // 3. Standard Bank or Credit Card Transaction
        // Crucial: Check DEBIT before generic CR so IDFC 'INR 599.99CR' balance tag never flips a debit into a credit!
        BigDecimal debitAmt = extractAmount(DEBIT_AMT, rawText);
        BigDecimal creditAmt = extractAmount(CREDIT_AMT, rawText);
        Direction direction;
        BigDecimal amount;
        if (debitAmt != null) {
            direction = Direction.DEBIT;
            amount = debitAmt;
        } else if (creditAmt != null) {
            direction = Direction.CREDIT;
            amount = creditAmt;
        } else {
            if (!lower.matches("(?s).*\\b(spent|debited|credited|paid|charged|received)\\b.*")
                || lower.matches("(?s).*\\b(offer|otp|will be|scheduled|failed|declined)\\b.*"))
                throw new IllegalArgumentException("A completed transaction is not clear from this notice. No row was added.");
            direction = lower.contains("credit") && !lower.contains("credit card") ? Direction.CREDIT : Direction.DEBIT;
            amount = extractAmount(ANY_INR_AMT, rawText);
            if (amount == null) {
                throw new IllegalArgumentException("Could not find an INR / ₹ amount in the pasted alert.");
            }
        }

        BigDecimal reportedBalance = extractBalance(rawText);
        String counterparty = extractCounterparty(rawText, lower);
        boolean recurring = isRecurringCategory(lower, counterparty);
        String category = classifyCategory(lower, counterparty, direction, recurring);
        AccountKind kind = mentionsCard ? AccountKind.CREDIT_CARD : AccountKind.BANK;

        return new ParsedNotice(
            kind == AccountKind.CREDIT_CARD ? NoticeIntent.CARD_TXN : NoticeIntent.BANK_TXN,
            institution,
            kind,
            last4,
            direction,
            amount,
            reportedBalance,
            counterparty,
            category,
            transactionDate(rawText),
            extractGroup(REF_ID, rawText, ""),
            recurring,
            "Local rules",
            institution + " " + last4 + " " + direction + " ₹" + amount + " (" + counterparty + ")",
            bestEvidenceSnippet(rawText)
        );
    }

    private static String detectInstitution(String lower) {
        if (lower.contains("idfc")) return "IDFC FIRST Bank";
        if (lower.contains("uco")) return "UCO Bank";
        if (lower.contains("hdfc")) return "HDFC Bank";
        if (lower.contains("icici") || lower.contains("imobile")) return "ICICI Bank";
        if (lower.contains("sbi") || lower.contains("state bank")) return "State Bank of India";
        if (lower.contains("axis")) return "Axis Bank";
        if (lower.contains("kotak")) return "Kotak Mahindra Bank";
        return "Unknown Institution";
    }

    private static String detectMerchantName(String lower) {
        if (lower.contains("myntra")) return "Myntra";
        if (lower.contains("amazon")) return "Amazon";
        if (lower.contains("flipkart")) return "Flipkart";
        if (lower.contains("swiggy")) return "Swiggy";
        if (lower.contains("zomato")) return "Zomato";
        return "Merchant Store";
    }

    private static String extractLast4(String text) {
        Matcher m1 = MASKED_ACCT.matcher(text);
        if (m1.find()) {
            return "••" + m1.group(1);
        }
        Matcher m2 = FALLBACK_LAST4.matcher(text);
        if (m2.find()) {
            return "••" + m2.group(1);
        }
        return "••0000";
    }

    private static String normalizeLast4(String raw) {
        Matcher m = Pattern.compile("(\\d{4})$").matcher(raw == null ? "" : raw.trim());
        return m.find() ? "••" + m.group(1) : "••0000";
    }

    private static BigDecimal extractAmount(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (m.find()) {
            return new BigDecimal(m.group(1).replace(",", ""));
        }
        return null;
    }

    private static BigDecimal extractLastAmount(String text) {
        Matcher m = ANY_INR_AMT.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last == null ? null : new BigDecimal(last.replace(",", ""));
    }

    private static BigDecimal extractBalance(String text) {
        Matcher m = BALANCE_AMT.matcher(text);
        if (m.find()) {
            BigDecimal val = new BigDecimal(m.group(1).replace(",", ""));
            String suffix = m.group(2);
            if ("DR".equalsIgnoreCase(suffix)) {
                return val.negate();
            }
            return val;
        }
        return null;
    }

    private static String extractCounterparty(String rawText, String lower) {
        Matcher m = TO_PARTY.matcher(rawText);
        if (m.find()) {
            String found = m.group(1).trim();
            if (!found.isEmpty() && !found.equalsIgnoreCase("INR") && !found.equalsIgnoreCase("Rs")) {
                return found;
            }
        }
        if (lower.contains("netflix")) return "Netflix";
        if (lower.contains("spotify")) return "Spotify";
        if (lower.contains("swiggy")) return "Swiggy";
        if (lower.contains("zomato")) return "Zomato";
        if (lower.contains("myntra")) return "Myntra";
        if (lower.contains("croma")) return "Croma Electronics";
        return "Bank / UPI Counterparty";
    }

    private static boolean isRecurringCategory(String lower, String party) {
        String combined = (lower + " " + party).toLowerCase(Locale.ROOT);
        return combined.contains("netflix") || combined.contains("spotify") || combined.contains("icloud")
            || combined.contains("autopay") || combined.contains("subscription") || combined.contains("membership")
            || combined.contains("cult") || combined.contains("chatgpt") || combined.contains("airtel")
            || combined.contains("lic") || combined.contains("premium");
    }

    private static String classifyCategory(String lower, String party, Direction dir, boolean recurring) {
        if (dir == Direction.CREDIT) {
            if (lower.contains("salary") || lower.contains("neft")) return "Salary / Income";
            return "UPI / Transfer In";
        }
        if (recurring) return "Recurring / Subscription";
        if (lower.contains("swiggy") || lower.contains("zomato") || lower.contains("coffee") || lower.contains("tokai") || lower.contains("zepto")) {
            return "Food & Groceries";
        }
        if (lower.contains("myntra") || lower.contains("amazon") || lower.contains("croma")) {
            return "Shopping & Electronics";
        }
        if (lower.contains("bescom") || lower.contains("electricity") || lower.contains("bill")) {
            return "Utilities & Bills";
        }
        return "UPI / Peer Transfer";
    }

    private static String extractGroup(Pattern p, String text, String fallback) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : fallback;
    }

    private static String bestEvidenceSnippet(String rawText) {
        return rawText.substring(0, Math.min(rawText.length(),200));
    }

    private static final Pattern NUMERIC_DATE = Pattern.compile("\\b(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{4})\\b");
    private static String transactionDate(String text) {
        Matcher date = NUMERIC_DATE.matcher(text);
        if (date.find()) return LedgerDates.date(date.group()).toString();
        Matcher us = Pattern.compile("(?i)\\b([A-Z]{3}) (\\d{1,2}), (\\d{4})\\b").matcher(text);
        if (us.find()) return LedgerDates.date(us.group(2)+" "+us.group(1)+" "+us.group(3)).toString();
        return nowFormatted();
    }

    private static String nowFormatted() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH));
    }
}
