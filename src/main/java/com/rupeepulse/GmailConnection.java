package com.rupeepulse;

import static com.rupeepulse.GmailDemoClient.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

@Component
@SessionScope
public class GmailConnection {
    record Status(boolean configured, boolean connected, String message, String label, String subject, String sampleBody) {}
    record Row(String ticket, String account, String merchant, BigDecimal amount, String date) {}
    record Preview(List<Row> rows, String message) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final GmailDemoClient google;
    private final Map<String,Debit> previews = new LinkedHashMap<>();
    private String state, verifier, token, note = "";
    private Instant stateUntil = Instant.EPOCH, tokenUntil = Instant.EPOCH, previewUntil = Instant.EPOCH;

    GmailConnection(GmailDemoClient google) { this.google = google; }

    public synchronized Status status() {
        if (token != null && !Instant.now().isBefore(tokenUntil)) {
            clear(); note = "This Gmail connection expired. Reconnect to preview messages.";
        }
        return new Status(google.configured(),token!=null,note,LABEL,SUBJECT,BODY);
    }

    public synchronized String begin() {
        clear();
        state=random(); verifier=random(); stateUntil=Instant.now().plusSeconds(300);
        try {
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            return google.authorizeUrl(state,challenge);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized boolean finish(String returnedState,String code,String error) {
        if (state==null || returnedState==null || returnedState.length()>128 || !Instant.now().isBefore(stateUntil) ||
                !MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),returnedState.getBytes(StandardCharsets.US_ASCII))) {
            note="Gmail sign-in was not matched to this session. Reopen Data sources and connect again.";
            return false;
        }
        String savedVerifier=verifier; state=null; verifier=null;
        if (error!=null || code==null || code.isBlank() || code.length()>2048) {
            note="Gmail consent was not completed. No mail was read."; return false;
        }
        try {
            Grant grant=google.exchange(code,savedVerifier);
            token=grant.token(); tokenUntil=Instant.now().plusSeconds(grant.seconds()-10);
            note="Demo mailbox connected read-only. Nothing has been imported."; return true;
        } catch (Failure ex) { clear(); note=ex.getMessage(); return false; }
    }

    public synchronized Preview preview(FinanceEngineService ledger) {
        requireConnection(); previews.clear();
        Search found;
        try { found=google.search(token,ledger::hasGmailMessage); }
        catch (Failure e) { if (e.status==HttpStatus.UNAUTHORIZED) { clear(); note=e.getMessage(); } throw e; }
        var rows=new ArrayList<Row>();
        for (var debit : found.rows()) {
            String ticket=random(); previews.put(ticket,debit);
            rows.add(new Row(ticket,"IDFC FIRST Bank ••9101",debit.merchant(),debit.amount(),debit.date().toString()));
        }
        previewUntil=Instant.now().plusSeconds(300);
        return new Preview(List.copyOf(rows),found.note());
    }

    public synchronized Models.ParseNoticeResponse confirm(String ticket, FinanceEngineService ledger) {
        requireConnection();
        var row=previews.get(ticket);
        if (row==null || !Instant.now().isBefore(previewUntil)) throw new Failure(HttpStatus.CONFLICT,"Preview this email again before importing.");
        var result=ledger.importGmailDebit(row);
        previews.remove(ticket);
        return result;
    }

    public synchronized void clear() {
        state=null; verifier=null; token=null; previews.clear(); note="";
        stateUntil=Instant.EPOCH; tokenUntil=Instant.EPOCH; previewUntil=Instant.EPOCH;
    }
    private void requireConnection() {
        if (!status().connected()) throw new Failure(HttpStatus.UNAUTHORIZED,"Connect the demo Gmail account first.");
    }
    private static String random() {
        byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
