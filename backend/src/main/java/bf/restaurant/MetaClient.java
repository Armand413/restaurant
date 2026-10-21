package bf.restaurant;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static bf.restaurant.ApiErrors.require;

@Component
class MetaClient {
    final String version,phoneId,businessId,token,verifyToken,secret,language;
    private final ObjectMapper json;
    private final WhatsAppCredentials credentials;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    MetaClient(ObjectMapper json,WhatsAppCredentials credentials,@Value("${meta.version}") String version,@Value("${meta.phone-id}") String phoneId,@Value("${meta.business-id}") String businessId,@Value("${meta.token}") String token,@Value("${meta.verify-token}") String verifyToken,@Value("${meta.app-secret}") String secret,@Value("${meta.language}") String language) {
        this.credentials=credentials;
        this.json=json;this.version=version;this.phoneId=phoneId;this.businessId=businessId;this.token=token;this.verifyToken=verifyToken;this.secret=secret;this.language=language;
    }
    String phoneId() { return (String)credentials.active().getOrDefault("phone_id",phoneId); }
    String businessId() { return (String)credentials.active().getOrDefault("waba_id",businessId); }
    private String accessToken() { var active=credentials.active();return active.isEmpty()?token:credentials.decrypt((String)active.get("encrypted_token")); }
    boolean configured() {
        try { return version.matches("v[0-9]+\\.0") && phoneId().matches("[0-9]+") && businessId().matches("[0-9]+") && !accessToken().isBlank() && !secret.isBlank() && !verifyToken.isBlank(); }
        catch(IllegalStateException e) { return false; }
    }
    JsonNode request(String method,String path,Object body) {
        require(configured(),503,"WhatsApp non configuré sur le serveur");
        return authorized(method,path,body,accessToken());
    }
    JsonNode authorized(String method,String path,Object body,String accessToken) {
        require(version.matches("v[0-9]+\\.0"),503,"Version Graph API manquante");
        try {
            var builder=HttpRequest.newBuilder(URI.create("https://graph.facebook.com/"+version+"/"+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+accessToken).header("Content-Type","application/json");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200 || response.statusCode()>=300) throw new MetaFailure("META_HTTP_"+response.statusCode(),response.statusCode()>=500);
            return json.readTree(response.body());
        } catch(MetaFailure e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new MetaFailure("META_INTERRUPTED",true); }
        catch(Exception e) { throw new MetaFailure("META_UNCONFIRMED",true); }
    }
    String send(String phone,String type,Object content) {
        var response=request("POST",phoneId()+"/messages",Map.of("messaging_product","whatsapp","to",phone.replace("+",""),"type",type,type,content));
        String id=response.path("messages").path(0).path("id").asText();if(id.isBlank())throw new MetaFailure("META_NO_MESSAGE_ID",true);return id;
    }
    static class MetaFailure extends RuntimeException {
        final String code;final boolean ambiguous;
        MetaFailure(String code,boolean ambiguous) { super(code);this.code=code;this.ambiguous=ambiguous; }
    }
}
