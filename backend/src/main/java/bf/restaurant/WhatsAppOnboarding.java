package bf.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import static bf.restaurant.ApiErrors.require;

/** Browser receives only a short lived capability, never the application or Graph token. */
@RestController
class WhatsAppOnboarding {
    final JdbcTemplate db;final MetaClient meta;final WhatsAppCredentials vault;final TransactionTemplate tx;
    final String appId,configId,publicUrl;
    WhatsAppOnboarding(JdbcTemplate db,MetaClient meta,WhatsAppCredentials vault,TransactionTemplate tx,
        @Value("${meta.app-id:}") String appId,@Value("${meta.signup-config-id:}") String configId,
        @Value("${meta.public-url:}") String publicUrl) {
        this.db=db;this.meta=meta;this.vault=vault;this.tx=tx;this.appId=appId;this.configId=configId;this.publicUrl=publicUrl.replaceAll("/+$","");
    }
    List<String> missing() {
        var missing=new ArrayList<String>();
        if(!appId.matches("[0-9]+"))missing.add("META_APP_ID : identifiant de l’application Meta");
        if(!configId.matches("[0-9]+"))missing.add("META_EMBEDDED_SIGNUP_CONFIG_ID : configuration Facebook Login for Business avec coexistence");
        if(!meta.version.matches("v[0-9]+\\.0"))missing.add("META_GRAPH_API_VERSION : version Graph supportée");
        if(meta.secret.isBlank())missing.add("META_APP_SECRET : secret conservé sur le serveur");
        if(meta.verifyToken.isBlank())missing.add("WHATSAPP_WEBHOOK_VERIFY_TOKEN : vérification du webhook");
        if(!vault.ready())missing.add("WHATSAPP_TOKEN_ENCRYPTION_KEY : clé AES de 32 octets en Base64");
        if(db.queryForObject("select count(*) from whatsapp_webhook_verification where configuration_hash=?",Long.class,SecurityConfig.hash(meta.secret+"|"+meta.verifyToken))==0)missing.add("Webhook Meta : terminer sa vérification et souscrire messages, history, smb_app_state_sync, smb_message_echoes et account_update dans Meta");
        try { URI uri=URI.create(publicUrl);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("", "/").contains(uri.getPath()))throw new IllegalArgumentException(); }
        catch(Exception e) { missing.add("PUBLIC_API_URL : adresse HTTPS publique du serveur, sans chemin"); }
        return missing;
    }
    @PostMapping("/api/whatsapp/onboarding") @PreAuthorize("hasRole('ADMIN')")
    Object start(Principal principal) {
        require(missing().isEmpty(),503,"Connexion Meta indisponible : la configuration serveur doit être terminée.");
        require(vault.active().isEmpty(),409,"Un numéro est déjà associé. Aucun remplacement automatique autorisé.");
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("delete from whatsapp_onboarding where expires_at<CURRENT_TIMESTAMP");
        db.update("insert into whatsapp_onboarding(token_hash,actor,expires_at) values(?,?,?)",SecurityConfig.hash(nonce),UUID.fromString(principal.getName()),OffsetDateTime.now().plusMinutes(10));
        return Map.of("url",publicUrl+"/connect/whatsapp#"+nonce,"expiresIn",600);
    }
    @GetMapping(value="/connect/whatsapp",produces=MediaType.TEXT_HTML_VALUE)
    ResponseEntity<?> page() {
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
            .header("Content-Security-Policy","default-src 'self'; script-src 'self' https://connect.facebook.net; connect-src 'self' https://*.facebook.com https://*.facebook.net; frame-src https://*.facebook.com; img-src 'self' https://*.facebook.com https://*.fbcdn.net data:; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            .body(new ClassPathResource("onboarding/index.html"));
    }
    @GetMapping(value="/connect/whatsapp.js",produces="application/javascript")
    ResponseEntity<?> script() { return ResponseEntity.ok().header("Cache-Control","no-store").body(new ClassPathResource("onboarding/signup.js")); }
    @GetMapping(value="/connect/whatsapp.css",produces="text/css")
    ResponseEntity<?> css() { return ResponseEntity.ok().body(new ClassPathResource("onboarding/signup.css")); }
    Map<String,Object> session(String nonce) {
        require(nonce!=null&&nonce.matches("[A-Za-z0-9_-]{43}"),401,"Lien de connexion invalide");
        var rows=db.queryForList("select o.* from whatsapp_onboarding o join app_user u on u.id=o.actor where o.token_hash=? and o.expires_at>CURRENT_TIMESTAMP and u.enabled=true and u.role='ADMIN'",SecurityConfig.hash(nonce));
        require(!rows.isEmpty(),401,"Lien expiré. Recommencez depuis les paramètres de l’application.");return rows.getFirst();
    }
    @GetMapping("/connect/whatsapp/config")
    ResponseEntity<?> config(@RequestHeader("X-Onboarding-Token") String nonce) {
        session(nonce);return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("appId",appId,"configId",configId,"version",meta.version));
    }
    record Code(@NotBlank @Size(max=4096) String code) {}
    @PostMapping("/connect/whatsapp/exchange")
    void exchange(@RequestHeader("X-Onboarding-Token") String nonce,@Valid @RequestBody Code body) {
        session(nonce);String key=SecurityConfig.hash(nonce);
        require(db.update("update whatsapp_onboarding set state='EXCHANGING' where token_hash=? and state='CREATED'",key)==1,409,"Code déjà traité. Aucun échange automatique supplémentaire.");
        try {
            String query="oauth/access_token?client_id="+appId+"&client_secret="+encode(meta.secret)+"&code="+encode(body.code());
            JsonNode response=meta.authorized("GET",query,null,appId+"|"+meta.secret);
            String token=response.path("access_token").asText();require(!token.isBlank(),502,"Meta n’a pas fourni de jeton");
            JsonNode debug=meta.authorized("GET","debug_token?input_token="+encode(token),null,appId+"|"+meta.secret).path("data");
            require(debug.path("is_valid").asBoolean(false)&&debug.path("app_id").asText().equals(appId),403,"Autorisation Meta invalide pour cette application");
            var scopes=new HashSet<String>();debug.path("scopes").forEach(s->scopes.add(s.asText()));
            require(scopes.containsAll(Set.of("whatsapp_business_management","whatsapp_business_messaging")),403,"Autorisations WhatsApp insuffisantes");
            db.update("update whatsapp_onboarding set encrypted_token=?,state='AUTHORIZED' where token_hash=?",vault.encrypt(token),key);
        } catch(Exception e) { db.update("update whatsapp_onboarding set state='FAILED',error_code='TOKEN_EXCHANGE_FAILED' where token_hash=?",key);throw e; }
    }
    record Selection(@Pattern(regexp="[0-9]{1,80}") @NotNull String wabaId,
        @Pattern(regexp="[0-9]{0,80}") @NotNull String phoneId,
        @NotNull String event) {}
    @PostMapping("/connect/whatsapp/inspect")
    Object inspect(@RequestHeader("X-Onboarding-Token") String nonce,@Valid @RequestBody Selection body) {
        var current=session(nonce);require("AUTHORIZED".equals(current.get("state")),409,"Autorisation Meta non terminée");
        require("FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING".equals(body.event()),409,"Meta n’a pas confirmé le parcours de coexistence. Connexion interrompue, sans enregistrement du numéro.");
        String token=vault.decrypt((String)current.get("encrypted_token"));
        var result=meta.authorized("GET",body.wabaId()+"/phone_numbers?fields=id,display_phone_number,verified_name,is_on_biz_app,platform_type&limit=100",null,token);
        var candidates=new ArrayList<JsonNode>();
        for(var phone:result.path("data"))if((body.phoneId().isBlank()||phone.path("id").asText().equals(body.phoneId()))&&phone.path("is_on_biz_app").asBoolean(false)&&phone.path("platform_type").asText().equals("CLOUD_API"))candidates.add(phone);
        require(candidates.size()==1,409,"Impossible d’identifier un numéro unique en coexistence confirmé par Meta. Aucune migration effectuée.");
        JsonNode phone=candidates.getFirst();
        require(phone.path("id").asText().matches("[0-9]+"),502,"Identifiant de numéro Meta invalide");
        require(db.update("update whatsapp_onboarding set state='REVIEW',waba_id=?,phone_id=?,display_phone=?,verified_name=? where token_hash=? and state='AUTHORIZED'",body.wabaId(),phone.path("id").asText(),phone.path("display_phone_number").asText(),phone.path("verified_name").asText(),SecurityConfig.hash(nonce))==1,409,"Sélection déjà traitée");
        return Map.of("phone",phone.path("display_phone_number").asText(),"name",phone.path("verified_name").asText(),"wabaId",body.wabaId(),"coexistence",true);
    }
    record Confirmation(boolean confirmed) {}
    @PostMapping("/connect/whatsapp/confirm")
    Object confirm(@RequestHeader("X-Onboarding-Token") String nonce,@RequestBody Confirmation body) {
        require(body.confirmed(),400,"Confirmation explicite requise");String key=SecurityConfig.hash(nonce);
        var current=tx.execute(s->{
            var item=session(nonce);db.queryForList("select id from restaurant_settings where id=1 for update");
            require(vault.active().isEmpty(),409,"Un numéro est déjà associé");
            require(db.update("update whatsapp_onboarding set state='ACTIVATING' where token_hash=? and state='REVIEW'",key)==1,409,"Activation déjà traitée ou non autorisée");
            db.update("insert into whatsapp_credentials(id,encrypted_token,waba_id,phone_id,display_phone,verified_name) values(1,?,?,?,?,?)",item.get("encrypted_token"),item.get("waba_id"),item.get("phone_id"),item.get("display_phone"),item.get("verified_name"));
            db.update("insert into audit_log(id,actor,action,target) values(?,?,'WHATSAPP_COEXISTENCE_CONFIRMED',?)",UUID.randomUUID(),item.get("actor"),item.get("phone_id"));return item;
        });
        String token=vault.decrypt((String)current.get("encrypted_token"));
        try {
            var subscribed=meta.authorized("POST",current.get("waba_id")+"/subscribed_apps",Map.of(),token);
            require(subscribed.path("success").asBoolean(false),502,"Souscription webhook non confirmée par Meta");
            // Each official sync is one-shot: persist the claim BEFORE calling Meta; never retry on timeout.
            sync(current,token,"smb_app_state_sync");sync(current,token,"history");
            db.update("update whatsapp_onboarding set state='COMPLETED',encrypted_token=null where token_hash=?",key);
            return Map.of("state","AWAITING_WEBHOOKS","message","Coexistence confirmée par Meta. Synchronisation demandée ; réception des événements à vérifier dans l’application.");
        } catch(Exception e) {
            db.update("update whatsapp_onboarding set state='ACTION_REQUIRED',error_code='ACTIVATION_UNCONFIRMED',encrypted_token=null where token_hash=?",key);
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_GATEWAY,"Numéro associé, mais souscription ou synchronisation non confirmée. Ne recommencez pas l’intégration : consultez les erreurs serveur et Meta.");
        }
    }
    private void sync(Map<String,Object> current,String token,String type) {
        UUID id=UUID.randomUUID();db.update("insert into sync_run(id,field,state,details) values(?,?,'REQUESTING','Requête officielle à usage unique. Aucun résultat confirmé à ce stade.')",id,type);
        try {
            var result=meta.authorized("POST",current.get("phone_id")+"/smb_app_data",Map.of("messaging_product","whatsapp","sync_type",type),token);
            String requestId=result.path("request_id").asText();require(!requestId.isBlank(),502,"Identifiant de synchronisation absent");
            db.update("update sync_run set state='REQUESTED',details=? where id=?","Requête acceptée par Meta : "+requestId,id);
        } catch(Exception e) { db.update("update sync_run set state='UNKNOWN',details='Résultat non confirmé. Aucune répétition automatique de cette requête à usage unique.' where id=?",id);throw e; }
    }
    static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
}
