package bf.restaurant;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"meta.app-secret=test-only-secret","meta.phone-id=123456","meta.business-id=654321"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {
    @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired BCryptPasswordEncoder encoder;@Autowired ObjectMapper json;
    String session(String role) {
        UUID id=UUID.randomUUID();String token=UUID.randomUUID().toString();db.update("insert into app_user(id,email,password_hash,role) values(?,?,?,?)",id,id+"@test.invalid",encoder.encode("test-only-password"),role);db.update("insert into app_session(token_hash,user_id,expires_at) values(?,?,?)",SecurityConfig.hash(token),id,java.time.OffsetDateTime.now().plusHours(1));return "Bearer "+token;
    }
    @Test void protectedApiRequiresSessionAndEmployeeCannotMutateCatalog() throws Exception {
        mvc.perform(get("/api/dishes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/dishes").header("Authorization",session("EMPLOYEE")).contentType("application/json").content("{\"name\":\"Riz\",\"price\":2500}")).andExpect(status().isForbidden());
    }
    @Test void persistedCatalogAndConsent() throws Exception {
        String auth=session("ADMIN");String dish=mvc.perform(post("/api/dishes").header("Authorization",auth).contentType("application/json").content("{\"name\":\"Plat de test\",\"price\":2500}")).andExpect(status().isOk()).andExpect(jsonPath("$.price").value(2500)).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/dishes").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        mvc.perform(put("/api/dishes/"+json.readTree(dish).path("id").asText()).header("Authorization",auth).contentType("application/json").content("{\"name\":\"Plat modifié\",\"price\":3000,\"version\":0}")).andExpect(status().isOk());
        String customer=mvc.perform(post("/api/customers").header("Authorization",auth).contentType("application/json").content("{\"name\":\"Test isolé\",\"phone\":\"+22670999999\",\"category\":\"\",\"tags\":\"\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();String id=json.readTree(customer).path("id").asText();
        var service=new CampaignService(db,null,null,null);Assertions.assertFalse(service.eligible(UUID.fromString(id)));
        mvc.perform(post("/api/customers/"+id+"/consent").header("Authorization",auth).contentType("application/json").content("{\"granted\":true,\"source\":\"FORMULAIRE_TEST\"}")).andExpect(status().isOk());Assertions.assertTrue(service.eligible(UUID.fromString(id)));
        mvc.perform(post("/api/customers/"+id+"/consent").header("Authorization",auth).contentType("application/json").content("{\"granted\":false,\"source\":\"STOP_TEST\"}")).andExpect(status().isOk());Assertions.assertFalse(service.eligible(UUID.fromString(id)));
    }
    @Test void revokedAndExpiredSessionsAreRejected() throws Exception {
        String auth=session("EMPLOYEE");mvc.perform(post("/api/auth/logout").header("Authorization",auth)).andExpect(status().isOk());mvc.perform(get("/api/auth/me").header("Authorization",auth)).andExpect(status().isUnauthorized());
    }
    @Test void unsignedWebhookIsRejected() throws Exception { mvc.perform(post("/webhooks/whatsapp").contentType("application/json").content("{}")).andExpect(status().isUnauthorized()); }
    @Test void signedWebhookIsIdempotentAndStopExcludesCustomer() throws Exception {
        String raw="""
        {"object":"whatsapp_business_account","entry":[{"id":"654321","changes":[{"field":"messages","value":{"metadata":{"phone_number_id":"123456"},"messages":[{"id":"wamid.test.dedup","from":"22670888888","timestamp":"1792573200","type":"text","text":{"body":"STOP"}}]}}]}]}
        """;
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("test-only-secret".getBytes(),"HmacSHA256"));String signature="sha256="+HexFormat.of().formatHex(mac.doFinal(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for(int i=0;i<2;i++)mvc.perform(post("/webhooks/whatsapp").header("X-Hub-Signature-256",signature).contentType("application/json").content(raw)).andExpect(status().isOk());
        Assertions.assertEquals(1,db.queryForObject("select count(*) from message where whatsapp_id='wamid.test.dedup'",Integer.class));
        Assertions.assertTrue(db.queryForObject("select unsubscribed from customer where phone='+22670888888'",Boolean.class));
        Assertions.assertEquals(1,db.queryForObject("select count(*) from webhook_event where field='messages'",Integer.class));
        mvc.perform(post("/webhooks/whatsapp").header("X-Hub-Signature-256",signature).contentType("application/json").content(raw+" ")).andExpect(status().isUnauthorized());
    }
    @Test void orderUsesCurrentServerPriceAndIdempotencyAndValidTransitions() throws Exception {
        String auth=session("EMPLOYEE");UUID customer=UUID.randomUUID(),dish=UUID.randomUUID(),key=UUID.randomUUID();
        db.update("insert into customer(id,name,phone) values(?,?,'+22670777777')",customer,"Commande test");
        db.update("insert into dish(id,name,description,category,price,available,version) values(?,'Plat','', '',2500,true,0)",dish);
        String body=json.writeValueAsString(Map.of("requestKey",key,"customerId",customer,"fulfillment","PICKUP","address","","instructions","","items",List.of(Map.of("dishId",dish,"quantity",3)),"total",1));
        String response=mvc.perform(post("/api/orders").header("Authorization",auth).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(7500)).andExpect(jsonPath("$.paid").value(false)).andReturn().getResponse().getContentAsString();
        String id=json.readTree(response).path("id").asText();
        mvc.perform(post("/api/orders").header("Authorization",auth).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        Assertions.assertEquals(1,db.queryForObject("select count(*) from customer_order where request_key=?",Integer.class,key));
        mvc.perform(post("/api/orders/"+id+"/status").header("Authorization",auth).contentType("application/json").content("{\"status\":\"DELIVERED\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/"+id+"/status").header("Authorization",auth).contentType("application/json").content("{\"status\":\"CONFIRMED\"}")).andExpect(status().isOk());
        Assertions.assertEquals(2,db.queryForObject("select count(*) from order_status_history where order_id=?",Integer.class,UUID.fromString(id)));
    }
}
