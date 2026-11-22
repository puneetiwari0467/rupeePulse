package com.rupeepulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupeepulse.Models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.test.web.servlet.setup.SharedHttpSessionConfigurer.sharedHttpSession;

@SpringBootTest(properties = {"AZURE_OPENAI_API_KEY=", "AZURE_OPENAI_ENDPOINT="})
@AutoConfigureMockMvc
class RupeePulseApplicationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;
    @Autowired
    private WebApplicationContext context;

    @BeforeEach
    void resetState() throws Exception {
        mvc = webAppContextSetup(context).apply(sharedHttpSession()).build();
        mvc.perform(post("/api/reset?empty=false")).andExpect(status().isOk());
    }

    @Test
    void multiBankAndMultiCardShortfallAndReconciliationWorkOutOfTheBox() throws Exception {
        String body = mvc.perform(get("/api/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.finvu.connected").value(true))
            .andExpect(jsonPath("$.metrics.bankCount").value(3))
            .andExpect(jsonPath("$.metrics.cardCount").value(2))
            .andExpect(jsonPath("$.metrics.totalProjectedShortfall").value(2000.00))
            .andReturn().getResponse().getContentAsString();

        DashboardResponse dash = json.readValue(body, DashboardResponse.class);
        BankAccount idfc = dash.bankAccounts().stream().filter(b -> b.id().equals("bank-idfc-9101")).findFirst().orElseThrow();
        assertThat(idfc.computedBalance()).isEqualByComparingTo("10799.99");
        assertThat(idfc.reportedBalance()).isEqualByComparingTo("10599.99");
        assertThat(idfc.reconciliationGap()).isEqualByComparingTo("-200.00");

        CreditCardAccount iciciCard = dash.creditCards().stream().filter(c -> c.id().equals("card-icici-8821")).findFirst().orElseThrow();
        assertThat(iciciCard.autopayStatus()).isEqualTo("COVERED");
    }

    @Test
    void shopRefundPromiseKeepsShortfallWhileCardIssuerRevisionClearsIt() throws Exception {
        // 1. Shop promises ₹3,000 refund -> must KEEP the ₹2,000 Wednesday shortfall!
        var shopReq = new ParseNoticeRequest(
            "noreply@myntra.com",
            "We've initiated your ₹3,000.00 refund for Running Shoes to Card ending 4412. Please allow 5-7 business days.",
            false
        );
        mvc.perform(post("/api/alerts/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(shopReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parsed.intent").value("MERCHANT_REFUND_PROMISE"))
            .andExpect(jsonPath("$.dashboard.metrics.totalProjectedShortfall").value(2000.00));

        // 2. Card issuer confirms refund & revises scheduled auto-debit to ₹8,000 -> CLEARS shortfall!
        var issuerReq = new ParseNoticeRequest(
            "alerts@hdfcbank.net",
            "Refund of INR 3,000.00 from MYNTRA DESIGNS is credited to your HDFC Bank Credit Card XX4412. Adjusted Total Amount Due for 25-Nov-2026 auto-debit is INR 8,000.00.",
            false
        );
        mvc.perform(post("/api/alerts/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(issuerReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parsed.intent").value("ISSUER_REVISED_DEBIT"))
            .andExpect(jsonPath("$.dashboard.metrics.totalProjectedShortfall").value(0.00));
    }

    @Test
    void idfcCrBalanceSuffixIsParsedAsDebitWithPositiveBalance() throws Exception {
        var idfcReq = new ParseNoticeRequest(
            "noreply@idfcfirstbank.com",
            "Dear Customer, Your IDFC FIRST Bank A/C XXXXXXX9101 is debited by INR 350.00 on 22/11/2026 10:15 towards UPI/ThirdWaveCoffee. New balance is INR 10,249.99CR.",
            false
        );
        mvc.perform(post("/api/alerts/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(idfcReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parsed.direction").value("DEBIT"))
            .andExpect(jsonPath("$.parsed.amount").value(350.00))
            .andExpect(jsonPath("$.parsed.reportedBalance").value(10249.99));
    }

    @Test
    void emptyResetThenFinvuConnectThenGmailSyncFlowWorks() throws Exception {
        mvc.perform(post("/api/reset?empty=true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.metrics.bankCount").value(0));

        var finvuReq = new FinvuConnectRequest(
            "demo@finvu",
            List.of("IDFC FIRST Bank", "ICICI Bank", "UCO Bank"),
            "482910"
        );
        mvc.perform(post("/api/aa/connect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(finvuReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.finvu.connected").value(true))
            .andExpect(jsonPath("$.metrics.bankCount").value(3));

        mvc.perform(post("/api/gmail/sync"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.metrics.cardCount").value(2));
    }

    @Test
    void naturalLanguageAiAnalystAnswersCategoryThresholdAndSubscriptionPrompts() throws Exception {
        mvc.perform(post("/api/ai/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AiAskRequest("Which are my 5 biggest personal spending categories so far this year?"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answerMarkdown").value(org.hamcrest.Matchers.containsString("Travel & Flights")));

        mvc.perform(post("/api/ai/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AiAskRequest("Could you tell me any transaction over $200 for the last 60 days?"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answerMarkdown").value(org.hamcrest.Matchers.containsString("IndiGo")));

        mvc.perform(post("/api/ai/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AiAskRequest("Please evaluate all of my recurring charges and make recommendations for things I should consider cancelling now."))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answerMarkdown").value(org.hamcrest.Matchers.containsString("Cloud AI Pro")));

        // Also test opening balance update
        mvc.perform(post("/api/accounts/opening-balance")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new OpeningBalanceRequest("bank-icici-9102", new BigDecimal("20000.00")))))
            .andExpect(status().isOk());
    }

    @Test
    void portfolioBillsInsuranceSpacesAndCsvImportWorkEndToEnd() throws Exception {
        // Verify Portfolio has all 6 buckets (MF, Stocks/ETFs, FD, NPS, EPF, PPF) totaling ₹14,82,400
        mvc.perform(get("/api/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.portfolio.totalValue").value(1482400.00))
            .andExpect(jsonPath("$.payableBillers.length()").value(5))
            .andExpect(jsonPath("$.insurancePolicies.length()").value(3))
            .andExpect(jsonPath("$.sharedSpaces.length()").value(2));

        // Pay a bill via Bharat Connect
        var payReq = new PayBillRequest("bill-gas", "bank-uco-9103", true);
        mvc.perform(post("/api/bills/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payReq)))
            .andExpect(status().isOk());

        // Upload CSV rows
        var csvReq = new CsvUploadRequest("bank-icici-9102", "22 Nov 2026,Third Wave Coffee,340.00,DEBIT");
        mvc.perform(post("/api/csv/upload")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(csvReq)))
            .andExpect(status().isOk());
    }

    private DashboardResponse snapshot() throws Exception {
        return json.readValue(mvc.perform(get("/api/dashboard")).andReturn().getResponse().getContentAsByteArray(), DashboardResponse.class);
    }

    private org.springframework.test.web.servlet.ResultActions submit(String endpoint, Object value) throws Exception {
        return mvc.perform(post("/api"+endpoint).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value)));
    }

    @Test void invalidCsvRollsBackAndRepeatedFileDoesNotReplay() throws Exception {
        var before=snapshot();
        String valid="2026-11-22,\"Demo, cafe\",100.15,DEBIT";
        submit("/csv/upload",new CsvUploadRequest("bank-idfc-9101",valid+"\nbad,shop,3,DEBIT")).andExpect(status().isBadRequest());
        assertThat(snapshot().transactions()).isEqualTo(before.transactions());
        submit("/csv/upload",new CsvUploadRequest("bank-idfc-9101",valid)).andExpect(status().isOk());
        submit("/csv/upload",new CsvUploadRequest("bank-idfc-9101",valid)).andExpect(status().isBadRequest());
        var after=snapshot();
        assertThat(after.transactions()).hasSize(before.transactions().size()+1);
        assertThat(after.bankAccounts().getFirst().effectiveBalance()).isEqualByComparingTo("10499.84");
    }

    @Test void cardFundingUsesDueDateOrderAndCountsOneGapPerBank() throws Exception {
        submit("/cards/add",new AddCardRequest("Sample","Early bill","2510",new BigDecimal("10000"),"2026-11-22","bank-idfc-9101")).andExpect(status().isOk());
        var snapshot=snapshot();
        assertThat(snapshot.upcomingObligations().getFirst().scheduledAmount()).isEqualByComparingTo("10000");
        assertThat(snapshot.upcomingObligations().get(1).balanceBefore()).isEqualByComparingTo("599.99");
        assertThat(snapshot.metrics().totalProjectedShortfall()).isEqualByComparingTo("12000");
    }

    @Test void cardPaymentAndReplayDoNotIncreaseSpendingOrRestoreOldBalance() throws Exception {
        var before=snapshot();
        assertThat(before.metrics().monthOutgoing()).isEqualByComparingTo("38933.57");
        var request=new PayBillRequest("bill-cc-hdfc","bank-uco-9103",false);
        submit("/bills/pay",request).andExpect(status().isOk());
        submit("/bills/pay",request).andExpect(status().isOk());
        mvc.perform(post("/api/gmail/sync")).andExpect(status().isOk());
        var after=snapshot();
        assertThat(after.transactions()).hasSize(before.transactions().size()+1);
        assertThat(after.metrics().netWorth()).isEqualByComparingTo(before.metrics().netWorth());
        assertThat(after.metrics().monthOutgoing()).isEqualByComparingTo(before.metrics().monthOutgoing());
        assertThat(after.metrics().totalProjectedShortfall()).isEqualByComparingTo("0");
    }

    @Test void repeatedSameAmountWithDifferentReferencesIsNotDuplicate() throws Exception {
        String alert="ICICI Bank: UPI payment of ₹50.00 to Test Shop from account XX9102 on 21/11/2026. UPI Ref: ";
        for (String ref:List.of("ABC00111","ABC00112"))
            submit("/alerts/parse",new ParseNoticeRequest("",alert+ref,false)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicateIgnored").value(false));
        submit("/alerts/parse",new ParseNoticeRequest("",alert+"ABC00112",false)).andExpect(jsonPath("$.duplicateIgnored").value(true));
    }

    @Test void unknownOrAmbiguousRefundDoesNotAlterEitherCard() throws Exception {
        var before=snapshot().creditCards();
        submit("/alerts/parse",new ParseNoticeRequest("shop","Refund initiated: ₹250 to Card ending 7444. Allow 5 days.",false))
            .andExpect(status().isBadRequest());
        submit("/alerts/parse",new ParseNoticeRequest("shop","Refund initiated: ₹250. Allow 5 days.",false))
            .andExpect(status().isBadRequest());
        assertThat(snapshot().creditCards()).isEqualTo(before);
    }

    @Test void thresholdIsStrictAndRespectsBothEndsOfWindow() throws Exception {
        submit("/csv/upload",new CsvUploadRequest("bank-icici-9102",
            "2026-09-22,Too early,20000,DEBIT\n2026-09-23,First day,17000.01,DEBIT\n2026-11-21,Exactly threshold,17000,DEBIT\n2026-11-22,Future,50000,DEBIT"))
            .andExpect(status().isOk());
        var result=submit("/ai/ask",new AiAskRequest("Any transactions over $200 for the last 60 days?")).andExpect(status().isOk());
        var reply=json.readValue(result.andReturn().getResponse().getContentAsByteArray(),AiAskResponse.class);
        assertThat(reply.answerMarkdown()).contains("First day","Salary").doesNotContain("Too early","Exactly threshold","Future");
    }
}
