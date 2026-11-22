package com.rupeepulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(properties={"AZURE_OPENAI_API_KEY=","AZURE_OPENAI_ENDPOINT=","AZURE_OPENAI_MANAGED_IDENTITY=false"})
@AutoConfigureMockMvc
@Import(GmailImportTests.FakeGoogle.class)
class GmailImportTests {
    static final String OWNER="demo@example.test", MAIL="email711", LABEL_ID="Label_42";
    static final String CALLBACK="http://127.0.0.1:8094/api/gmail/callback", ACCESS="test-grant-not-real";

    static class Wire {
        final RestClient.Builder builder=RestClient.builder();
        final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class FakeGoogle {
        @Bean Wire gmailWire() { return new Wire(); }
        @Bean @Primary GmailDemoClient fakeGmail(Wire wire) {
            return new GmailDemoClient("test-client","test-only",OWNER,CALLBACK,wire.builder.build());
        }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Wire wire;
    MockHttpSession session;
    @BeforeEach void setup() { wire.server.reset(); session=new MockHttpSession(); }

    ResultActions send(MockHttpSession s, String route, Object payload) throws Exception {
        return mvc.perform(post("/api/"+route).session(s).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(payload)));
    }
    JsonNode body(ResultActions response) throws Exception {
        return json.readTree(response.andReturn().getResponse().getContentAsByteArray());
    }
    void verified() { wire.server.verify(); wire.server.reset(); }
    URI start() throws Exception {
        var uri=URI.create(body(send(session,"gmail/connect",Map.of()).andExpect(status().isOk())).get("authorizationUrl").asText());
        assertThat(uri.getHost()).isEqualTo("accounts.google.com");
        assertThat(param(uri,"scope")).isEqualTo(GmailDemoClient.SCOPE);
        assertThat(param(uri,"access_type")).isEqualTo("online");
        assertThat(param(uri,"code_challenge_method")).isEqualTo("S256");
        return uri;
    }
    String param(URI uri,String key) {
        return URLDecoder.decode(UriComponentsBuilder.fromUri(uri).build(true).getQueryParams().getFirst(key), StandardCharsets.UTF_8);
    }
    void expectGet(String path, String queryContains, Object result) throws Exception {
        wire.server.expect(request->{
            assertThat(request.getURI().getHost()).isEqualTo("gmail.googleapis.com");
            assertThat(request.getURI().getPath()).isEqualTo("/gmail/v1/users/me"+path);
            var query=URLDecoder.decode(request.getURI().getRawQuery(),StandardCharsets.UTF_8);
            assertThat(query).contains(queryContains);
            if (path.equals("/messages")) assertThat(query).contains("labelIds="+LABEL_ID,"labelIds=SENT","from:me to:me","newer_than:1d",GmailDemoClient.SUBJECT);
        }).andExpect(method(HttpMethod.GET)).andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization","Bearer "+ACCESS))
          .andRespond(withSuccess(json.writeValueAsString(result),MediaType.APPLICATION_JSON));
    }
    void exchange(URI auth,String profileEmail) throws Exception {
        wire.server.expect(requestTo(GmailDemoClient.TOKEN)).andExpect(method(HttpMethod.POST)).andExpect(request->{
            String form=((MockClientHttpRequest)request).getBodyAsString();
            var verifier=Pattern.compile("(?:^|&)code_verifier=([^&]+)").matcher(form);
            assertThat(verifier.find()).isTrue();
            try {
                var digest=MessageDigest.getInstance("SHA-256").digest(verifier.group(1).getBytes(StandardCharsets.US_ASCII));
                assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(digest)).isEqualTo(param(auth,"code_challenge"));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }).andRespond(withSuccess(json.writeValueAsString(Map.of("access_token",ACCESS,"expires_in",3600,
            "scope",GmailDemoClient.SCOPE,"token_type","Bearer")),MediaType.APPLICATION_JSON));
        expectGet("/profile","emailAddress",Map.of("emailAddress",profileEmail));
        mvc.perform(get("/api/gmail/callback").session(session).param("state",param(auth,"state")).param("code","test-auth-code"))
          .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/?gmail=return#transactions"));
        verified();
    }
    void searchList() throws Exception {
        expectGet("/labels","fields=",Map.of("labels",List.of(Map.of("name",GmailDemoClient.LABEL,"id",LABEL_ID))));
        expectGet("/messages","maxResults=3",Map.of("messages",List.of(Map.of("id",MAIL))));
    }
    void message(String format,String subject,String text) throws Exception {
        var headers=List.of(Map.of("name","From","value","Demo User <"+OWNER+">"),Map.of("name","To","value",OWNER),
            Map.of("name","Subject","value",subject));
        var part=Map.of("mimeType","text/plain","body",Map.of("data",Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.getBytes(StandardCharsets.UTF_8))));
        var payload=format.equals("metadata")?Map.of("headers",headers):Map.of("headers",headers,
            "mimeType","multipart/alternative","parts",List.of(part));
        expectGet("/messages/"+MAIL,"format="+format,Map.of("id",MAIL,"sizeEstimate",1800,
            "labelIds",List.of("SENT",LABEL_ID),"payload",payload));
    }

    @Test void authorizedPreviewAndConfirmUseOneSessionAndDebitExactlyOnce() throws Exception {
        exchange(start(),OWNER);
        mvc.perform(get("/api/gmail/status").session(session)).andExpect(jsonPath("$.connected").value(true));
        searchList();message("metadata",GmailDemoClient.SUBJECT,"");message("full",GmailDemoClient.SUBJECT,GmailDemoClient.BODY);
        var preview=body(send(session,"gmail/preview",Map.of()).andExpect(status().isOk())
            .andExpect(jsonPath("$.rows[0].merchant").value("Demo Cafe")).andExpect(jsonPath("$.rows[0].amount").value(711)));
        verified();
        assertThat(preview.toString()).doesNotContain(ACCESS,OWNER,"test-only",MAIL);
        var ticket=Map.of("ticket",preview.at("/rows/0/ticket").asText());
        mvc.perform(get("/api/dashboard").session(session)).andExpect(jsonPath("$.metrics.totalProjectedShortfall").value(2000));
        send(new MockHttpSession(),"gmail/import",ticket).andExpect(status().isUnauthorized());
        send(session,"gmail/import",ticket).andExpect(status().isOk())
            .andExpect(jsonPath("$.dashboard.transactions[0].source").value("GMAIL_IMPORT"))
            .andExpect(jsonPath("$.dashboard.transactions[0].accountId").value("bank-idfc-9101"))
            .andExpect(jsonPath("$.dashboard.bankAccounts[0].effectiveBalance").value(9888.99))
            .andExpect(jsonPath("$.dashboard.metrics.totalProjectedShortfall").value(2711));
        send(session,"gmail/import",ticket).andExpect(status().isConflict());
        searchList();send(session,"gmail/preview",Map.of()).andExpect(jsonPath("$.rows").isEmpty());verified();
        mvc.perform(get("/api/dashboard").session(new MockHttpSession())).andExpect(jsonPath("$.metrics.totalProjectedShortfall").value(2000));
        send(session,"reset",Map.of()).andExpect(status().isOk());
        send(session,"gmail/preview",Map.of()).andExpect(status().isUnauthorized());
    }

    @Test void invalidStateDeniedConsentAndWrongAccountNeverGetMessageBodies() throws Exception {
        var auth=start();
        mvc.perform(get("/api/gmail/callback").session(session).param("state","wrong").param("code","test")).andExpect(status().isSeeOther());
        send(session,"gmail/preview",Map.of()).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/gmail/callback").session(session).param("state",param(auth,"state")).param("error","access_denied"));
        mvc.perform(get("/api/gmail/callback").session(session).param("state",param(auth,"state")).param("code","test"));
        verified();
        exchange(start(),"other@example.test");
        mvc.perform(get("/api/gmail/status").session(session)).andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.message",org.hamcrest.Matchers.containsString("No messages were read")));
    }

    @Test void absenceOfLabelNonExactSubjectAndNonDemoContentLeaveLedgerUntouched() throws Exception {
        exchange(start(),OWNER);
        expectGet("/labels","fields=",Map.of("labels",List.of()));
        send(session,"gmail/preview",Map.of()).andExpect(jsonPath("$.rows").isEmpty());verified();
        searchList();message("metadata",GmailDemoClient.SUBJECT+" forwarded","");
        send(session,"gmail/preview",Map.of()).andExpect(jsonPath("$.rows").isEmpty());verified();
        searchList();message("metadata",GmailDemoClient.SUBJECT,"");message("full",GmailDemoClient.SUBJECT,"Not a synthetic test notice.");
        send(session,"gmail/preview",Map.of()).andExpect(jsonPath("$.rows").isEmpty());verified();
        mvc.perform(get("/api/dashboard").session(session)).andExpect(jsonPath("$.metrics.totalProjectedShortfall").value(2000));
    }

    @Test void editedEmailUsesItsActualAmountAndMerchantAndDisconnectDropsAccess() throws Exception {
        exchange(start(),OWNER);
        searchList();message("metadata",GmailDemoClient.SUBJECT,"");
        message("full",GmailDemoClient.SUBJECT,GmailDemoClient.BODY.replace("711.00","238.40")
            .replace("Demo Cafe","Demo Corner Shop").replace("DEMO711001","DEMO238402"));
        var result=body(send(session,"gmail/preview",Map.of()).andExpect(jsonPath("$.rows[0].merchant").value("Demo Corner Shop")));
        verified();
        send(session,"gmail/import",Map.of("ticket",result.at("/rows/0/ticket").asText()))
            .andExpect(jsonPath("$.dashboard.transactions[0].amount").value(238.40))
            .andExpect(jsonPath("$.dashboard.metrics.totalProjectedShortfall").value(2238.40));
        send(session,"gmail/disconnect",Map.of()).andExpect(jsonPath("$.connected").value(false));
        send(session,"gmail/preview",Map.of()).andExpect(status().isUnauthorized());
    }

    @Test void expiredProviderGrantIsDisconnectedAndNoSensitiveErrorsAreReturned() throws Exception {
        exchange(start(),OWNER);
        wire.server.expect(method(HttpMethod.GET)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
            .body("fake sensitive upstream diagnostics"));
        send(session,"gmail/preview",Map.of()).andExpect(status().isUnauthorized())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("upstream diagnostics"))));
        mvc.perform(get("/api/gmail/status").session(session)).andExpect(jsonPath("$.connected").value(false));
        verified();
    }
}
