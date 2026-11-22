package com.rupeepulse;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class GmailDemoClient {
    static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    static final String LABEL = "RupeePulseDemo";
    static final String SUBJECT = "[rupeePulse DEMO] Debit alert RP-711";
    static final String BODY = """
        Fictional test transaction — not a bank notification.
        IDFC FIRST Bank A/C XX9101 debited INR 711.00 at Demo Cafe on 22/11/2026.
        Debit card purchase. Txn ID: DEMO711001.""";
    static final String API = "https://gmail.googleapis.com/gmail/v1/users/me";
    static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final int MAX_MAIL_BYTES = 48_000;
    private static final Pattern DEBIT = Pattern.compile(
        "Fictional test transaction\\s*[-–—]\\s*not a bank notification\\.\\s+"
        + "IDFC FIRST Bank A/C XX9101 debited INR (\\d{1,6}(?:\\.\\d{1,2})?) at "
        + "(Demo [A-Za-z0-9][A-Za-z0-9 &-]{0,35}?) on (\\d{2}/\\d{2}/\\d{4})\\.\\s+"
        + "Debit card purchase\\.\\s*Txn ID:\\s*(DEMO[A-Za-z0-9]{3,16})\\.?", Pattern.CASE_INSENSITIVE);

    record Debit(String id, String merchant, BigDecimal amount, LocalDate date, String reference) {}
    record Search(List<Debit> rows, String note) {}
    record Grant(String token, long seconds) {
        @Override public String toString() { return "Gmail access grant [redacted]"; }
    }
    static final class Failure extends RuntimeException {
        final HttpStatus status;
        Failure(HttpStatus status, String message) { super(message); this.status = status; }
    }

    private final RestClient rest;
    private final String clientId, secret, owner, redirect;

    @Autowired
    GmailDemoClient(@Value("${GMAIL_CLIENT_ID:}") String clientId,
                    @Value("${GMAIL_CLIENT_SECRET:}") String secret,
                    @Value("${GMAIL_DEMO_EMAIL:}") String owner,
                    @Value("${GMAIL_REDIRECT_URI:}") String redirect) {
        this(clientId, secret, owner, redirect, transport());
    }

    GmailDemoClient(String clientId, String secret, String owner, String redirect, RestClient rest) {
        this.clientId = clientId.trim();
        this.secret = secret.trim();
        this.owner = owner.trim().toLowerCase(Locale.ROOT);
        this.redirect = redirect.trim();
        this.rest = rest;
    }

    private static RestClient transport() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(7));
        return RestClient.builder().requestFactory(factory).build();
    }

    boolean configured() {
        if (clientId.isBlank() || secret.isBlank() || !owner.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) return false;
        try {
            var uri = URI.create(redirect);
            return uri.getHost()!=null && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null
                && "/api/gmail/callback".equals(uri.getPath())
                && ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))));
        } catch (IllegalArgumentException invalidUri) { return false; }
    }

    String authorizeUrl(String state, String challenge) {
        if (!configured()) throw new Failure(HttpStatus.SERVICE_UNAVAILABLE, "Gmail OAuth is not configured on this host.");
        return UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
            .queryParam("response_type","code").queryParam("client_id",clientId).queryParam("redirect_uri",redirect)
            .queryParam("scope",SCOPE).queryParam("state",state).queryParam("code_challenge",challenge)
            .queryParam("code_challenge_method","S256").queryParam("access_type","online")
            .queryParam("prompt","select_account").build().encode().toUriString();
    }

    Grant exchange(String code, String verifier) {
        var form = new LinkedMultiValueMap<String,String>();
        form.add("code",code); form.add("code_verifier",verifier); form.add("client_id",clientId);
        form.add("client_secret",secret); form.add("redirect_uri",redirect); form.add("grant_type","authorization_code");
        JsonNode response = google(() -> rest.post().uri(TOKEN).contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form).retrieve().body(JsonNode.class));
        String token = response.path("access_token").asText("");
        long lifetime = Math.min(3600,response.path("expires_in").asLong(0));
        if (token.isBlank() || lifetime < 30 || !SCOPE.equals(response.path("scope").asText())
                || !"Bearer".equalsIgnoreCase(response.path("token_type").asText()))
            throw new Failure(HttpStatus.FORBIDDEN, "Gmail did not return the required read-only grant.");
        var profile = get(url("/profile").queryParam("fields","emailAddress"), token);
        if (!owner.equalsIgnoreCase(profile.path("emailAddress").asText()))
            throw new Failure(HttpStatus.FORBIDDEN, "Use the configured demo mailbox. No messages were read.");
        return new Grant(token,lifetime);
    }

    Search search(String token, Predicate<String> alreadyImported) {
        var labels = get(url("/labels").queryParam("fields","labels(id,name)"), token);
        String labelId = null;
        for (var label : labels.path("labels")) if (LABEL.equals(label.path("name").asText())) labelId = label.path("id").asText();
        if (labelId == null || labelId.isBlank())
            return new Search(List.of(), "Create the Gmail label " + LABEL + ", apply it to your self-sent test email, then preview again.");
        var hits = get(url("/messages").queryParam("labelIds",labelId,"SENT").queryParam("maxResults",3)
            .queryParam("q","from:me to:me newer_than:1d subject:\"" + SUBJECT + "\"")
            .queryParam("fields","messages(id)"), token);
        List<Debit> rows = new ArrayList<>();
        int checked = 0, duplicates = 0;
        for (var hit : hits.path("messages")) {
            if (++checked > 3) break;
            String id = hit.path("id").asText("");
            if (!id.matches("[A-Za-z0-9_-]{1,128}")) continue;
            if (alreadyImported.test(id)) { duplicates++; continue; }
            String path = "/messages/" + id;
            var metadata = get(url(path).queryParam("format","metadata").queryParam("metadataHeaders","Subject","From","To")
                .queryParam("fields","id,labelIds,sizeEstimate,payload(headers)"),token);
            if (!eligible(metadata,id,labelId)) continue;
            var full = get(url(path).queryParam("format","full").queryParam("fields","id,labelIds,sizeEstimate,payload"),token);
            if (eligible(full,id,labelId)) plain(full.path("payload"),0).flatMap(body -> debit(id,body)).ifPresent(rows::add);
        }
        String note = !rows.isEmpty() ? "Review the matching self-sent test debit before importing."
            : duplicates>0 ? "Already imported. No new transactions."
            : "No eligible demo debit. Send the template to yourself, label it, and try again. No ledger changes.";
        return new Search(List.copyOf(rows),note);
    }

    private boolean eligible(JsonNode message, String id, String label) {
        long size = message.path("sizeEstimate").asLong(-1);
        boolean sent = false, labeled = false;
        for (var l : message.path("labelIds")) {
            sent |= "SENT".equals(l.asText()); labeled |= label.equals(l.asText());
            if ("TRASH".equals(l.asText()) || "SPAM".equals(l.asText())) return false;
        }
        var headers = message.path("payload").path("headers");
        return sent && labeled && id.equals(message.path("id").asText()) && size > 0 && size <= MAX_MAIL_BYTES
            && SUBJECT.equals(header(headers,"Subject")) && ownerHeader(header(headers,"From")) && ownerHeader(header(headers,"To"));
    }

    private boolean ownerHeader(String value) {
        return owner.equalsIgnoreCase(value) || Pattern.compile("[^<>\\r\\n,]*<"+Pattern.quote(owner)+">",
            Pattern.CASE_INSENSITIVE).matcher(value).matches();
    }

    private static String header(JsonNode headers, String key) {
        String found = null;
        for (var h : headers) if (key.equalsIgnoreCase(h.path("name").asText())) {
            if (found != null) return "";
            found = h.path("value").asText("").trim();
        }
        return found == null ? "" : found;
    }

    private static Optional<String> plain(JsonNode part, int depth) {
        if (depth>5 || !part.path("filename").asText("").isBlank() || part.path("body").has("attachmentId")) return Optional.empty();
        String mime = part.path("mimeType").asText("");
        if ("text/plain".equalsIgnoreCase(mime)) {
            String data = part.path("body").path("data").asText("");
            if (data.isEmpty() || data.length()>6000) return Optional.empty();
            try { return Optional.of(new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8).strip()); }
            catch (IllegalArgumentException invalidData) { return Optional.empty(); }
        }
        if (mime.startsWith("multipart/")) for (var child : part.path("parts")) {
            var text = plain(child,depth+1); if (text.isPresent()) return text;
        }
        return Optional.empty();
    }

    private static Optional<Debit> debit(String id, String body) {
        var match = DEBIT.matcher(body.strip().replaceAll("\\s+"," "));
        if (!match.matches()) return Optional.empty();
        var amount = new BigDecimal(match.group(1));
        if (amount.signum()<=0) return Optional.empty();
        try { return Optional.of(new Debit(id,match.group(2),amount,LedgerDates.date(match.group(3)),match.group(4).toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException invalidDate) { return Optional.empty(); }
    }

    private static UriComponentsBuilder url(String suffix) { return UriComponentsBuilder.fromUriString(API+suffix); }
    private JsonNode get(UriComponentsBuilder uri,String token) {
        return google(() -> rest.get().uri(uri.build().encode().toUri()).header(HttpHeaders.AUTHORIZATION,"Bearer "+token)
            .retrieve().body(JsonNode.class));
    }

    private static JsonNode google(Supplier<JsonNode> call) {
        try {
            var data = call.get();
            if (data==null || !data.isObject()) throw new Failure(HttpStatus.BAD_GATEWAY,"Gmail returned no usable response.");
            return data;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value()==401) throw new Failure(HttpStatus.UNAUTHORIZED,"Google access expired. Connect Gmail again.");
            if (e.getStatusCode().value()==403) throw new Failure(HttpStatus.FORBIDDEN,"Google denied Gmail access. Check the test user, consent and Gmail API settings.");
            if (e.getStatusCode().value()==429) throw new Failure(HttpStatus.TOO_MANY_REQUESTS,"Google rate-limited this request. Try again later.");
            throw new Failure(HttpStatus.BAD_GATEWAY,"Google could not complete the request ("+e.getStatusCode().value()+").");
        } catch (RestClientException e) {
            throw new Failure(HttpStatus.BAD_GATEWAY,"Gmail could not be reached or its response was unreadable. No transaction was imported.");
        }
    }
}
