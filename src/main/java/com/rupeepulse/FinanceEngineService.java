package com.rupeepulse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.rupeepulse.Models.*;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.SessionScope;

@Service
@SessionScope
public class FinanceEngineService {

    private static final class MutableBank {
        final String id;
        final String bankName;
        final String maskedNumber;
        final String ifscCode;
        final String accountRole;
        BigDecimal openingBalance;
        BigDecimal latestReportedBalance;
        BigDecimal netAfterReport = BigDecimal.ZERO;
        LocalDate reportDay = LedgerDates.AS_OF;
        final BigDecimal interestRatePct;
        boolean aaLinked;
        String lastUpdatedNote;

        MutableBank(String id, String bankName, String maskedNumber, String ifscCode, String accountRole,
                    String opening, String reported, String ratePct, boolean aaLinked, String note) {
            this.id = id;
            this.bankName = bankName;
            this.maskedNumber = maskedNumber;
            this.ifscCode = ifscCode;
            this.accountRole = accountRole;
            this.openingBalance = bd(opening);
            this.latestReportedBalance = reported == null ? null : bd(reported);
            this.interestRatePct = bd(ratePct);
            this.aaLinked = aaLinked;
            this.lastUpdatedNote = note;
        }
    }

    private static final class MutableCard {
        final String id;
        final String issuerName;
        final String cardName;
        final String maskedNumber;
        BigDecimal statementTotalDue;
        BigDecimal minimumDue;
        BigDecimal effectiveAutopayDue;
        final String dueDate;
        final String autopayBankAccountId;
        final String autopayBankLabel;
        BigDecimal pendingMerchantRefund;
        String statusNote;
        String evidenceQuote;

        MutableCard(String id, String issuerName, String cardName, String maskedNumber,
                    String totalDue, String minDue, String dueDate,
                    String autopayBankAccountId, String autopayBankLabel,
                    String statusNote, String evidenceQuote) {
            this.id = id;
            this.issuerName = issuerName;
            this.cardName = cardName;
            this.maskedNumber = maskedNumber;
            this.statementTotalDue = bd(totalDue);
            this.minimumDue = bd(minDue);
            this.effectiveAutopayDue = bd(totalDue);
            this.dueDate = dueDate;
            this.autopayBankAccountId = autopayBankAccountId;
            this.autopayBankLabel = autopayBankLabel;
            this.pendingMerchantRefund = bd("0.00");
            this.statusNote = statusNote;
            this.evidenceQuote = evidenceQuote;
        }
    }

    private final NoticeAiParser parser;
    private final MoneyAiAnalyst analyst;

    private final Map<String, MutableBank> banks = new LinkedHashMap<>();
    private final Map<String, MutableCard> cards = new LinkedHashMap<>();
    private final List<TransactionItem> transactions = new ArrayList<>();
    private final Set<UUID> importedStatements = new HashSet<>();
    private final List<PortfolioHolding> holdings = new ArrayList<>();
    private final List<RecurringBillItem> recurringBills = new ArrayList<>();
    private final List<PayableBiller> payableBillers = new ArrayList<>();
    private final List<InsurancePolicy> insurancePolicies = new ArrayList<>();
    private final List<SharedSpace> sharedSpaces = new ArrayList<>();
    private final List<String> activityFeed = new ArrayList<>();

    private boolean finvuConnected;
    private String finvuHandle;
    private String finvuConsentId;
    private List<String> finvuLinkedBanks = new ArrayList<>();
    private String finvuApprovedAt;

    private boolean gmailSynced;
    private int gmailMessagesScanned;
    private int duplicatesIgnored;
    private String gmailSyncedAt;

    public FinanceEngineService(NoticeAiParser parser, MoneyAiAnalyst analyst) {
        this.parser = parser;
        this.analyst = analyst;
        resetWorkspace(false);
    }

    public synchronized AiAskResponse askAi(AiAskRequest req) {
        String q = req == null ? "" : req.question();
        List<AiTurn> history = (req == null || req.history() == null) ? List.of() : req.history();
        DashboardResponse snapshot = buildDashboard();
        AiAskResponse res = analyst.ask(q, history, snapshot);
        activityFeed.add(0, "Asked AI Copilot: \"" + (q.length() > 58 ? q.substring(0, 55) + "..." : q) + "\" (" + res.modelUsed() + ")");
        return res;
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale2(BigDecimal val) {
        return val == null ? null : val.setScale(2, RoundingMode.HALF_UP);
    }

    private static String nowLabel() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a", Locale.ENGLISH));
    }

    public synchronized DashboardResponse resetWorkspace(boolean startEmpty) {
        banks.clear();
        cards.clear();
        transactions.clear();
        importedStatements.clear();
        holdings.clear();
        recurringBills.clear();
        payableBillers.clear();
        insurancePolicies.clear();
        sharedSpaces.clear();
        activityFeed.clear();
        finvuConnected = false;
        finvuHandle = "demo@finvu";
        finvuConsentId = "";
        finvuLinkedBanks = new ArrayList<>();
        finvuApprovedAt = "";
        gmailSynced = false;
        gmailMessagesScanned = 0;
        duplicatesIgnored = 0;
        gmailSyncedAt = "";

        if (startEmpty) {
            activityFeed.add("Workspace cleared. Load sample records or import a redacted alert.");
            return buildDashboard();
        }

        connectFinvuInternal("demo@finvu", List.of("IDFC FIRST Bank", "ICICI Bank", "UCO Bank"));
        syncGmailAlertsInternal();
        seedPortfolioHoldings();
        seedRecurringBills();
        seedBillersSpacesAndPolicies();
        return buildDashboard();
    }

    public synchronized DashboardResponse connectFinvu(FinvuConnectRequest req) {
        if (req == null || req.banks() == null || req.banks().isEmpty()
                || !List.of("IDFC FIRST Bank", "ICICI Bank", "UCO Bank").containsAll(req.banks())) {
            throw new IllegalArgumentException("Choose at least one of the sample banks.");
        }
        connectFinvuInternal("demo@finvu", req.banks().stream().distinct().toList());
        return buildDashboard();
    }

    private void connectFinvuInternal(String handle, List<String> selectedBanks) {
        this.finvuConnected = true;
        this.finvuHandle = handle;
        this.finvuConsentId = "AA-REBIT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        this.finvuApprovedAt = nowLabel();
        this.finvuLinkedBanks = new ArrayList<>();

        for (String b : selectedBanks) {
            String low = b.toLowerCase(Locale.ROOT);
            if (low.contains("idfc")) {
                finvuLinkedBanks.add("IDFC FIRST Bank ••9101");
                banks.putIfAbsent("bank-idfc-9101", new MutableBank(
                    "bank-idfc-9101", "IDFC FIRST Bank", "••9101", "IDFB0080102", "Autopay & Bills Hub",
                    "13835.56", "11250.56", "3.00", true,
                    "Finvu AA DEPOSIT FIP synced"));
                addTxnIfMissing(new TransactionItem(
                    "aa-idfc-1", "17 Nov 2026, 09:15 AM", "bank-idfc-9101",
                    "IDFC FIRST Bank", "••9101", AccountKind.BANK, Direction.DEBIT,
                    bd("1500.00"), "BESCOM Electricity Bill", "Utilities & Bills",
                    "AA78100231", bd("12335.56"), DataSource.FINVU_AA, "FINVU_REBIT_XML",
                    "NAR: BBPS/BESCOM/BLRSOUTH | TYPE: DEBIT | AMT: 1500.00", false));
                addTxnIfMissing(new TransactionItem(
                    "aa-idfc-2", "19 Nov 2026, 10:02 AM", "bank-idfc-9101",
                    "IDFC FIRST Bank", "••9101", AccountKind.BANK, Direction.DEBIT,
                    bd("500.00"), "Airtel Postpaid AutoPay", "Recurring / Subscription",
                    "AA78100994", bd("11835.56"), DataSource.FINVU_AA, "FINVU_REBIT_XML",
                    "NAR: SI/AIRTEL/POSTPAID | TYPE: DEBIT | AMT: 500.00", true));
                addTxnIfMissing(new TransactionItem(
                    "aa-idfc-design", "20 Nov 2026, 10:00 AM", "bank-idfc-9101",
                    "IDFC FIRST Bank", "••9101", AccountKind.BANK, Direction.DEBIT,
                    bd("510.00"), "Design Weekly", "Recurring / Subscription",
                    "DEMODESIGN020", bd("11325.56"), DataSource.FINVU_AA, "FINVU_REBIT_XML",
                    "NAR: SI/SAMPLE DESIGN WEEKLY | TYPE: DEBIT | AMT: 510.00", true));
                addTxnIfMissing(new TransactionItem(
                    "aa-idfc-tea", "20 Nov 2026, 04:15 PM", "bank-idfc-9101",
                    "IDFC FIRST Bank", "••9101", AccountKind.BANK, Direction.DEBIT,
                    bd("75.00"), "Sample Tea Stand", "Food & Groceries",
                    "DEMOTEA020", bd("11250.56"), DataSource.FINVU_AA, "FINVU_REBIT_XML",
                    "NAR: UPI/TEA STAND | TYPE: DEBIT | AMT: 75.00", false));
            } else if (low.contains("icici")) {
                finvuLinkedBanks.add("ICICI Bank ••9102");
                banks.putIfAbsent("bank-icici-9102", new MutableBank(
                    "bank-icici-9102", "ICICI Bank", "••9102", "ICIC0003168", "Daily UPI & Lifestyle",
                    "16448.00", "15149.00", "3.00", true,
                    "Finvu AA DEPOSIT FIP synced"));
                addTxnIfMissing(new TransactionItem(
                    "aa-icici-1", "18 Nov 2026, 07:10 AM", "bank-icici-9102",
                    "ICICI Bank", "••9102", AccountKind.BANK, Direction.DEBIT,
                    bd("1299.00"), "Cult.fit Pro Membership", "Recurring / Subscription",
                    "AA99200412", bd("15149.00"), DataSource.FINVU_AA, "FINVU_REBIT_XML",
                    "NAR: UPI-AUTOPAY/CULTFIT | TYPE: DEBIT | AMT: 1299.00", true));
            } else if (low.contains("uco")) {
                finvuLinkedBanks.add("UCO Bank ••9103");
                banks.putIfAbsent("bank-uco-9103", new MutableBank(
                    "bank-uco-9103", "UCO Bank", "••9103", "UCBA0009103", "Savings & Salary Reserve",
                    "167236.33", "184536.33", "2.75", true,
                    "Finvu AA DEPOSIT FIP synced"));
                addTxnIfMissing(new TransactionItem(
                    "aa-uco-flight", "04 Oct 2026, 08:20 PM", "bank-uco-9103",
                    "UCO Bank", "••9103", AccountKind.BANK, Direction.DEBIT,
                    bd("24500.00"), "IndiGo Airlines — BLR↔JAI Roundtrip", "Travel & Flights",
                    "UCUPI600412", bd("142736.33"), DataSource.FINVU_AA, "FINVU_REBIT_XML",
                    "NAR: UPI/INDIGO AIRLINES/PNR-K8Q2M | TYPE: DEBIT | AMT: 24500.00", false));
                addTxnIfMissing(new TransactionItem(
                    "aa-uco-rent", "05 Nov 2026, 09:05 AM", "bank-uco-9103",
                    "UCO Bank", "••9103", AccountKind.BANK, Direction.DEBIT,
                    bd("18200.00"), "Sample apartment rent", "Housing & Rent",
                    "UCNEFT71904", bd("124536.33"), DataSource.FINVU_AA, "FINVU_REBIT_XML",
                    "NAR: NEFT/RENT NOV | TYPE: DEBIT | AMT: 18200.00", false));
                addTxnIfMissing(new TransactionItem(
                    "aa-uco-1", "16 Nov 2026, 11:30 AM", "bank-uco-9103",
                    "UCO Bank", "••9103", AccountKind.BANK, Direction.CREDIT,
                    bd("60000.00"), "NEFT Salary / Bonus Credit", "Salary / Income",
                    "UCNEFT88210", bd("184536.33"), DataSource.FINVU_AA, "FINVU_REBIT_XML",
                    "NAR: NEFT/CR/MONTHLY PAYROLL | TYPE: CREDIT | AMT: 60000.00", false));
            }
        }
        activityFeed.add(0, "Finvu AA Consent " + finvuConsentId + " approved for "
            + String.join(", ", finvuLinkedBanks) + " + CAMS/NSDL Wealth FIPs.");
        if (holdings.isEmpty()) {
            seedPortfolioHoldings();
        }
        if (recurringBills.isEmpty()) {
            seedRecurringBills();
        }
    }

    private void seedPortfolioHoldings() {
        holdings.clear();
        holdings.add(new PortfolioHolding(
            "pf-mf-ppfas", "MUTUAL_FUNDS", "Mutual Funds",
            "Parag Parikh Flexi Cap Direct Growth", "CAMS · Groww", "Equity Flexi-Cap · Active SIP ₹15,000/mo",
            bd("255000.00"), bd("318400.00"), bd("24.86"), bd("15000.00"),
            "Finvu AA · CAMS FIP", "Core compounding fund; low portfolio overlap."));
        holdings.add(new PortfolioHolding(
            "pf-mf-nifty", "MUTUAL_FUNDS", "Mutual Funds",
            "UTI Nifty 50 Index Direct Growth", "KFintech · Groww", "Large-Cap Index · Expense 0.18% · SIP ₹10,000/mo",
            bd("185000.00"), bd("216600.00"), bd("17.08"), bd("10000.00"),
            "Finvu AA · KFintech FIP", "Low-cost index anchor."));
        holdings.add(new PortfolioHolding(
            "pf-mf-mid", "MUTUAL_FUNDS", "Mutual Funds",
            "Motilal Oswal Midcap Direct Growth", "CAMS · Groww", "Mid-Cap Growth · SIP ₹7,500/mo",
            bd("92000.00"), bd("110000.00"), bd("19.57"), bd("7500.00"),
            "Finvu AA · CAMS FIP", "High-beta satellite allocation (17% of MF)."));
        holdings.add(new PortfolioHolding(
            "pf-eq-niftybees", "STOCKS_ETFS", "Stocks & ETFs",
            "NIPPON INDIA ETF NIFTY 50 BeES", "NSDL Demat · Zerodha", "480 units · NSE: NIFTYBEES",
            bd("108000.00"), bd("126400.00"), bd("17.04"), bd("0.00"),
            "Finvu AA · NSDL Demat", "Zero tracking-error ETF liquidity."));
        holdings.add(new PortfolioHolding(
            "pf-eq-icici", "STOCKS_ETFS", "Stocks & ETFs",
            "ICICI Bank Ltd & TCS Ltd Basket", "NSDL Demat · Zerodha", "Bluechip Direct Equity · 2 Stocks",
            bd("162000.00"), bd("186000.00"), bd("14.81"), bd("0.00"),
            "Finvu AA · NSDL Demat", "Dividend + steady large-cap compounder."));
        holdings.add(new PortfolioHolding(
            "pf-fd-idfc", "FIXED_DEPOSITS", "Fixed Deposits",
            "IDFC FIRST Bank 1-YrCallable FD", "IDFC FIRST ••9101", "7.25% p.a. · Matures 14 Aug 2027",
            bd("142000.00"), bd("150000.00"), bd("7.25"), bd("0.00"),
            "Finvu AA · TERM_DEPOSIT", "Emergency liquid reserve; instant premature sweep enabled."));
        holdings.add(new PortfolioHolding(
            "pf-nps-1", "NPS", "NPS",
            "HDFC Pension Fund — Tier I (LC75)", "Protean CRA · PRAN ••4910", "75% Equity / 25% Govt Bonds · Sec 80CCD(1B)",
            bd("95000.00"), bd("110000.00"), bd("15.79"), bd("5000.00"),
            "Finvu AA · NPS CRA", "Tax-efficient retirement bucket."));
        holdings.add(new PortfolioHolding(
            "pf-epf-1", "EPF", "EPF",
            "Sample EPF passbook", "EPFO India", "Employee and employer contributions",
            bd("132000.00"), bd("145000.00"), bd("8.25"), bd("6000.00"),
            "EPFO Sync", "Verify employer transfer upon company switch."));
        holdings.add(new PortfolioHolding(
            "pf-ppf-1", "PPF", "PPF",
            "Public Provident Fund (EEE Tax-Free)", "SBI PPF ••8820", "7.10% p.a. · Lock-in Tier",
            bd("110000.00"), bd("120000.00"), bd("7.10"), bd("4000.00"),
            "Bank Statement Sync", "AI Tip: Deposit before the 5th of every month to earn interest for that month."));
    }

    private void seedRecurringBills() {
        recurringBills.clear();
        recurringBills.add(new RecurringBillItem(
            "rec-cloud-ai", "Cloud AI Pro Plan", "Subscription", bd("1399.00"),
            "Monthly · 16th", "16 Dec 2026", "ICICI Amazon Pay ••8821", "ICICI",
            "FLAGGED_DUPLICATE", "Cancel now — overlaps with your existing workspace AI tools (saves ₹16,788/yr)."));
        recurringBills.add(new RecurringBillItem(
            "rec-cult", "Cult.fit Pro Gym", "Health & Fitness", bd("1299.00"),
            "Monthly · 18th", "18 Dec 2026", "ICICI Bank ••9102", "ICICI",
            "ACTIVE", "Switch monthly ₹1,299 mandate to Annual Elite pass to cut ~38% cost."));
        recurringBills.add(new RecurringBillItem(
            "rec-netflix", "Netflix India 4K", "Subscription", bd("649.00"),
            "Monthly · 19th", "19 Dec 2026", "ICICI Bank ••9102", "ICICI",
            "WATCH", "4-screen plan detected; downgrading to ₹199 Standard saves ₹5,400/yr if solo."));
        recurringBills.add(new RecurringBillItem(
            "rec-design", "Design Weekly", "Subscription", bd("510.00"),
            "Monthly · 20th", "20 Dec 2026", "IDFC FIRST ••9101", "IDFC",
            "WATCH", "Check usage before the next renewal."));
        recurringBills.add(new RecurringBillItem(
            "rec-airtel", "Airtel Postpaid Fiber", "Utilities & Bills", bd("500.00"),
            "Monthly · 19th", "19 Dec 2026", "IDFC FIRST ••9101", "IDFC",
            "ESSENTIAL", "Essential broadband/mobile utility — keep AutoPay active."));
        recurringBills.add(new RecurringBillItem(
            "rec-icloud", "Apple iCloud+ 200GB", "Cloud Storage", bd("199.00"),
            "Monthly · 20th", "20 Dec 2026", "ICICI Bank ••9102", "ICICI",
            "ACTIVE", "Keep active if device backup exceeds 50GB."));
        recurringBills.add(new RecurringBillItem(
            "rec-lic", "LIC Tech-Term Cover", "Insurance", bd("1599.99"),
            "Monthly · 23rd", "Mon, 23 Nov 2026", "IDFC FIRST ••9101", "IDFC",
            "ESSENTIAL", "Debits ₹1,599.99 Monday morning from IDFC ••9101."));
    }

    private void seedBillersSpacesAndPolicies() {
        payableBillers.clear();
        payableBillers.add(new PayableBiller(
            "bill-elec", "ELECTRICITY", "BESCOM Bengaluru Electricity", "Bharat Connect · BESCOM",
            "RR No. DEMO-ELEC-01", bd("1480.00"), "28 Nov 2026", false, true, "IDFC FIRST ••9101"));
        payableBillers.add(new PayableBiller(
            "bill-gas", "GAS", "Indane / GAIL Piped Gas", "Bharat Connect · GAIL Gas",
            "BP No. DEMO-GAS-02", bd("803.00"), "30 Nov 2026", false, false, "Not set"));
        payableBillers.add(new PayableBiller(
            "bill-water", "WATER", "BWSSB Cauvery Water Supply", "Bharat Connect · BWSSB",
            "Conn ID DEMO-WATER-03", bd("420.00"), "02 Dec 2026", false, false, "Not set"));
        payableBillers.add(new PayableBiller(
            "bill-fastag", "FASTAG", "ICICI Bank FASTag Recharge", "NPCI NETC FASTag",
            "Vehicle DEMO-TAG-04 (Low Bal: ₹180)", bd("500.00"), "Instant Top-up", false, true, "ICICI Bank ••9102"));
        payableBillers.add(new PayableBiller(
            "bill-cc-hdfc", "CREDIT_CARD", "HDFC Regalia Gold ••4412", "Bharat Connect · HDFC Cards",
            "Card ••4412 (Wed AutoPay)", bd("11000.00"), "Wed, 25 Nov 2026", false, true, "IDFC FIRST ••9101"));

        insurancePolicies.clear();
        insurancePolicies.add(new InsurancePolicy(
            "pol-term", "Term Life", "LIC of India", "Tech-Term Pure Protection",
            "DEMO-POL-01", bd("10000000.00"), bd("1599.99"), "Monthly", "Mon, 23 Nov 2026",
            "IDFC FIRST ••9101",
            List.of("Pure risk cover (zero mixing with low-yield endowment) ✓", "Nominee 100% registered ✓", "Auto-debit active on IDFC ••9101")));
        insurancePolicies.add(new InsurancePolicy(
            "pol-health", "Health", "HDFC ERGO", "Optima Secure Individual + Family",
            "DEMO-POL-02", bd("1500000.00"), bd("14800.00"), "Annual", "14 Mar 2027",
            "HDFC Regalia ••4412",
            List.of("No room-rent cap ✓", "Zero co-pay across network hospitals ✓", "2X Secure + 100% Restore benefit active ✓")));
        insurancePolicies.add(new InsurancePolicy(
            "pol-motor", "Motor", "ICICI Lombard", "Zero-Dep Comprehensive Cover",
            "DEMO-POL-03", bd("850000.00"), bd("9200.00"), "Annual", "09 Aug 2027",
            "ICICI Bank ••9102",
            List.of("35% No-Claim Bonus protected ✓", "Engine + Roadside Assistance included ✓")));

        sharedSpaces.clear();
        sharedSpaces.add(new SharedSpace(
            "space-family", "Household bills", "A local collection for household expenses.",
            "indigo", List.of("You", "Member A", "Member B"),
            bd("22403.00"),
            List.of(
                new SpaceExpense("Electricity & broadband", "You", "Utilities", "17 Nov", bd("2000.00")),
                new SpaceExpense("Family policy reserve", "You", "Insurance", "10 Nov", bd("14800.00")),
                new SpaceExpense("Home supplies", "Member A", "Home", "19 Nov", bd("5603.00"))
            )));
        sharedSpaces.add(new SharedSpace(
            "space-trip", "Jaipur & Winter Travel Pool", "Flights, cabs & shared bookings",
            "emerald", List.of("You", "Travel partner"),
            bd("24550.00"),
            List.of(
                new SpaceExpense("IndiGo round trip", "You", "Flights", "04 Oct", bd("24500.00")),
                new SpaceExpense("Airport tea / UPI split", "Travel partner", "Food", "21 Nov", bd("50.00"))
            )));
    }

    public synchronized DashboardResponse syncGmailAlerts() {
        syncGmailAlertsInternal();
        return buildDashboard();
    }

    private void syncGmailAlertsInternal() {
        if (gmailSynced) return; // A fixture replay must not overwrite subsequent payments or imports.
        // Ensure bank shells exist even if user clicked Gmail Sync before Finvu AA
        banks.putIfAbsent("bank-idfc-9101", new MutableBank(
            "bank-idfc-9101", "IDFC FIRST Bank", "••9101", "IDFB0080102", "Autopay & Bills Hub",
            "11050.56", "10599.99", "3.00", false, "Created from a sample alert"));
        banks.putIfAbsent("bank-icici-9102", new MutableBank(
            "bank-icici-9102", "ICICI Bank", "••9102", "ICIC0003168", "Daily UPI & Lifestyle",
            "15149.00", null, "3.00", false, "Created from Gmail alert (user opening bal)"));
        banks.putIfAbsent("bank-uco-9103", new MutableBank(
            "bank-uco-9103", "UCO Bank", "••9103", "UCBA0009103", "Savings & Salary Reserve",
            "184536.33", "184556.33", "2.75", false, "Created from Gmail alert"));

        // Latest bank email alerts (exact real Indian formats!)
        addTxnIfMissing(new TransactionItem(
            "gm-idfc-1", "21 Nov 2026, 11:10 AM", "bank-idfc-9101",
            "IDFC FIRST Bank", "••9101", AccountKind.BANK, Direction.DEBIT,
            bd("450.57"), "Sample Market (UPI)", "Food & Groceries",
            "DEMOMARKET021", bd("10599.99"), DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "Sample: IDFC FIRST Bank A/C XX9101 debited by INR 450.57 on 21/11/2026 11:10 towards UPI/SampleMarket. New balance is INR 10,599.99CR.",
            false));
        banks.get("bank-idfc-9101").latestReportedBalance = bd("10599.99");
        banks.get("bank-idfc-9101").lastUpdatedNote = "Sample balance alert (21 Nov 11:10 AM)";

        addTxnIfMissing(new TransactionItem(
            "gm-icici-1", "19 Nov 2026, 08:15 PM", "bank-icici-9102",
            "ICICI Bank", "••9102", AccountKind.BANK, Direction.DEBIT,
            bd("649.00"), "Netflix India AutoPay", "Recurring / Subscription",
            "DEMOUPI001", null, DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "UPI AutoPay mandate of ₹649.00 executed for Netflix India from account XXXXXXXX9102.",
            true));
        addTxnIfMissing(new TransactionItem(
            "gm-icici-2", "20 Nov 2026, 09:05 AM", "bank-icici-9102",
            "ICICI Bank", "••9102", AccountKind.BANK, Direction.DEBIT,
            bd("199.00"), "Apple iCloud+ 200GB", "Recurring / Subscription",
            "DEMOUPI002", null, DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "UPI AutoPay of ₹199.00 debited from account XXXXXXXX9102 towards Apple Services.",
            true));
        addTxnIfMissing(new TransactionItem(
            "gm-icici-3", "20 Nov 2026, 09:42 PM", "bank-icici-9102",
            "ICICI Bank", "••9102", AccountKind.BANK, Direction.DEBIT,
            bd("246.00"), "Zepto Instant Grocery", "Food & Groceries",
            "DEMOUPI003", null, DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "Sample: UPI payment of ₹246.00 via iMobile to Zepto from account XX9102.",
            false));
        addTxnIfMissing(new TransactionItem(
            "gm-icici-4", "21 Nov 2026, 05:20 PM", "bank-icici-9102",
            "ICICI Bank", "••9102", AccountKind.BANK, Direction.DEBIT,
            bd("55.00"), "Sample Florist", "UPI / Peer Transfer",
            "DEMOUPI004", null, DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "Sample: UPI payment of ₹55.00 to Sample Florist from account XX9102 on Nov 21, 2026 at 05:20 PM. UPI Ref: DEMOUPI004.",
            false));
        banks.get("bank-icici-9102").latestReportedBalance = null; // ICICI UPI email omits balance -> computed from opening!
        banks.get("bank-icici-9102").lastUpdatedNote = "Email omits balance; computed from opening ₹" + banks.get("bank-icici-9102").openingBalance;

        addTxnIfMissing(new TransactionItem(
            "gm-uco-1", "21 Nov 2026, 01:05 PM", "bank-uco-9103",
            "UCO Bank", "••9103", AccountKind.BANK, Direction.CREDIT,
            bd("20.00"), "UCO-UPI Transfer", "UPI / Transfer In",
            "DEMOUCO021", bd("184556.33"), DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "Sample: UCO Bank A/c XX9103 credited with INR 20.00 (UPI). Available Balance INR 184556.33.",
            false));
        banks.get("bank-uco-9103").latestReportedBalance = bd("184556.33");
        banks.get("bank-uco-9103").lastUpdatedNote = "Gmail Avl Bal matches computed ledger ✓";

        // Credit Cards + Card Spend Alerts (Not available on RBI DEPOSIT AA FIPs -> pulled from Gmail!)
        cards.putIfAbsent("card-hdfc-4412", new MutableCard(
            "card-hdfc-4412", "HDFC Bank", "HDFC Regalia Gold", "••4412",
            "11000.00", "550.00", "Wed, 25 Nov 2026",
            "bank-idfc-9101", "IDFC FIRST Bank ••9101",
            "Full-due AutoPay (₹11,000.00) scheduled from IDFC ••9101 on Wed 25 Nov",
            "HDFC Bank Credit Card XX4412 Statement: Total Amount Due INR 11,000.00, Minimum Amount Due INR 550.00. Auto-debit on 25-Nov-2026."));

        cards.putIfAbsent("card-icici-8821", new MutableCard(
            "card-icici-8821", "ICICI Bank", "Amazon Pay ICICI Card", "••8821",
            "4250.00", "220.00", "Fri, 27 Nov 2026",
            "bank-icici-9102", "ICICI Bank ••9102",
            "Full-due AutoPay (₹4,250.00) scheduled from ICICI ••9102 on Fri 27 Nov",
            "Amazon Pay ICICI Credit Card XX8821 Statement: Total Due ₹4,250.00 on 27-Nov-2026 via ICICI A/c XX9102 AutoPay."));

        addTxnIfMissing(new TransactionItem(
            "cc-hdfc-1", "12 Nov 2026, 04:20 PM", "card-hdfc-4412",
            "HDFC Regalia CC", "••4412", AccountKind.CREDIT_CARD, Direction.DEBIT,
            bd("8000.00"), "Croma Electronics — Mobile", "Shopping & Electronics",
            "HDFCCC88101", null, DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "Alert: INR 8,000.00 spent on HDFC Bank Credit Card XX4412 at CROMA ELECTRONICS on 12-11-2026.",
            false));
        addTxnIfMissing(new TransactionItem(
            "cc-hdfc-2", "14 Nov 2026, 07:50 PM", "card-hdfc-4412",
            "HDFC Regalia CC", "••4412", AccountKind.CREDIT_CARD, Direction.DEBIT,
            bd("3000.00"), "Myntra — Running Shoes", "Shopping & Electronics",
            "HDFCCC88459", null, DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "Alert: INR 3,000.00 spent on HDFC Bank Credit Card XX4412 at MYNTRA DESIGNS on 14-11-2026.",
            false));
        addTxnIfMissing(new TransactionItem(
            "cc-icici-1", "13 Nov 2026, 01:12 PM", "card-icici-8821",
            "ICICI Amazon Pay CC", "••8821", AccountKind.CREDIT_CARD, Direction.DEBIT,
            bd("2851.00"), "Amazon.in — Keychron Keyboard", "Shopping & Electronics",
            "ICICICC4019", null, DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "INR 2,851.00 spent on ICICI Bank Credit Card XX8821 at AMAZON SELLER SERVICES.",
            false));
        addTxnIfMissing(new TransactionItem(
            "cc-icici-2", "16 Nov 2026, 06:00 AM", "card-icici-8821",
            "ICICI Amazon Pay CC", "••8821", AccountKind.CREDIT_CARD, Direction.DEBIT,
            bd("1399.00"), "Cloud AI Pro Plan (Duplicate Sub)", "Recurring / Subscription",
            "ICICICC4580", null, DataSource.GMAIL_ALERT, "AI_INTENT_ENGINE",
            "Standing Instruction: INR 1,399.00 charged on ICICI Credit Card XX8821 at CLOUD-AI-PRO.",
            true));

        this.gmailSynced = true;
        this.gmailMessagesScanned = 12;
        this.duplicatesIgnored = Math.max(this.duplicatesIgnored, 1); // Ignored duplicate HDFC reminder email
        this.gmailSyncedAt = nowLabel();
        activityFeed.add(0, "Gmail Sync scanned 12 bank/card alert emails: extracted 9 txns + 2 card bills, deduped 1 repeat HDFC reminder.");
    }

    public synchronized DashboardResponse updateOpeningBalance(OpeningBalanceRequest req) {
        if (req == null || req.accountId() == null || req.openingBalance() == null) {
            throw new IllegalArgumentException("Account ID and valid opening balance are required.");
        }
        MutableBank bank = banks.get(req.accountId());
        if (bank == null) {
            throw new IllegalArgumentException("Unknown bank account: " + req.accountId());
        }
        bank.openingBalance = scale2(req.openingBalance());
        bank.lastUpdatedNote = "Opening balance updated to ₹" + bank.openingBalance;
        activityFeed.add(0, "Updated " + bank.bankName + " " + bank.maskedNumber + " opening balance to ₹" + bank.openingBalance + ".");
        return buildDashboard();
    }

    public synchronized ParseNoticeResponse ingestNotice(ParseNoticeRequest req) {
        if (req == null || req.rawText() == null || req.rawText().isBlank()) {
            throw new IllegalArgumentException("Paste a bank or credit card email/SMS to analyze.");
        }
        ParsedNotice parsed = parser.parse(req.sender(), req.rawText(), req.preferLiveAi());
        BigDecimal amt = scale2(parsed.amount());
        if (amt == null || amt.signum() < 0 || amt.signum() == 0 && parsed.intent() != NoticeIntent.ISSUER_REVISED_DEBIT)
            throw new IllegalArgumentException("This notice has no usable transaction amount.");

        // Handle Intent 1: Merchant Refund Promise (does NOT lower Wednesday's auto-debit!)
        if (parsed.intent() == NoticeIntent.MERCHANT_REFUND_PROMISE) {
            MutableCard card = findMatchingCard(parsed.maskedNumber(), null);
            String msg = "Merchant expects a ₹" + amt + " refund. The scheduled debit for " + card.cardName
                + " remains ₹" + card.effectiveAutopayDue + ".";
            card.pendingMerchantRefund = amt;
            card.statusNote = msg;
            card.evidenceQuote = parsed.evidenceQuote();
            activityFeed.add(0, msg);
            return new ParseNoticeResponse(parsed, false, msg, buildDashboard());
        }

        // Handle Intent 2: Card Issuer Confirmed Refund & Revised Auto-Debit
        if (parsed.intent() == NoticeIntent.ISSUER_REVISED_DEBIT) {
            MutableCard targetCard = findMatchingCard(parsed.maskedNumber(), parsed.institution());
            if (targetCard.effectiveAutopayDue.signum()==0 && payableBillers.stream().anyMatch(b -> b.id().equals("bill-cc-hdfc")
                    && targetCard.id.equals("card-hdfc-4412") && b.paid()))
                throw new IllegalArgumentException("That statement was settled in the demo. The notice did not reopen it.");
            if (targetCard != null) {
                BigDecimal creditDelta = targetCard.effectiveAutopayDue.subtract(amt);
                targetCard.effectiveAutopayDue = amt;
                targetCard.pendingMerchantRefund = bd("0.00");
                targetCard.statusNote = "Issuer confirmed refund & revised scheduled auto-debit to ₹" + amt + " ✓";
                targetCard.evidenceQuote = parsed.evidenceQuote();
                if (creditDelta.compareTo(BigDecimal.ZERO) > 0) {
                    addTxnIfMissing(new TransactionItem(
                        "ai-cc-adj-" + UUID.randomUUID().toString().substring(0, 6),
                        parsed.timestamp(),
                        targetCard.id,
                        targetCard.issuerName + " CC",
                        targetCard.maskedNumber,
                        AccountKind.CREDIT_CARD,
                        Direction.CREDIT,
                        scale2(creditDelta),
                        "Refund Adjusted on Card",
                        "Refund Credit",
                        parsed.referenceId(),
                        null,
                        DataSource.AI_PASTE,
                        parsed.parserUsed(),
                        parsed.evidenceQuote(),
                        false));
                }
            }
            String msg = "AI classified as ISSUER_REVISED_DEBIT: updated card scheduled auto-debit to ₹" + amt + " and recalculated shortfall!";
            activityFeed.add(0, msg);
            return new ParseNoticeResponse(parsed, false, msg, buildDashboard());
        }

        // Handle Intent 3: Credit Card Bill Summary (dedupe if same due already tracked)
        if (parsed.intent() == NoticeIntent.CARD_BILL_SUMMARY) {
            MutableCard card = findMatchingCard(parsed.maskedNumber(), parsed.institution());
            if (card != null && card.statementTotalDue.compareTo(amt) == 0) {
                duplicatesIgnored++;
                String msg = "AI recognized a duplicate reminder for the same " + card.cardName + " bill (₹" + amt + ") — ignored so due isn't double-counted.";
                activityFeed.add(0, msg);
                return new ParseNoticeResponse(parsed, true, msg, buildDashboard());
            }
            card.statementTotalDue = amt;
            card.effectiveAutopayDue = amt;
            card.evidenceQuote = parsed.evidenceQuote();
            card.statusNote = "Updated full-payment statement: ₹" + amt + ".";
            return new ParseNoticeResponse(parsed, false, card.statusNote, buildDashboard());
        }

        // Deduplicate by referenceId or exact signature
        if (isDuplicateTransaction(parsed, amt)) {
            duplicatesIgnored++;
            String msg = "Duplicate transaction alert ignored (Ref " + (parsed.referenceId().isBlank() ? "match" : parsed.referenceId()) + " already in ledger).";
            activityFeed.add(0, msg);
            return new ParseNoticeResponse(parsed, true, msg, buildDashboard());
        }

        // Route to matching Bank Account or Credit Card (or create a dynamic one if brand-new bank!)
        String targetAccountId;
        if (parsed.accountKind() == AccountKind.CREDIT_CARD) {
            MutableCard card = findOrCreateCard(parsed);
            targetAccountId = card.id;
            // A new swipe belongs to the ledger, not an already issued statement.
        } else {
            MutableBank bank = findOrCreateBank(parsed);
            targetAccountId = bank.id;
            LocalDate date = LedgerDates.date(parsed.timestamp());
            if (parsed.reportedBalance() != null && (bank.latestReportedBalance == null || !date.isBefore(bank.reportDay))) {
                bank.latestReportedBalance = scale2(parsed.reportedBalance());
                bank.reportDay = date;
                bank.netAfterReport = transactions.stream().filter(t -> t.accountId().equals(bank.id) && LedgerDates.date(t.timestamp()).isAfter(date))
                    .map(t -> t.direction()==Direction.CREDIT ? t.amount() : t.amount().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
                bank.lastUpdatedNote = "Updated via AI Paste (Bank reported ₹" + bank.latestReportedBalance + ")";
            } else {
                if (bank.latestReportedBalance != null && date.isAfter(bank.reportDay))
                    bank.netAfterReport = bank.netAfterReport.add(parsed.direction()==Direction.CREDIT ? amt : amt.negate());
                bank.lastUpdatedNote = "Updated via AI Paste (Computed in Java)";
            }
        }

        TransactionItem item = new TransactionItem(
            "ai-" + UUID.randomUUID().toString().substring(0, 7),
            parsed.timestamp(),
            targetAccountId,
            parsed.institution(),
            parsed.maskedNumber(),
            parsed.accountKind(),
            parsed.direction(),
            amt,
            parsed.counterparty(),
            parsed.category(),
            parsed.referenceId(),
            scale2(parsed.reportedBalance()),
            DataSource.AI_PASTE,
            parsed.parserUsed(),
            parsed.evidenceQuote(),
            parsed.recurring()
        );
        transactions.add(0, item);
        String msg = "Added " + parsed.direction() + " ₹" + amt + " on " + parsed.institution() + " " + parsed.maskedNumber()
            + " via " + parsed.parserUsed() + ".";
        activityFeed.add(0, msg);
        return new ParseNoticeResponse(parsed, false, msg, buildDashboard());
    }

    private boolean isDuplicateTransaction(ParsedNotice parsed, BigDecimal amt) {
        for (TransactionItem existing : transactions) {
            if (!existing.maskedNumber().equals(parsed.maskedNumber()) || existing.accountKind()!=parsed.accountKind()
                    || existing.direction()!=parsed.direction() || existing.amount().compareTo(amt)!=0) continue;
            if (!parsed.referenceId().isBlank() && !existing.referenceId().isBlank()) {
                if (parsed.referenceId().equalsIgnoreCase(existing.referenceId())) return true;
            } else if (existing.evidenceQuote().equals(parsed.evidenceQuote())) return true;
        }
        return false;
    }

    private MutableCard findMatchingCard(String masked, String institution) {
        var matches = cards.values().stream().filter(c -> c.maskedNumber.equals(masked))
            .filter(c -> institution == null || institution.toLowerCase(Locale.ROOT)
                .contains(c.issuerName.toLowerCase(Locale.ROOT).split(" ")[0])).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Cannot match this notice to exactly one tracked card. No card was changed.");
        return matches.get(0);
    }

    private MutableCard findOrCreateCard(ParsedNotice p) {
        for (MutableCard c : cards.values()) {
            if (c.maskedNumber.equals(p.maskedNumber())) return c;
        }
        String id = "card-dyn-" + p.maskedNumber().replace("•", "");
        String defaultBankId = banks.keySet().stream().findFirst().orElse("bank-idfc-9101");
        MutableCard created = new MutableCard(
            id, p.institution(), p.institution() + " Card", p.maskedNumber(),
            "0.00", "0.00", "Monthly Cycle", defaultBankId, "Linked Bank",
            "Added dynamically from pasted alert", p.evidenceQuote());
        cards.put(id, created);
        return created;
    }

    private MutableBank findOrCreateBank(ParsedNotice p) {
        if (p.maskedNumber().equals("••0000") || p.institution().startsWith("Unknown"))
            throw new IllegalArgumentException("Include the bank and a masked account ending. No account was changed.");
        for (MutableBank b : banks.values()) {
            if (b.maskedNumber.equals(p.maskedNumber()) && b.bankName.toLowerCase(Locale.ROOT).split(" ")[0]
                .equals(p.institution().toLowerCase(Locale.ROOT).split(" ")[0])) return b;
        }
        String id = "bank-dyn-" + UUID.randomUUID();
        String initial = p.reportedBalance() == null ? "0.00" : p.reportedBalance()
            .add(p.direction()==Direction.DEBIT ? p.amount() : p.amount().negate()).toPlainString();
        MutableBank created = new MutableBank(
            id, p.institution(), p.maskedNumber(), "BANK0000001", "External Bank Account",
            initial, null, "0.00", false,
            "Created dynamically from pasted alert");
        banks.put(id, created);
        return created;
    }

    private void addTxnIfMissing(TransactionItem item) {
        boolean exists = transactions.stream().anyMatch(t -> t.id().equals(item.id()));
        if (!exists) {
            transactions.add(item);
        }
    }

    public synchronized DashboardResponse buildDashboard() {
        // 1. Compute bank balances in exact BigDecimal arithmetic
        List<BankAccount> bankSnapshots = new ArrayList<>();
        Map<String, BankAccount> bankMap = new LinkedHashMap<>();
        BigDecimal totalBankBalance = bd("0.00");
        BigDecimal idleSavingsCash = bd("0.00");

        for (MutableBank mb : banks.values()) {
            BigDecimal credits = bd("0.00");
            BigDecimal debits = bd("0.00");
            for (TransactionItem t : transactions) {
                if (t.accountId().equals(mb.id) && t.accountKind() == AccountKind.BANK) {
                    if (t.direction() == Direction.CREDIT) {
                        credits = credits.add(t.amount());
                    } else {
                        debits = debits.add(t.amount());
                    }
                }
            }
            BigDecimal computed = scale2(mb.openingBalance.add(credits).subtract(debits));
            BigDecimal reported = mb.latestReportedBalance;
            BigDecimal effective = reported != null ? scale2(reported.add(mb.netAfterReport)) : computed;
            BigDecimal gap = scale2(effective.subtract(computed));
            boolean reconciled = reported == null || gap.compareTo(BigDecimal.ZERO) == 0;
            // Effective balance uses bank-reported balance when present (safest for shortfall checks), else Java computed balance

            BankAccount snap = new BankAccount(
                mb.id, mb.bankName, mb.maskedNumber, mb.ifscCode, mb.accountRole,
                mb.openingBalance, scale2(credits), scale2(debits),
                computed, reported, effective, gap, reconciled,
                mb.interestRatePct, mb.aaLinked, mb.lastUpdatedNote
            );
            bankSnapshots.add(snap);
            bankMap.put(mb.id, snap);
            totalBankBalance = totalBankBalance.add(effective);

            if (mb.id.equals("bank-uco-9103")) {
                idleSavingsCash = effective.subtract(bd("34556.33")).max(BigDecimal.ZERO);
            }
        }

        // 2. Compute upcoming scheduled obligations per bank account (sequential cashflow projection!)
        List<ScheduledObligation> obligations = new ArrayList<>();
        Map<String, BigDecimal> projectedAccountBalance = new LinkedHashMap<>();
        for (BankAccount b : bankSnapshots) {
            projectedAccountBalance.put(b.id(), b.effectiveBalance());
        }

        record Debit(String id, String bank, LocalDate date, BigDecimal amount) {}
        var planned = new ArrayList<Debit>();
        boolean premiumDue = projectedAccountBalance.containsKey("bank-idfc-9101")
            && recurringBills.stream().anyMatch(r -> r.id().equals("rec-lic") && !r.status().equals("CANCELLED"));
        if (premiumDue) planned.add(new Debit("ob-lic-mon","bank-idfc-9101",LocalDate.of(2026,11,23),bd("1599.99")));
        for (var card : cards.values()) if (card.effectiveAutopayDue.signum() > 0 && bankMap.containsKey(card.autopayBankAccountId))
            planned.add(new Debit("ob-"+card.id,card.autopayBankAccountId,LedgerDates.date(card.dueDate),card.effectiveAutopayDue));
        planned.sort(Comparator.comparing(Debit::date));
        Map<String,BigDecimal> beforeDebit = new LinkedHashMap<>();
        for (var debit : planned) {
            BigDecimal before = projectedAccountBalance.get(debit.bank());
            beforeDebit.put(debit.id(),before);
            projectedAccountBalance.put(debit.bank(),before.subtract(debit.amount()));
        }
        if (premiumDue) {
            BigDecimal before = beforeDebit.get("ob-lic-mon");
            BigDecimal licAmt = bd("1599.99");
            BigDecimal after = scale2(before.subtract(licAmt));
            BigDecimal shortAmt = after.compareTo(BigDecimal.ZERO) < 0 ? after.abs() : bd("0.00");
            obligations.add(new ScheduledObligation(
                "ob-lic-mon",
                "LIC Term Insurance Standing Instruction",
                "Mon, 23 Nov 2026",
                "bank-idfc-9101",
                "IDFC FIRST Bank ••9101",
                null,
                licAmt,
                licAmt,
                before,
                after,
                shortAmt,
                shortAmt.compareTo(BigDecimal.ZERO) > 0 ? "SHORTFALL" : "COVERED",
                "Debits ₹" + licAmt + " on Monday morning, leaving ₹" + after + " in IDFC ••9101 before Wednesday's card autopay.",
                shortAmt.compareTo(BigDecimal.ZERO) > 0 ? "Top up IDFC ••9101 by ₹" + shortAmt : "Covered by current IDFC balance"
            ));
        }

        // Credit Card AutoPays per linked bank account
        List<CreditCardAccount> cardSnapshots = new ArrayList<>();
        BigDecimal totalCardDue = bd("0.00");
        BigDecimal totalShortfall = bd("0.00");

        for (MutableCard mc : cards.values().stream().sorted(Comparator.comparing(c ->
                c.dueDate.equals("Monthly Cycle") ? LocalDate.MAX : LedgerDates.date(c.dueDate))).toList()) {
            totalCardDue = totalCardDue.add(mc.effectiveAutopayDue);
            BigDecimal before = beforeDebit.getOrDefault("ob-"+mc.id, bd("0.00"));
            BigDecimal after = scale2(before.subtract(mc.effectiveAutopayDue));
            BigDecimal shortAmt = after.compareTo(BigDecimal.ZERO) < 0 ? scale2(after.abs()) : bd("0.00");

            String cardAutopayStatus = shortAmt.compareTo(BigDecimal.ZERO) > 0 ? "SHORTFALL" : "COVERED";
            String explanation;
            String actionText;
            if (shortAmt.compareTo(BigDecimal.ZERO) > 0) {
                explanation = mc.autopayBankLabel + " will have ₹" + before + " before " + mc.dueDate
                    + ", but " + mc.cardName + " AutoPay will try to pull ₹" + mc.effectiveAutopayDue + "."
                    + (mc.pendingMerchantRefund.compareTo(BigDecimal.ZERO) > 0
                        ? " (Shop's ₹" + mc.pendingMerchantRefund + " refund is NOT credited yet!)"
                        : "");
                actionText = "Move ₹" + shortAmt + " from UCO ••9103 or ICICI ••9102 into " + mc.autopayBankLabel + " before " + mc.dueDate + ".";
            } else {
                explanation = mc.autopayBankLabel + " has ₹" + before + " available for ₹" + mc.effectiveAutopayDue
                    + " AutoPay — leaves ₹" + after + " buffer.";
                actionText = "No transfer needed — AutoPay is fully covered.";
            }

            if (beforeDebit.containsKey("ob-"+mc.id)) obligations.add(new ScheduledObligation(
                "ob-" + mc.id,
                mc.cardName + " (" + mc.maskedNumber + ") Full-Due AutoPay",
                mc.dueDate,
                mc.autopayBankAccountId,
                mc.autopayBankLabel,
                mc.id,
                mc.effectiveAutopayDue,
                mc.statementTotalDue,
                before,
                after,
                shortAmt,
                cardAutopayStatus,
                explanation,
                actionText
            ));

            cardSnapshots.add(new CreditCardAccount(
                mc.id, mc.issuerName, mc.cardName, mc.maskedNumber,
                mc.statementTotalDue, mc.minimumDue, mc.effectiveAutopayDue,
                mc.dueDate, mc.autopayBankAccountId, mc.autopayBankLabel,
                cardAutopayStatus, mc.pendingMerchantRefund, mc.statusNote, mc.evidenceQuote
            ));
        }

        // A shortage carried from one debit into the next is funded once per account.
        obligations.sort(Comparator.comparing(o -> LedgerDates.date(o.dueDate())));
        totalShortfall = planned.stream().map(Debit::bank).distinct().map(projectedAccountBalance::get).map(b -> b.negate().max(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MoneyNudge> nudges = new ArrayList<>();

        for (ScheduledObligation ob : obligations) {
            if ("SHORTFALL".equals(ob.status())) {
                nudges.add(new MoneyNudge(
                    "nudge-" + ob.id(),
                    "CRITICAL",
                    "AUTOPAY_SHORTFALL",
                    ob.title() + " is projected to be ₹" + ob.shortfallAmount() + " short on " + ob.dueDate(),
                    ob.explanation() + " " + ob.actionText(),
                    ob.shortfallAmount(),
                    "Forecast from the linked account balance and the scheduled debit; not a bank instruction."
                ));
            }
        }

        for (BankAccount b : bankSnapshots) {
            if (!b.reconciled()) {
                nudges.add(new MoneyNudge(
                    "nudge-gap-" + b.id(),
                    "WARNING",
                    "MISSING_TXN_GAP",
                    "Balance difference on " + b.bankName() + " " + b.maskedNumber() + ": ₹" + b.reconciliationGap().abs(),
                    "Ledger Opening + Credits − Debits = ₹" + b.computedBalance()
                        + ", but latest bank alert states ₹" + b.reportedBalance()
                        + ". Check the opening balance and any missing transactions.",
                    b.reconciliationGap().abs(),
                    "The difference alone does not identify a missing transaction."
                ));
            }
        }

        var monthly = recurringBills.stream().filter(r -> !r.status().equals("CANCELLED")
            && r.cadence().toLowerCase(Locale.ROOT).startsWith("monthly")).toList();
        BigDecimal recurringTotal = monthly.stream().map(RecurringBillItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (recurringTotal.compareTo(BigDecimal.ZERO) > 0) {
            nudges.add(new MoneyNudge(
                "nudge-subs",
                "WARNING",
                "SUBSCRIPTION_LEAK",
                "₹" + recurringTotal + " in monthly recurring commitments",
                monthly.size() + " tracked charges. Review the services you still use before the next renewal.",
                recurringTotal, "Payments show cost, not how often you use a service."
            ));
        }

        if (idleSavingsCash.compareTo(BigDecimal.ZERO) > 0) {
            nudges.add(new MoneyNudge(
                "nudge-idle-cash",
                "OPPORTUNITY",
                "IDLE_CASH",
                "₹" + scale2(idleSavingsCash) + " sitting idle in UCO Bank ••9103 at 2.75% p.a.",
                "Illustrative surplus after the demo reserve of ₹34,556.33. Keep money for upcoming bills accessible.",
                idleSavingsCash, "A simulated transfer is not an investment recommendation."
            ));
        }

        // 4. Portfolio aggregation
        BigDecimal pfTotalVal = bd("0.00");
        BigDecimal pfTotalInv = bd("0.00");
        BigDecimal pfMonthly = bd("0.00");
        for (PortfolioHolding h : holdings) {
            pfTotalVal = pfTotalVal.add(h.currentValue());
            pfTotalInv = pfTotalInv.add(h.investedAmount());
            pfMonthly = pfMonthly.add(h.monthlyContribution());
        }
        BigDecimal pfGain = scale2(pfTotalVal.subtract(pfTotalInv));
        BigDecimal pfRetPct = pfTotalInv.compareTo(BigDecimal.ZERO) > 0
            ? pfGain.multiply(new BigDecimal("100")).divide(pfTotalInv, 2, RoundingMode.HALF_UP)
            : bd("0.00");
        PortfolioSummary portfolioSummary = new PortfolioSummary(
            scale2(pfTotalVal), scale2(pfTotalInv), pfGain, pfRetPct, scale2(pfMonthly), List.copyOf(holdings)
        );

        // Cashflow totals for current period
        BigDecimal monthIncoming = bd("0.00");
        BigDecimal monthOutgoing = bd("0.00");
        for (TransactionItem t : transactions) {
            if (!YearMonth.from(LedgerDates.date(t.timestamp())).equals(LedgerDates.MONTH)
                    || internalTransfer(t)) continue;
            if (t.direction() == Direction.CREDIT) monthIncoming = monthIncoming.add(t.amount());
            else monthOutgoing = monthOutgoing.add(t.amount());
        }

        BigDecimal netLiquidAfterDues = scale2(totalBankBalance.subtract(totalCardDue));
        BigDecimal netWorth = scale2(netLiquidAfterDues.add(pfTotalVal));

        SummaryMetrics metrics = new SummaryMetrics(
            scale2(totalBankBalance),
            scale2(pfTotalVal),
            netWorth,
            scale2(monthIncoming),
            scale2(monthOutgoing),
            scale2(pfMonthly),
            scale2(totalCardDue),
            netLiquidAfterDues,
            scale2(totalShortfall),
            scale2(recurringTotal),
            scale2(idleSavingsCash),
            bankSnapshots.size(),
            cardSnapshots.size(),
            transactions.size()
        );

        CreditReportSummary creditReport = new CreditReportSummary(
            786,
            "Excellent",
            bd("14.20"),
            bd("750000.00"),
            100,
            cardSnapshots.size(),
            totalShortfall.compareTo(BigDecimal.ZERO) > 0
                ? "Top up IDFC ••9101 by ₹" + scale2(totalShortfall) + " before Wed 25 Nov so an AutoPay bounce never dents your 786 CIBIL score."
                : "Utilization is 14.2% (well below the 30% CIBIL threshold) and all card AutoPays are funded."
        );

        FinvuStatus finvuStatus = new FinvuStatus(
            finvuConnected,
            finvuHandle,
            "Sample account aggregator (offline)",
            finvuConsentId,
            "Code 101 — Wealth, Cashflow & Shortfall Protection (Read-Only)",
            List.of("DEPOSIT", "TERM_DEPOSIT", "MUTUAL_FUNDS (CAMS/KFintech)", "EQUITIES (NSDL)", "NPS"),
            finvuLinkedBanks,
            finvuApprovedAt,
            "21 Dec 2026 (30-Day Revocable Consent)",
            "These bank, card and portfolio fixtures are simulated. No financial provider or mailbox was contacted."
        );

        GmailStatus gmailStatus = new GmailStatus(
            gmailSynced,
            "from:(idfcfirstbank.com OR icicibank.com OR ucobank.com OR hdfcbank.net OR myntra.com)",
            gmailMessagesScanned,
            duplicatesIgnored,
            gmailSyncedAt
        );

        // Keep Bharat Connect HDFC card biller amount synced with effectiveAutopayDue
        MutableCard hdfcRef = cards.get("card-hdfc-4412");
        if (hdfcRef != null) {
            for (int i = 0; i < payableBillers.size(); i++) {
                PayableBiller pb = payableBillers.get(i);
                if ("bill-cc-hdfc".equals(pb.id()) && !pb.paid()) {
                    payableBillers.set(i, new PayableBiller(
                        pb.id(), pb.billerType(), pb.title(), pb.provider(), pb.identifier(),
                        hdfcRef.effectiveAutopayDue, pb.dueDate(), pb.paid(), pb.autoPayEnabled(), pb.autopayAccountLabel()
                    ));
                }
            }
        }

        return new DashboardResponse(
            parser.isLiveAzureConfigured(),
            parser.aiModelLabel(),
            finvuStatus,
            gmailStatus,
            metrics,
            bankSnapshots,
            cardSnapshots,
            creditReport,
            portfolioSummary,
            List.copyOf(recurringBills),
            List.copyOf(payableBillers),
            List.copyOf(insurancePolicies),
            List.copyOf(sharedSpaces),
            obligations,
            nudges,
            List.copyOf(transactions),
            samplePresets(),
            List.copyOf(activityFeed)
        );
    }

    public synchronized DashboardResponse addPortfolioHolding(AddHoldingRequest req) {
        if (req == null || req.name() == null || req.name().isBlank() || req.currentValue() == null) {
            throw new IllegalArgumentException("Holding name and current value are required.");
        }
        String bucket = req.bucket() == null ? "MUTUAL_FUNDS" : req.bucket().trim().toUpperCase(Locale.ROOT);
        String label = switch (bucket) {
            case "STOCKS_ETFS" -> "Stocks & ETFs";
            case "FIXED_DEPOSITS" -> "Fixed Deposits";
            case "NPS" -> "NPS";
            case "EPF" -> "EPF";
            case "PPF" -> "PPF";
            case "MUTUAL_FUNDS" -> "Mutual Funds";
            default -> throw new IllegalArgumentException("Choose a supported asset class.");
        };
        BigDecimal inv = req.investedAmount() != null ? scale2(req.investedAmount()) : scale2(req.currentValue());
        BigDecimal cur = scale2(req.currentValue());
        if (cur.signum() < 0 || inv.signum() < 0 || req.monthlyContribution() != null && req.monthlyContribution().signum() < 0)
            throw new IllegalArgumentException("Holding values and contributions cannot be negative.");
        BigDecimal ret = inv.compareTo(BigDecimal.ZERO) > 0
            ? cur.subtract(inv).multiply(new BigDecimal("100")).divide(inv, 2, RoundingMode.HALF_UP)
            : bd("0");
        BigDecimal sip = req.monthlyContribution() != null ? scale2(req.monthlyContribution()) : bd("0.00");

        holdings.add(0, new PortfolioHolding(
            "pf-" + UUID.randomUUID().toString().substring(0, 6),
            bucket, label, req.name().trim(),
            req.provider() == null || req.provider().isBlank() ? "Manual / AA Sync" : req.provider().trim(),
            label + " · Added to Portfolio", inv, cur, ret, sip, "Live Added", "Tracked in unified net worth."
        ));

        activityFeed.add(0, "Added " + label + " holding: " + req.name() + " (₹" + cur + ").");
        return buildDashboard();
    }

    public synchronized DashboardResponse simulateSweep() {
        BigDecimal cur = buildDashboard().metrics().idleSavingsCash();
        if (cur.signum() <= 0) throw new IllegalArgumentException("No sample cash is available above the reserve.");
        addPortfolioHolding(new AddHoldingRequest("FIXED_DEPOSITS", "UCO sample sweep deposit", "UCO Bank", cur, cur, BigDecimal.ZERO));
        MutableBank uco = banks.get("bank-uco-9103");
        if (uco.latestReportedBalance != null) uco.netAfterReport = uco.netAfterReport.subtract(cur);
        transactions.add(0, new TransactionItem(
                "fd-sweep-" + UUID.randomUUID().toString().substring(0, 5),
                nowLabel(), "bank-uco-9103", "UCO Bank", "••9103",
                AccountKind.BANK, Direction.DEBIT, cur,
                "7.25% Liquid Auto-Sweep FD", "Investment / FD Sweep",
                "FDSWEEP01", null, DataSource.SIMULATION, "JAVA_LEDGER",
                "Sample transfer from savings to a deposit in the same workspace.", false
            ));
        return buildDashboard();
    }

    static boolean internalTransfer(TransactionItem t) {
        return t.category().equals("Card repayment") || t.category().equals("Investment / FD Sweep");
    }

    public synchronized DashboardResponse addRecurringBill(AddRecurringRequest req) {
        if (req == null || req.merchant() == null || req.merchant().isBlank() || req.amount() == null) {
            throw new IllegalArgumentException("Merchant name and amount are required.");
        }
        if (scale2(req.amount()).signum() <= 0) throw new IllegalArgumentException("The recurring amount must be positive.");
        recurringBills.add(0, new RecurringBillItem(
            "rec-" + UUID.randomUUID().toString().substring(0, 6),
            req.merchant().trim(),
            req.category() == null || req.category().isBlank() ? "Subscription" : req.category().trim(),
            scale2(req.amount()),
            req.cadence() == null || req.cadence().isBlank() ? "Monthly" : req.cadence().trim(),
            req.nextDueDate() == null || req.nextDueDate().isBlank() ? "Next Month" : req.nextDueDate().trim(),
            req.accountLabel() == null || req.accountLabel().isBlank() ? "ICICI Bank ••9102" : req.accountLabel().trim(),
            "ICICI",
            "ACTIVE",
            "User-scheduled recurring tracker active."
        ));
        activityFeed.add(0, "Added recurring tracker for " + req.merchant() + " (₹" + scale2(req.amount()) + ").");
        return buildDashboard();
    }

    public synchronized DashboardResponse toggleRecurringBill(ToggleRecurringRequest req) {
        if (req == null || req.id() == null) {
            throw new IllegalArgumentException("Recurring item ID is required.");
        }
        for (int i = 0; i < recurringBills.size(); i++) {
            RecurringBillItem item = recurringBills.get(i);
            if (item.id().equals(req.id())) {
                String nextStatus = "CANCEL".equalsIgnoreCase(req.action()) ? "CANCELLED" : "IGNORED";
                if (!"CANCEL".equalsIgnoreCase(req.action()) && !"IGNORE".equalsIgnoreCase(req.action()))
                    throw new IllegalArgumentException("Choose cancel or ignore.");
                recurringBills.set(i, new RecurringBillItem(
                    item.id(), item.merchant(), item.category(), item.amount(), item.cadence(),
                    item.nextDueDate(), item.accountLabel(), item.bankCode(), nextStatus,
                    "CANCELLED".equals(nextStatus) ? "Mandate marked cancelled — saves ₹" + scale2(item.amount().multiply(new BigDecimal("12"))) + "/yr." : "Ignored from leak alerts."
                ));
                activityFeed.add(0, item.merchant() + " recurring mandate marked " + nextStatus + ".");
                return buildDashboard();
            }
        }
        throw new IllegalArgumentException("That recurring item is not in this workspace.");
    }

    public synchronized DashboardResponse payBiller(PayBillRequest req) {
        if (req == null || req.billerId() == null) {
            throw new IllegalArgumentException("Select a biller to pay.");
        }
        String acctId = (req.bankAccountId() == null || req.bankAccountId().isBlank()) ? "bank-uco-9103" : req.bankAccountId();
        MutableBank bank = banks.get(acctId);
        if (bank == null) {
            throw new IllegalArgumentException("Selected bank account not found.");
        }
        for (int i = 0; i < payableBillers.size(); i++) {
            PayableBiller pb = payableBillers.get(i);
            if (pb.id().equals(req.billerId())) {
                if (pb.paid()) {
                    return buildDashboard();
                }
                BigDecimal amt = pb.dueAmount();
                if (amt.signum()<=0) throw new IllegalArgumentException("There is no outstanding amount to pay.");
                var available = buildDashboard().bankAccounts().stream().filter(b -> b.id().equals(acctId)).findFirst().orElseThrow().effectiveBalance();
                if (amt.compareTo(available) > 0)
                    throw new IllegalArgumentException("Not enough funds in that account. Choose a different sample account.");
                if (bank.latestReportedBalance != null) {
                    bank.netAfterReport = bank.netAfterReport.subtract(amt);
                }
                transactions.add(0, new TransactionItem(
                    "bbps-" + UUID.randomUUID().toString().substring(0, 6),
                    nowLabel(), bank.id, bank.bankName, bank.maskedNumber,
                    AccountKind.BANK, Direction.DEBIT, amt,
                    pb.title() + " (simulation)", "CREDIT_CARD".equals(pb.billerType()) ? "Card repayment" : "Utilities & Bills",
                    "BBPS" + System.currentTimeMillis() % 1000000,
                    null, DataSource.SIMULATION, "LOCAL_PREVIEW",
                    "Simulated payment from " + bank.bankName + " " + bank.maskedNumber + "; no funds moved.",
                    req.setupAutoPay()
                ));
                if ("bill-cc-hdfc".equals(pb.id()) && cards.containsKey("card-hdfc-4412")) {
                    MutableCard hdfc = cards.get("card-hdfc-4412");
                    hdfc.effectiveAutopayDue = bd("0.00");
                    hdfc.statusNote = "Settled in this simulation from " + bank.bankName + " " + bank.maskedNumber + ".";
                }
                payableBillers.set(i, new PayableBiller(
                    pb.id(), pb.billerType(), pb.title(), pb.provider(), pb.identifier(),
                    bd("0.00"), "Paid in demo", true, req.setupAutoPay(),
                    bank.bankName + " " + bank.maskedNumber
                ));
                if (req.setupAutoPay() && recurringBills.stream().noneMatch(r -> r.id().equals("track-"+pb.id()))) {
                    recurringBills.add(new RecurringBillItem("track-"+pb.id(),pb.title(),"Bill reminder",amt,
                        "Per bill", "Next bill",bank.bankName+" "+bank.maskedNumber,bank.bankName,"ACTIVE",
                        "Demo reminder only. The next amount may vary."));
                }
                activityFeed.add(0, "Paid " + pb.title() + " (₹" + amt + ") via Bharat Connect from " + bank.bankName + " " + bank.maskedNumber + ".");
                return buildDashboard();
            }
        }
        throw new IllegalArgumentException("That sample bill was not found.");
    }

    public synchronized DashboardResponse addCreditCard(AddCardRequest req) {
        if (req == null || req.cardName() == null || req.cardName().isBlank()) {
            throw new IllegalArgumentException("Card name is required.");
        }
        if (req.last4() == null || !req.last4().matches("\\d{4}")) throw new IllegalArgumentException("Enter exactly four card digits.");
        if (req.statementDue() != null && req.statementDue().signum()<0) throw new IllegalArgumentException("Statement due cannot be negative.");
        if (!banks.containsKey(req.autopayBankAccountId())) throw new IllegalArgumentException("Choose a funding account.");
        if (req.dueDate()!=null && !req.dueDate().isBlank()) LedgerDates.date(req.dueDate());
        String last4 = "••" + req.last4();
        String id = "card-" + UUID.randomUUID().toString().substring(0, 6);
        String bankId = req.autopayBankAccountId() != null && banks.containsKey(req.autopayBankAccountId())
            ? req.autopayBankAccountId() : "bank-icici-9102";
        MutableBank b = banks.get(bankId);
        String bankLabel = b != null ? b.bankName + " " + b.maskedNumber : "Linked Bank";
        BigDecimal due = req.statementDue() != null ? scale2(req.statementDue()) : bd("0.00");
        cards.put(id, new MutableCard(
            id,
            req.issuerName() == null || req.issuerName().isBlank() ? "Axis Bank" : req.issuerName().trim(),
            req.cardName().trim(),
            last4,
            due.toPlainString(),
            scale2(due.multiply(new BigDecimal("0.05"))).toPlainString(),
            req.dueDate() == null || req.dueDate().isBlank() ? "05 Dec 2026" : req.dueDate().trim(),
            bankId,
            bankLabel,
            "AutoPay linked to " + bankLabel,
            "Added in Cards hub"
        ));
        activityFeed.add(0, "Added credit card " + req.cardName() + " (" + last4 + ") linked to " + bankLabel + ".");
        return buildDashboard();
    }

    public synchronized DashboardResponse addPolicy(AddPolicyRequest req) {
        if (req == null || req.planName() == null || req.planName().isBlank()) {
            throw new IllegalArgumentException("Policy plan name is required.");
        }
        if (req.coverAmount()==null || req.coverAmount().signum()<=0 || req.premiumAmount()==null || req.premiumAmount().signum()<=0)
            throw new IllegalArgumentException("Coverage and premium must be positive.");
        insurancePolicies.add(0, new InsurancePolicy(
            "pol-" + UUID.randomUUID().toString().substring(0, 6),
            req.policyType() == null ? "Health" : req.policyType(),
            req.insurer() == null || req.insurer().isBlank() ? "Insurer" : req.insurer().trim(),
            req.planName().trim(),
            "POL-" + (100000 + insurancePolicies.size()),
            req.coverAmount() != null ? scale2(req.coverAmount()) : bd("1000000.00"),
            req.premiumAmount() != null ? scale2(req.premiumAmount()) : bd("12000.00"),
            req.cadence() == null ? "Annual" : req.cadence(),
            req.nextDueDate() == null || req.nextDueDate().isBlank() ? "15 Jan 2027" : req.nextDueDate(),
            "Not linked", List.of("Entered manually. Check policy terms with the insurer.")
        ));
        activityFeed.add(0, "Added insurance policy: " + req.planName() + ".");
        return buildDashboard();
    }

    public synchronized DashboardResponse createSpace(CreateSpaceRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("Space name is required.");
        }
        List<String> members = (req.members() == null || req.members().isEmpty())
            ? List.of("You")
            : req.members();
        sharedSpaces.add(0, new SharedSpace(
            "space-" + UUID.randomUUID().toString().substring(0, 6),
            req.name().trim(),
            req.purpose() == null || req.purpose().isBlank() ? "Shared expense visibility without exposing personal accounts" : req.purpose().trim(),
            "violet",
            members,
            bd("0.00"),
            List.of()
        ));
        activityFeed.add(0, "Created shared Space \"" + req.name().trim() + "\" with " + members.size() + " members.");
        return buildDashboard();
    }

    public synchronized DashboardResponse uploadCsv(CsvUploadRequest req) {
        if (req == null || req.csvText() == null || req.csvText().isBlank()) {
            throw new IllegalArgumentException("Choose or paste CSV rows (date,description,amount,type).");
        }
        MutableBank bank = banks.get(req.accountId());
        if (bank == null) throw new IllegalArgumentException("Choose an account before importing.");
        var rows = StatementImport.read(req.csvText());
        var file = UUID.nameUUIDFromBytes((bank.id + "\n" + req.csvText().strip().replace("\r\n", "\n")).getBytes(StandardCharsets.UTF_8));
        if (!importedStatements.add(file)) throw new IllegalArgumentException("This statement was already imported into this account.");
        int imported = 0;
        for (var row : rows) {
            if (bank.latestReportedBalance != null && row.date().isAfter(bank.reportDay))
                bank.netAfterReport = bank.netAfterReport.add(row.direction()==Direction.CREDIT ? row.amount() : row.amount().negate());
            String key = file + "-" + (++imported);
            transactions.add(0, new TransactionItem("csv-" + key, row.date().toString(), bank.id, bank.bankName,
                bank.maskedNumber, AccountKind.BANK, row.direction(), row.amount(), row.merchant(), "Statement Import",
                key, null, DataSource.STATEMENT_CSV, "CSV import", row.source(), false));
        }
        activityFeed.add(0, "Imported " + imported + " real statement transaction(s) into " + bank.bankName + " " + bank.maskedNumber + ".");
        return buildDashboard();
    }

    private static List<SampleAlertPreset> samplePresets() {
        return List.of(
            new SampleAlertPreset(
                "preset-shop-refund",
                "1. Shop Promise: Myntra ₹3,000 Refund Started",
                "Keep ₹2k Warning",
                "noreply@myntra.com",
                "Refund Initiated for Order #MYN-99281",
                "We've initiated your ₹3,000.00 refund for Running Shoes to your original payment mode (Card ending 4412). Please allow 5-7 business days for your bank to credit it.",
                "Watch AI classify this as a merchant promise (NOT a bank debit reduction) — Wednesday's ₹2,000 shortfall warning stays ON!"
            ),
            new SampleAlertPreset(
                "preset-issuer-revised",
                "2. Card Issuer: HDFC Revises Wed AutoPay to ₹8,000",
                "Clears Shortfall ✓",
                "alerts@hdfcbank.net",
                "Refund Credited & Revised Auto-Debit for HDFC Credit Card XX4412",
                "Dear Customer, Refund of INR 3,000.00 from MYNTRA DESIGNS is credited to your HDFC Bank Credit Card XX4412. Per RBI Credit Card Directions, your Adjusted Total Amount Due for 25-Nov-2026 auto-debit is INR 8,000.00.",
                "Watch AI update HDFC Regalia's scheduled debit to ₹8,000 — Java math recalculates ₹9,000 - ₹8,000 = ₹1,000 left, clearing the shortfall!"
            ),
            new SampleAlertPreset(
                "preset-idfc-cr",
                "3. IDFC FIRST Debit with 'CR' Balance Tag",
                "CR Trap Test",
                "noreply@idfcfirstbank.com",
                "Debit Alert: IDFC FIRST Bank A/C XXXXXXX9101",
                "Dear Customer, Your IDFC FIRST Bank A/C XXXXXXX9101 is debited by INR 350.00 on 22/11/2026 10:15 towards UPI/ThirdWaveCoffee. New balance is INR 10,249.99CR.",
                "Proves AI reads 'debited by INR 350.00' as money OUT and '10,249.99CR' as positive remaining balance (not money IN)!"
            ),
            new SampleAlertPreset(
                "preset-icici-upi",
                "4. ICICI UPI Alert (No Balance Stated)",
                "Opening Bal Math",
                "zerobalance@icicibank.com",
                "UPI Transaction Alert",
                "Dear Customer, You have made a UPI payment of ₹420.00 via iMobile to Swiggy Instamart from account XXXXXXXX9102 on Nov 22, 2026 at 01:20 PM. UPI Ref: 111299445566.",
                "ICICI omits available balance — Java subtracts ₹420 from ICICI ••9102 computed balance automatically."
            ),
            new SampleAlertPreset(
                "preset-duplicate-bill",
                "5. Duplicate HDFC Bill Reminder Email",
                "Dedupe Guard",
                "statements@hdfcbank.net",
                "Reminder: HDFC Bank Credit Card XX4412 Payment Due",
                "Gentle Reminder: Total Amount Due on your HDFC Bank Credit Card XX4412 is INR 11,000.00 (Minimum Amount Due INR 550.00) for due date 25-Nov-2026.",
                "Proves two emails about the same bill never double-count your ₹11,000 due!"
            )
        );
    }
}
