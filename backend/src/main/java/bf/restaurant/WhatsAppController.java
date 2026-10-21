package bf.restaurant;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/whatsapp")
@PreAuthorize("hasRole('ADMIN')")
class WhatsAppController {
    final MetaClient meta;final JdbcTemplate db;final WhatsAppOnboarding onboarding;final WhatsAppCredentials credentials;
    WhatsAppController(MetaClient meta,JdbcTemplate db,WhatsAppOnboarding onboarding,WhatsAppCredentials credentials) { this.meta=meta;this.db=db;this.onboarding=onboarding;this.credentials=credentials; }
    @GetMapping("/status") Object status() {
        var active=credentials.active();boolean associated=!active.isEmpty();
        var result=new LinkedHashMap<String,Object>();result.put("configured",meta.configured());result.put("connection",associated?"COEXISTENCE_CONFIRMED_AT_ONBOARDING":"UNVERIFIED");result.put("phone",active.get("display_phone"));result.put("coexistence",associated?"CONFIRMED_AT_ONBOARDING":"AWAITING_CONFIRMATION");result.put("historySync","AWAITING_CONFIRMATION");
        result.put("progress",db.queryForObject("select max(progress) from whatsapp_sync_progress",Integer.class));
        result.put("lastSync",db.queryForList("select field,state,details,received_at from sync_run order by received_at desc limit 10"));
        boolean observed=db.queryForObject("select count(*) from webhook_event where state='PROCESSED' and field in ('history','smb_app_state_sync','smb_message_echoes')",Long.class)>0;
        for(var entry:Map.of("contactsImported","CONTACT","conversationsImported","CONVERSATION","messagesImported","MESSAGE").entrySet())result.put(entry.getKey(),observed?db.queryForObject("select count(*) from whatsapp_import where kind=?",Long.class,entry.getValue()):null);
        result.put("lastSyncAt",db.queryForList("select max(received_at) received_at from webhook_event where state='PROCESSED' and field in ('history','smb_app_state_sync','smb_message_echoes')").getFirst().get("received_at"));
        result.put("pendingEvents",db.queryForObject("select count(*) from webhook_event where state in ('AWAITING_SCHEMA_REVIEW','QUEUED')",Long.class));
        result.put("syncErrors",db.queryForList("select field,error_code,received_at from webhook_event where state='FAILED' order by received_at desc limit 10"));
        result.put("onboardingErrors",db.queryForList("select state,error_code from whatsapp_onboarding where state in ('FAILED','ACTION_REQUIRED','EXCHANGING','ACTIVATING') order by expires_at desc limit 1"));
        result.put("lastApiCheck",db.queryForList("select display_phone,verified_name,checked_at from whatsapp_connection_check where phone_id=? and business_id=? order by checked_at desc limit 1",meta.phoneId(),meta.businessId()));
        result.put("lastWebhook",db.queryForList("select received_at from webhook_event where state='PROCESSED' order by received_at desc limit 1"));
        result.put("onboardingAvailable",onboarding.missing().isEmpty()&&!associated);result.put("missingConfiguration",onboarding.missing());
        result.put("reason",associated?"Numéro identifié par Meta lors de la connexion. Réception des messages et synchronisation à vérifier avec les événements réels.":"La connexion nécessite une application Meta autorisée, une configuration Embedded Signup avec coexistence et un serveur HTTPS public. L’éligibilité du numéro est décidée par Meta.");return result;
    }
    @PostMapping("/verify") Object verify() {
        var phone=meta.request("GET",meta.phoneId()+"?fields=id,display_phone_number,verified_name,quality_rating,is_on_biz_app,platform_type",null);
        db.update("insert into whatsapp_connection_check(id,phone_id,business_id,display_phone,verified_name) values(?,?,?,?,?)",UUID.randomUUID(),meta.phoneId(),meta.businessId(),phone.path("display_phone_number").asText(),phone.path("verified_name").asText());
        return Map.of("phone",phone,"connection","API_ACCESS_CONFIRMED","coexistence",phone.path("is_on_biz_app").asBoolean(false)&&phone.path("platform_type").asText().equals("CLOUD_API")?"CONFIRMED":"AWAITING_CONFIRMATION","webhook","AWAITING_REAL_DELIVERY");
    }
    @PostMapping("/events/{key}/retry") Object retry(@PathVariable String key) {
        bf.restaurant.ApiErrors.require(key.matches("[a-f0-9]{64}"),400,"Identifiant invalide");
        bf.restaurant.ApiErrors.require(db.update("update webhook_event set state='QUEUED',error_code=null where event_key=? and state='FAILED' and field in ('history','smb_app_state_sync','smb_message_echoes')",key)==1,409,"Événement absent ou non rejouable");
        return Map.of("state","QUEUED","message","Retraitement local demandé. Aucun message ni appel de synchronisation n’est envoyé à Meta.");
    }
}
