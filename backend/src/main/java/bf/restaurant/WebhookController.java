package bf.restaurant;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static bf.restaurant.ApiErrors.require;

@RestController
@RequestMapping("/webhooks/whatsapp")
class WebhookController {
    private final JdbcTemplate db; private final MetaClient meta; private final ObjectMapper json;private final MessageService messages;
    WebhookController(JdbcTemplate db,MetaClient meta,ObjectMapper json,MessageService messages) { this.db=db;this.meta=meta;this.json=json;this.messages=messages; }
    @GetMapping String verify(@RequestParam("hub.mode") String mode,@RequestParam("hub.verify_token") String token,@RequestParam("hub.challenge") String challenge) {
        require(!meta.verifyToken.isBlank() && mode.equals("subscribe") && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),meta.verifyToken.getBytes(StandardCharsets.UTF_8)),403,"Vérification refusée");
        String fingerprint=SecurityConfig.hash(meta.secret+"|"+meta.verifyToken);
        if(db.update("update whatsapp_webhook_verification set configuration_hash=?,verified_at=CURRENT_TIMESTAMP where id=1",fingerprint)==0)db.update("insert into whatsapp_webhook_verification(id,configuration_hash) values(1,?)",fingerprint);
        return challenge;
    }
    static boolean validSignature(byte[] body,String header,String secret) {
        if(secret.isBlank()||header==null||!header.matches("sha256=[a-fA-F0-9]{64}"))return false;
        try { var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(header.substring(7))); }
        catch(Exception e) { return false; }
    }
    @PostMapping @Transactional void receive(@RequestBody byte[] raw,@RequestHeader(value="X-Hub-Signature-256",required=false) String signature) throws Exception {
        require(raw.length<=2_000_000,413,"Notification trop volumineuse");require(validSignature(raw,signature,meta.secret),401,"Signature invalide");
        JsonNode root=json.readTree(raw);require(root.path("object").asText().equals("whatsapp_business_account") && root.path("entry").isArray(),400,"Notification invalide");
        // Serialize deliveries for one business to avoid racing duplicate message IDs.
        db.queryForList("select id from restaurant_settings where id=1 for update");
        for(var entry:root.path("entry")) {
            require(entry.path("id").asText().equals(meta.businessId()),403,"Compte WhatsApp inattendu");
            for(var change:entry.path("changes")) {
                String field=change.path("field").asText();var value=change.path("value");
                String phone=value.path("metadata").path("phone_number_id").asText();
                boolean scoped=Set.of("messages","history","smb_app_state_sync","smb_message_echoes").contains(field);
                require(scoped ? phone.equals(meta.phoneId()) : phone.isBlank() || phone.equals(meta.phoneId()),403,"Numéro WhatsApp inattendu ou absent");
                String canonical=json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(json.convertValue(change,Object.class));
                String key=SecurityConfig.hash(canonical);if(!db.queryForList("select event_key from webhook_event where event_key=?",key).isEmpty())continue;
                db.update("insert into webhook_event(event_key,field,payload,state) values(?,?,?,'RECEIVED')",key,field,change.toString());
                if(field.equals("messages")) { processMessages(value); db.update("update webhook_event set state='PROCESSED' where event_key=?",key); }
                else {
                    db.update("update webhook_event set state=? where event_key=?",Set.of("history","smb_app_state_sync","smb_message_echoes").contains(field)?"QUEUED":"UNSUPPORTED",key);
                }
            }
        }
    }
    private void processMessages(JsonNode value) {
        for(var m:value.path("messages")) {
            String waId=m.path("id").asText(),from=m.path("from").asText();require(!waId.isBlank()&&from.matches("[1-9][0-9]{7,14}")&&m.path("timestamp").asText().matches("[0-9]+"),400,"Message incomplet");
            if(!db.queryForList("select id from message where whatsapp_id=?",waId).isEmpty())continue;
            String phone="+"+from;var customers=db.queryForList("select id from customer where phone=?",phone);UUID customer;
            if(customers.isEmpty()) { customer=UUID.randomUUID();String name=phone;for(var contact:value.path("contacts"))if(contact.path("wa_id").asText().equals(from))name=contact.path("profile").path("name").asText(phone);db.update("insert into customer(id,name,phone) values(?,?,?)",customer,name.substring(0,Math.min(160,name.length())),phone); }
            else customer=(UUID)customers.getFirst().get("id");
            var conversations=db.queryForList("select id from conversation where customer_id=?",customer);UUID conversation;
            if(conversations.isEmpty()) { conversation=UUID.randomUUID();db.update("insert into conversation(id,customer_id) values(?,?)",conversation,customer); }else conversation=(UUID)conversations.getFirst().get("id");
            String type=m.path("type").asText("unknown"),body=type.equals("text")?m.path("text").path("body").asText():"["+type+"]";
            OffsetDateTime timestamp=Instant.ofEpochSecond(Long.parseLong(m.path("timestamp").asText())).atOffset(ZoneOffset.UTC);
            db.update("insert into message(id,conversation_id,whatsapp_id,direction,type,body,media_id,status,created_at) values(?,?,?,'IN',?,?,?,'RECEIVED',?)",UUID.randomUUID(),conversation,waId,type,body,m.path(type).path("id").asText(null),timestamp);
            db.update("update conversation set unread=unread+1,last_inbound_at=case when last_inbound_at is null or last_inbound_at<? then ? else last_inbound_at end,updated_at=CURRENT_TIMESTAMP where id=?",timestamp,timestamp,conversation);
            if(Set.of("stop","désabonner","desabonner","unsubscribe").contains(body.strip().toLowerCase(Locale.ROOT))) {
                db.update("update customer set unsubscribed=true where id=?",customer);
                db.update("insert into consent_record(id,customer_id,granted,scope,source) values(?,?,false,'MARKETING','WHATSAPP_STOP')",UUID.randomUUID(),customer);
            }
        }
        for(var event:value.path("statuses")) {
            String id=event.path("id").asText(),status=event.path("status").asText(),timestamp=event.path("timestamp").asText();require(!id.isBlank()&&timestamp.matches("[0-9]+"),400,"Statut incomplet");String key=SecurityConfig.hash(id+"|"+status+"|"+timestamp);
            if(db.queryForList("select event_key from message_status_event where event_key=?",key).isEmpty()) {
                db.update("insert into message_status_event(event_key,whatsapp_id,status,event_at) values(?,?,?,?)",key,id,status,Instant.ofEpochSecond(Long.parseLong(timestamp)).atOffset(ZoneOffset.UTC));messages.updateStatus(id,status);
            }
        }
    }
}
