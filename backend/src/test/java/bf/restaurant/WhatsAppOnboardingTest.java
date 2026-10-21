package bf.restaurant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"meta.app-id=111","meta.signup-config-id=222","meta.public-url=https://restaurant.test.invalid","meta.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc @ActiveProfiles("test")
class WhatsAppOnboardingTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired WhatsAppCredentials vault;
    @MockitoBean MetaClient meta;
    String nonce,auth;
    @BeforeEach void setup() {
        db.update("delete from whatsapp_credentials");db.update("delete from whatsapp_onboarding");
        ReflectionTestUtils.setField(meta,"version","v26.0");ReflectionTestUtils.setField(meta,"secret","isolated-secret");ReflectionTestUtils.setField(meta,"verifyToken","isolated-verify");
        db.update("delete from whatsapp_webhook_verification");db.update("insert into whatsapp_webhook_verification(id,configuration_hash) values(1,?)",SecurityConfig.hash("isolated-secret|isolated-verify"));
        UUID id=UUID.randomUUID();auth=UUID.randomUUID().toString();
        db.update("insert into app_user(id,email,password_hash,role) values(?,?,?,'ADMIN')",id,id+"@onboarding.test.invalid","unused");
        db.update("insert into app_session(token_hash,user_id,expires_at) values(?,?,?)",SecurityConfig.hash(auth),id,java.time.OffsetDateTime.now().plusHours(1));
        nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        db.update("insert into whatsapp_onboarding(token_hash,actor,expires_at) values(?,?,?)",SecurityConfig.hash(nonce),id,java.time.OffsetDateTime.now().plusMinutes(10));
    }
    @AfterEach void cleanup() { db.update("delete from whatsapp_credentials");db.update("delete from whatsapp_onboarding"); }
    @Test void ciphertextIsRandomizedAuthenticatedAndNotPlaintext() {
        String first=vault.encrypt("test-only-token"),second=vault.encrypt("test-only-token");
        assertNotEquals(first,second);assertFalse(first.contains("test-only-token"));assertEquals("test-only-token",vault.decrypt(first));
        byte[] corrupt=Base64.getDecoder().decode(first);corrupt[20]^=1;
        assertThrows(IllegalStateException.class,()->vault.decrypt(Base64.getEncoder().encodeToString(corrupt)));
    }
    @Test void startRequiresAdminAndNeverReturnsSecrets() throws Exception {
        mvc.perform(post("/api/whatsapp/onboarding")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/whatsapp/onboarding").header("Authorization","Bearer "+auth)).andExpect(status().isOk()).andExpect(jsonPath("$.expiresIn").value(600)).andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("https://restaurant.test.invalid/connect/whatsapp#"))).andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(get("/connect/whatsapp/config").header("X-Onboarding-Token","invalid")).andExpect(status().isUnauthorized());
        db.update("update whatsapp_onboarding set expires_at=?",java.time.OffsetDateTime.now().minusMinutes(1));
        mvc.perform(get("/connect/whatsapp/config").header("X-Onboarding-Token",nonce)).andExpect(status().isUnauthorized());
    }
    @Test void codeIsExchangedOnceAndEncrypted() throws Exception {
        when(meta.authorized(eq("GET"),startsWith("oauth/access_token?"),isNull(),anyString())).thenReturn(json.readTree("{\"access_token\":\"isolated-test-token\"}"));
        when(meta.authorized(eq("GET"),startsWith("debug_token?"),isNull(),anyString())).thenReturn(json.readTree("{\"data\":{\"is_valid\":true,\"app_id\":\"111\",\"scopes\":[\"whatsapp_business_management\",\"whatsapp_business_messaging\"]}}"));
        mvc.perform(post("/connect/whatsapp/exchange").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"code\":\"isolated-code\"}")).andExpect(status().isOk());
        String encrypted=db.queryForObject("select encrypted_token from whatsapp_onboarding where token_hash=?",String.class,SecurityConfig.hash(nonce));assertEquals("isolated-test-token",vault.decrypt(encrypted));
        mvc.perform(post("/connect/whatsapp/exchange").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"code\":\"isolated-code\"}")).andExpect(status().isConflict());
        verify(meta,times(1)).authorized(eq("GET"),startsWith("oauth/access_token?"),isNull(),anyString());
    }
    void authorize() { db.update("update whatsapp_onboarding set state='AUTHORIZED',encrypted_token=? where token_hash=?",vault.encrypt("isolated-test-token"),SecurityConfig.hash(nonce)); }
    @Test void standardSignupAndUnconfirmedCoexistenceAreRejected() throws Exception {
        authorize();
        mvc.perform(post("/connect/whatsapp/inspect").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"wabaId\":\"123\",\"phoneId\":\"456\",\"event\":\"FINISH\"}")).andExpect(status().isConflict());
        when(meta.authorized(eq("GET"),startsWith("123/phone_numbers"),isNull(),anyString())).thenReturn(json.readTree("{\"data\":[{\"id\":\"456\",\"is_on_biz_app\":false,\"platform_type\":\"CLOUD_API\"}]}"));
        mvc.perform(post("/connect/whatsapp/inspect").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"wabaId\":\"123\",\"phoneId\":\"456\",\"event\":\"FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING\"}")).andExpect(status().isConflict());
        assertTrue(vault.active().isEmpty());verify(meta,never()).authorized(eq("POST"),anyString(),any(),anyString());
    }
    @Test void confirmedCoexistenceSubscribesAndRequestsEachSyncExactlyOnce() throws Exception {
        authorize();
        when(meta.authorized(eq("GET"),startsWith("123/phone_numbers"),isNull(),anyString())).thenReturn(json.readTree("{\"data\":[{\"id\":\"456\",\"display_phone_number\":\"+22670000000\",\"verified_name\":\"Test isolé\",\"is_on_biz_app\":true,\"platform_type\":\"CLOUD_API\"}]}"));
        mvc.perform(post("/connect/whatsapp/inspect").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"wabaId\":\"123\",\"phoneId\":\"\",\"event\":\"FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING\"}")).andExpect(status().isOk());
        when(meta.authorized(eq("POST"),eq("123/subscribed_apps"),any(),anyString())).thenReturn(json.readTree("{\"success\":true}"));
        when(meta.authorized(eq("POST"),eq("456/smb_app_data"),any(),anyString())).thenReturn(json.readTree("{\"request_id\":\"isolated-request\"}"));
        mvc.perform(post("/connect/whatsapp/confirm").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"confirmed\":false}")).andExpect(status().isBadRequest());
        mvc.perform(post("/connect/whatsapp/confirm").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("AWAITING_WEBHOOKS"));
        mvc.perform(post("/connect/whatsapp/confirm").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isConflict());
        assertEquals("456",vault.active().get("phone_id"));
        verify(meta,times(2)).authorized(eq("POST"),eq("456/smb_app_data"),any(),anyString());
        verify(meta,never()).authorized(anyString(),contains("/register"),any(),anyString());
    }
    @Test void ambiguousExchangeIsNeverRetried() throws Exception {
        when(meta.authorized(eq("GET"),startsWith("oauth/access_token?"),isNull(),anyString())).thenThrow(new MetaClient.MetaFailure("META_UNCONFIRMED",true));
        mvc.perform(post("/connect/whatsapp/exchange").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"code\":\"test\"}")).andExpect(status().is5xxServerError());
        mvc.perform(post("/connect/whatsapp/exchange").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"code\":\"test\"}")).andExpect(status().isConflict());
        verify(meta,times(1)).authorized(eq("GET"),startsWith("oauth/access_token?"),isNull(),anyString());
    }
    @Test void tokenForAnotherMetaAppCannotBeActivated() throws Exception {
        when(meta.authorized(eq("GET"),startsWith("oauth/access_token?"),isNull(),anyString())).thenReturn(json.readTree("{\"access_token\":\"isolated-test-token\"}"));
        when(meta.authorized(eq("GET"),startsWith("debug_token?"),isNull(),anyString())).thenReturn(json.readTree("{\"data\":{\"is_valid\":true,\"app_id\":\"999\"}}"));
        mvc.perform(post("/connect/whatsapp/exchange").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"code\":\"isolated-code\"}")).andExpect(status().isForbidden());
        assertTrue(vault.active().isEmpty());
    }
    @Test void unverifiedWebhookBlocksSignupAndEmployeeCannotStart() throws Exception {
        db.update("delete from whatsapp_webhook_verification");
        mvc.perform(post("/api/whatsapp/onboarding").header("Authorization","Bearer "+auth)).andExpect(status().isServiceUnavailable());
        db.update("update app_user set role='EMPLOYEE' where id=(select user_id from app_session where token_hash=?)",SecurityConfig.hash(auth));
        mvc.perform(post("/api/whatsapp/onboarding").header("Authorization","Bearer "+auth)).andExpect(status().isForbidden());
    }
    @Test void ambiguousActivationKeepsIdentityAndCannotRepeatItsSyncCalls() throws Exception {
        db.update("update whatsapp_onboarding set state='REVIEW',encrypted_token=?,waba_id='123',phone_id='456',display_phone='+22670000000',verified_name='Test isolé' where token_hash=?",vault.encrypt("isolated-test-token"),SecurityConfig.hash(nonce));
        when(meta.authorized(eq("POST"),eq("123/subscribed_apps"),any(),anyString())).thenReturn(json.readTree("{\"success\":true}"));
        when(meta.authorized(eq("POST"),eq("456/smb_app_data"),any(),anyString())).thenThrow(new MetaClient.MetaFailure("META_UNCONFIRMED",true));
        mvc.perform(post("/connect/whatsapp/confirm").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isBadGateway());
        mvc.perform(post("/connect/whatsapp/confirm").header("X-Onboarding-Token",nonce).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isConflict());
        assertEquals("456",vault.active().get("phone_id"));
        verify(meta,times(1)).authorized(eq("POST"),eq("456/smb_app_data"),any(),anyString());
        assertEquals("ACTION_REQUIRED",db.queryForObject("select state from whatsapp_onboarding where token_hash=?",String.class,SecurityConfig.hash(nonce)));
    }
}
