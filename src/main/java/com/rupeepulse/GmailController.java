package com.rupeepulse;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gmail")
class GmailController {
    private final GmailConnection mailbox;
    private final FinanceEngineService ledger;
    GmailController(GmailConnection mailbox,FinanceEngineService ledger) { this.mailbox=mailbox; this.ledger=ledger; }

    @GetMapping("/status")
    ResponseEntity<GmailConnection.Status> status() { return ok(mailbox.status()); }

    @PostMapping(value="/connect",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String,String>> connect() { return ok(Map.of("authorizationUrl",mailbox.begin())); }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam(required=false) String state,@RequestParam(required=false) String code,
                                  @RequestParam(required=false) String error,HttpServletRequest request) {
        if (request.getSession(false)!=null && mailbox.finish(state,code,error)) request.changeSessionId();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/?gmail=return#transactions"))
            .cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").build();
    }

    @PostMapping(value="/preview",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GmailConnection.Preview> preview() { return ok(mailbox.preview(ledger)); }

    record Confirmation(String ticket) {}
    @PostMapping(value="/import",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Models.ParseNoticeResponse> confirm(@RequestBody Confirmation req) {
        return ok(mailbox.confirm(req.ticket(),ledger));
    }

    @PostMapping(value="/disconnect",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GmailConnection.Status> disconnect() { mailbox.clear(); return ok(mailbox.status()); }

    @ExceptionHandler(GmailDemoClient.Failure.class)
    ResponseEntity<Map<String,String>> failed(GmailDemoClient.Failure ex) {
        return ResponseEntity.status(ex.status).cacheControl(CacheControl.noStore()).body(Map.of("error",ex.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error",ex.getMessage()));
    }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
