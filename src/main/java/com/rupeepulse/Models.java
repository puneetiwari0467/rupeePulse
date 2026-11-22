package com.rupeepulse;

import java.math.BigDecimal;
import java.util.List;

public final class Models {
    private Models() {}

    public enum AccountKind { BANK, CREDIT_CARD }
    public enum Direction { DEBIT, CREDIT }
    public enum DataSource { FINVU_AA, GMAIL_ALERT, AI_PASTE, STATEMENT_CSV, SIMULATION }

    public enum NoticeIntent {
        BANK_TXN,
        CARD_TXN,
        CARD_BILL_SUMMARY,
        MERCHANT_REFUND_PROMISE,
        ISSUER_REVISED_DEBIT
    }

    public record BankAccount(
        String id,
        String bankName,
        String maskedNumber,
        String ifscCode,
        String accountRole,
        BigDecimal openingBalance,
        BigDecimal totalCredits,
        BigDecimal totalDebits,
        BigDecimal computedBalance,
        BigDecimal reportedBalance,
        BigDecimal effectiveBalance,
        BigDecimal reconciliationGap,
        boolean reconciled,
        BigDecimal interestRatePct,
        boolean aaLinked,
        String lastUpdatedNote
    ) {}

    public record CreditCardAccount(
        String id,
        String issuerName,
        String cardName,
        String maskedNumber,
        BigDecimal statementTotalDue,
        BigDecimal minimumDue,
        BigDecimal effectiveAutopayDue,
        String dueDate,
        String autopayBankAccountId,
        String autopayBankLabel,
        String autopayStatus,
        BigDecimal pendingMerchantRefund,
        String statusNote,
        String evidenceQuote
    ) {}

    public record TransactionItem(
        String id,
        String timestamp,
        String accountId,
        String institution,
        String maskedNumber,
        AccountKind accountKind,
        Direction direction,
        BigDecimal amount,
        String counterparty,
        String category,
        String referenceId,
        BigDecimal reportedBalanceAfter,
        DataSource source,
        String parserUsed,
        String evidenceQuote,
        boolean recurring
    ) {}

    public record ScheduledObligation(
        String id,
        String title,
        String dueDate,
        String bankAccountId,
        String bankLabel,
        String cardId,
        BigDecimal scheduledAmount,
        BigDecimal originalAmount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        BigDecimal shortfallAmount,
        String status,
        String explanation,
        String actionText
    ) {}

    public record MoneyNudge(
        String id,
        String severity,
        String kind,
        String title,
        String detail,
        BigDecimal amountAtStake,
        String evidence
    ) {}

    public record FinvuStatus(
        boolean connected,
        String handle,
        String aggregatorName,
        String consentId,
        String purpose,
        List<String> fiTypes,
        List<String> linkedInstitutions,
        String approvedAt,
        String expiresAt,
        String coverageNote
    ) {}

    public record GmailStatus(
        boolean synced,
        String queryFilter,
        int messagesScanned,
        int duplicatesIgnored,
        String lastSyncedAt
    ) {}

    public record ParsedNotice(
        NoticeIntent intent,
        String institution,
        AccountKind accountKind,
        String maskedNumber,
        Direction direction,
        BigDecimal amount,
        BigDecimal reportedBalance,
        String counterparty,
        String category,
        String timestamp,
        String referenceId,
        boolean recurring,
        String parserUsed,
        String summary,
        String evidenceQuote
    ) {}

    public record SampleAlertPreset(
        String id,
        String label,
        String badge,
        String sender,
        String subject,
        String rawText,
        String whatToWatch
    ) {}

    public record PortfolioHolding(
        String id,
        String bucket,
        String bucketLabel,
        String name,
        String provider,
        String subtitle,
        BigDecimal investedAmount,
        BigDecimal currentValue,
        BigDecimal returnPct,
        BigDecimal monthlyContribution,
        String sourceBadge,
        String aiNote
    ) {}

    public record PortfolioSummary(
        BigDecimal totalValue,
        BigDecimal totalInvested,
        BigDecimal totalGain,
        BigDecimal overallReturnPct,
        BigDecimal monthlyInvested,
        List<PortfolioHolding> holdings
    ) {}

    public record RecurringBillItem(
        String id,
        String merchant,
        String category,
        BigDecimal amount,
        String cadence,
        String nextDueDate,
        String accountLabel,
        String bankCode,
        String status,
        String aiRecommendation
    ) {}

    public record PayableBiller(
        String id,
        String billerType,
        String title,
        String provider,
        String identifier,
        BigDecimal dueAmount,
        String dueDate,
        boolean paid,
        boolean autoPayEnabled,
        String autopayAccountLabel
    ) {}

    public record InsurancePolicy(
        String id,
        String policyType,
        String insurer,
        String planName,
        String policyNumber,
        BigDecimal coverAmount,
        BigDecimal premiumAmount,
        String cadence,
        String nextDueDate,
        String linkedAccount,
        List<String> aiPlainEnglishChecks
    ) {}

    public record SpaceExpense(
        String title,
        String paidBy,
        String category,
        String date,
        BigDecimal amount
    ) {}

    public record SharedSpace(
        String id,
        String name,
        String purpose,
        String accent,
        List<String> members,
        BigDecimal monthlyTotal,
        List<SpaceExpense> sharedItems
    ) {}

    public record CreditReportSummary(
        int cibilScore,
        String ratingLabel,
        BigDecimal utilizationPct,
        BigDecimal totalCreditLimit,
        int onTimePaymentsPct,
        int activeCardCount,
        String aiCardAdvice
    ) {}

    public record SummaryMetrics(
        BigDecimal totalBankBalance,
        BigDecimal totalPortfolioValue,
        BigDecimal netWorth,
        BigDecimal monthIncoming,
        BigDecimal monthOutgoing,
        BigDecimal monthInvested,
        BigDecimal totalCreditCardDue,
        BigDecimal netLiquidAfterDues,
        BigDecimal totalProjectedShortfall,
        BigDecimal monthlyRecurringLeaks,
        BigDecimal idleSavingsCash,
        int bankCount,
        int cardCount,
        int transactionCount
    ) {}

    public record DashboardResponse(
        boolean liveAzureAiConfigured,
        String aiModelLabel,
        FinvuStatus finvu,
        GmailStatus gmail,
        SummaryMetrics metrics,
        List<BankAccount> bankAccounts,
        List<CreditCardAccount> creditCards,
        CreditReportSummary creditReport,
        PortfolioSummary portfolio,
        List<RecurringBillItem> recurringBills,
        List<PayableBiller> payableBillers,
        List<InsurancePolicy> insurancePolicies,
        List<SharedSpace> sharedSpaces,
        List<ScheduledObligation> upcomingObligations,
        List<MoneyNudge> nudges,
        List<TransactionItem> transactions,
        List<SampleAlertPreset> samplePresets,
        List<String> activityFeed
    ) {}

    public record FinvuConnectRequest(String handle, List<String> banks, String otp) {}
    public record OpeningBalanceRequest(String accountId, BigDecimal openingBalance) {}
    public record ParseNoticeRequest(String sender, String rawText, boolean preferLiveAi) {}
    public record ParseNoticeResponse(ParsedNotice parsed, boolean duplicateIgnored, String outcomeMessage, DashboardResponse dashboard) {}

    public record AddHoldingRequest(String bucket, String name, String provider, BigDecimal investedAmount, BigDecimal currentValue, BigDecimal monthlyContribution) {}
    public record AddRecurringRequest(String merchant, String category, BigDecimal amount, String cadence, String nextDueDate, String accountLabel) {}
    public record ToggleRecurringRequest(String id, String action) {}
    public record PayBillRequest(String billerId, String bankAccountId, boolean setupAutoPay) {}
    public record AddCardRequest(String issuerName, String cardName, String last4, BigDecimal statementDue, String dueDate, String autopayBankAccountId) {}
    public record AddPolicyRequest(String policyType, String insurer, String planName, BigDecimal coverAmount, BigDecimal premiumAmount, String cadence, String nextDueDate) {}
    public record CreateSpaceRequest(String name, String purpose, List<String> members) {}
    public record CsvUploadRequest(String accountId, String csvText) {}

    public record AiTurn(String question, String answer) {}
    public record AiAskRequest(String question, List<AiTurn> history) {
        public AiAskRequest(String question) {
            this(question, List.of());
        }
    }
    public record AiCitation(String label, String detail, String amount) {}
    public record AiAskResponse(
        String question,
        String answerMarkdown,
        String modelUsed,
        long latencyMs,
        List<AiCitation> citations
    ) {}
}
