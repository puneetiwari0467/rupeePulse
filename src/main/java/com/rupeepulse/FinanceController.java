package com.rupeepulse;

import java.util.Map;

import com.rupeepulse.Models.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FinanceController {

    private final FinanceEngineService engine;

    public FinanceController(FinanceEngineService engine) {
        this.engine = engine;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard() {
        return engine.buildDashboard();
    }

    @PostMapping("/aa/connect")
    public DashboardResponse connectFinvu(@RequestBody(required = false) FinvuConnectRequest request) {
        return engine.connectFinvu(request);
    }

    @PostMapping("/gmail/sync")
    public DashboardResponse syncGmail() {
        return engine.syncGmailAlerts();
    }

    @PostMapping("/accounts/opening-balance")
    public DashboardResponse updateOpeningBalance(@RequestBody OpeningBalanceRequest request) {
        return engine.updateOpeningBalance(request);
    }

    @PostMapping("/alerts/parse")
    public ParseNoticeResponse parseNotice(@RequestBody ParseNoticeRequest request) {
        return engine.ingestNotice(request);
    }

    @PostMapping("/ai/ask")
    public AiAskResponse askAi(@RequestBody AiAskRequest request) {
        return engine.askAi(request);
    }

    @PostMapping("/portfolio/add")
    public DashboardResponse addHolding(@RequestBody AddHoldingRequest request) {
        return engine.addPortfolioHolding(request);
    }

    @PostMapping("/bills/recurring")
    public DashboardResponse addRecurring(@RequestBody AddRecurringRequest request) {
        return engine.addRecurringBill(request);
    }

    @PostMapping("/portfolio/simulate-sweep")
    public DashboardResponse simulateSweep() {
        return engine.simulateSweep();
    }

    @PostMapping("/bills/recurring/toggle")
    public DashboardResponse toggleRecurring(@RequestBody ToggleRecurringRequest request) {
        return engine.toggleRecurringBill(request);
    }

    @PostMapping("/bills/pay")
    public DashboardResponse payBill(@RequestBody PayBillRequest request) {
        return engine.payBiller(request);
    }

    @PostMapping("/cards/add")
    public DashboardResponse addCard(@RequestBody AddCardRequest request) {
        return engine.addCreditCard(request);
    }

    @PostMapping("/policies/add")
    public DashboardResponse addPolicy(@RequestBody AddPolicyRequest request) {
        return engine.addPolicy(request);
    }

    @PostMapping("/spaces/create")
    public DashboardResponse createSpace(@RequestBody CreateSpaceRequest request) {
        return engine.createSpace(request);
    }

    @PostMapping("/csv/upload")
    public DashboardResponse uploadCsv(@RequestBody CsvUploadRequest request) {
        return engine.uploadCsv(request);
    }

    @PostMapping("/reset")
    public DashboardResponse reset(@RequestParam(defaultValue = "false") boolean empty) {
        return engine.resetWorkspace(empty);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DailyModelBudget.Limit.class)
    public ResponseEntity<Map<String,String>> quota(DailyModelBudget.Limit ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", ex.getMessage()));
    }
}
