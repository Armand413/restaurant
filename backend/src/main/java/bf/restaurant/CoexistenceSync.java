package bf.restaurant;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import static bf.restaurant.ApiErrors.require;

/** Processes the durable inbox, including events preserved by previous releases. */
@Component
class CoexistenceSync {
    final JdbcTemplate db;final ObjectMapper json;final TransactionTemplate tx;final MessageService messages;
    CoexistenceSync(JdbcTemplate db,ObjectMapper json,TransactionTemplate tx,MessageService messages) { this.db=db;this.json=json;this.tx=tx;this.messages=messages; }
    @Scheduled(fixedDelay=1000) void drain() {
        var rows=db.queryForList("select event_key from webhook_event where state in ('QUEUED','AWAITING_SCHEMA_REVIEW') and field in ('history','smb_app_state_sync','smb_message_echoes') order by received_at limit 5");
        for(var row:rows) {
            String key=(String)row.get("event_key");
            try { tx.executeWithoutResult(s->process(key)); }
            catch(Exception e) {
                // No payload, phone number, message body or exception text in logs/error metadata.
                db.update("update webhook_event set state='FAILED',error_code='INVALID_SYNC_PAYLOAD' where event_key=? and state<>'PROCESSED'",key);
                db.update("insert into sync_run(id,field,state,details) select ?,field,'FAILED','Événement non importé : structure invalide ou incohérente. Une analyse technique est nécessaire.' from webhook_event where event_key=?",UUID.randomUUID(),key);
            }
        }
    }
    void process(String key) {
        db.queryForList("select id from restaurant_settings where id=1 for update");
        var rows=db.queryForList("select * from webhook_event where event_key=? and state in ('QUEUED','AWAITING_SCHEMA_REVIEW')",key);
        if(rows.isEmpty())return;
        var row=rows.getFirst();JsonNode value;
        try { value=json.readTree((String)row.get("payload")).path("value"); } catch(Exception e) { throw new IllegalArgumentException(); }
        String field=(String)row.get("field");
        switch(field) {
            case "smb_app_state_sync" -> contacts(value);
            case "smb_message_echoes" -> { require(value.path("message_echoes").isArray(),400,"Échos absents");for(var m:value.path("message_echoes"))importMessage(m,m.path("to").asText(),true,"WHATSAPP_APP",true); }
            case "history" -> history(value,key);
            default -> throw new IllegalArgumentException();
        }
        db.update("update webhook_event set state='PROCESSED',error_code=null where event_key=?",key);
    }
    private void history(JsonNode value,String key) {
        require(value.path("history").isArray()||value.path("messages").isArray(),400,"Historique absent");
        for(var chunk:value.path("history")) {
            if(chunk.path("errors").isArray()&&!chunk.path("errors").isEmpty()) {
                for(var error:chunk.path("errors")) db.update("insert into sync_run(id,field,state,details) values(?,'history','REFUSED',?)",UUID.randomUUID(),error.path("code").asInt()==2593109?"Partage de l’historique refusé ou désactivé dans WhatsApp Business.":"Meta a signalé une erreur de synchronisation. Code : "+error.path("code").asInt());
                continue;
            }
            var metadata=chunk.path("metadata");
            require(metadata.path("phase").isIntegralNumber()&&metadata.path("chunk_order").isIntegralNumber()&&metadata.path("progress").isIntegralNumber()&&chunk.path("threads").isArray(),400,"Segment invalide");
            int phase=metadata.path("phase").asInt(),order=metadata.path("chunk_order").asInt(),progress=metadata.path("progress").asInt();
            require(phase>=0&&phase<=2&&order>=0&&progress>=0&&progress<=100,400,"Progression invalide");
            String business=value.path("metadata").path("display_phone_number").asText().replaceAll("[^0-9]","");
            for(var thread:chunk.path("threads")) {
                String peer=thread.path("id").asText();require(thread.path("messages").isArray(),400,"Fil incomplet");
                for(var m:thread.path("messages")) {
                    String from=m.path("from").asText();
                    require(from.equals(peer)||(!business.isBlank()&&from.equals(business)),400,"Expéditeur historique incohérent");
                    importMessage(m,peer,!from.equals(peer),"META_HISTORY",false);
                }
            }
            db.update("insert into whatsapp_sync_progress(event_key,phase,chunk_order,progress) values(?,?,?,?)",key,phase,order,progress);
        }
        // Separate official media follow-up only enriches an existing history item.
        for(var m:value.path("messages")) {
            String type=m.path("type").asText(),id=m.path("id").asText(),media=m.path(type).path("id").asText();
            require(!id.isBlank()&&!media.isBlank(),400,"Média historique incomplet");
            if(db.update("update whatsapp_history_media set media_id=?,message_type=? where whatsapp_id=?",media,type,id)==0)db.update("insert into whatsapp_history_media(whatsapp_id,media_id,message_type) values(?,?,?)",id,media,type);
            db.update("update message set type=?,media_id=?,body=? where whatsapp_id=? and type='media_placeholder'",type,media,"["+type+"]",id);
        }
    }
    private void contacts(JsonNode value) {
        require(value.path("state_sync").isArray(),400,"Contacts absents");
        for(var entry:value.path("state_sync")) {
            require(entry.path("type").asText().equals("contact"),400,"Type de synchronisation inconnu");
            var contact=entry.path("contact");String raw=contact.path("phone_number").asText().replaceFirst("^\\+","");phone(raw);
            String action=entry.path("action").asText();require(Set.of("add","remove").contains(action),400,"Action inconnue");
            if(action.equals("remove")) { db.update("update whatsapp_import set removed=true where kind='CONTACT' and external_id=?",raw);continue; }
            String name=contact.path("full_name").asText(contact.path("first_name").asText("+"+raw));
            UUID customer=customer(raw,name);mark("CONTACT",raw,customer);
            db.update("update whatsapp_import set removed=false where kind='CONTACT' and external_id=?",raw);
        }
    }
    private UUID customer(String raw,String name) {
        phone(raw);var existing=db.queryForList("select id from customer where phone=?","+"+raw);
        if(!existing.isEmpty())return (UUID)existing.getFirst().get("id");
        UUID id=UUID.randomUUID();db.update("insert into customer(id,name,phone) values(?,?,?)",id,name.substring(0,Math.min(160,name.length())),"+"+raw);return id;
    }
    private void mark(String kind,String external,UUID id) {
        if(db.queryForList("select local_id from whatsapp_import where kind=? and external_id=?",kind,external).isEmpty())db.update("insert into whatsapp_import(kind,external_id,local_id) values(?,?,?)",kind,external,id);
    }
    private void importMessage(JsonNode m,String peer,boolean outbound,String source,boolean liveEcho) {
        phone(peer);String waId=m.path("id").asText(),type=m.path("type").asText();
        require(!waId.isBlank()&&waId.length()<=300&&!type.isBlank()&&type.length()<=30&&m.path("timestamp").asText().matches("[0-9]{1,12}"),400,"Message incomplet");
        OffsetDateTime at=Instant.ofEpochSecond(Long.parseLong(m.path("timestamp").asText())).atOffset(ZoneOffset.UTC);
        var existing=db.queryForList("select id from message where whatsapp_id=?",waId);
        if(!existing.isEmpty())return;
        UUID customer=customer(peer,"+"+peer);var conversations=db.queryForList("select id from conversation where customer_id=?",customer);UUID conversation;
        if(conversations.isEmpty()) { conversation=UUID.randomUUID();db.update("insert into conversation(id,customer_id,updated_at) values(?,?,?)",conversation,customer,at); }else conversation=(UUID)conversations.getFirst().get("id");
        String media=m.path(type).path("id").asText(null);
        if(type.equals("media_placeholder")) {
            var followup=db.queryForList("select media_id,message_type from whatsapp_history_media where whatsapp_id=?",waId);
            if(!followup.isEmpty()) { type=(String)followup.getFirst().get("message_type");media=(String)followup.getFirst().get("media_id"); }
        }
        String body=type.equals("text")?m.path("text").path("body").asText():"["+type+"]";
        require(body.length()<=10000,400,"Message trop long");
        String status="RECEIVED";
        if(outbound) {
            String reported=m.path("history_context").path("status").asText();
            status=liveEcho?"SENT":switch(reported) {case "READ" -> "READ";case "DELIVERED","PLAYED" -> "DELIVERED";case "SENT" -> "SENT";case "ERROR" -> "FAILED";default -> "UNKNOWN";};
        }
        UUID id=UUID.randomUUID();
        db.update("insert into message(id,conversation_id,whatsapp_id,direction,type,body,media_id,status,source,created_at) values(?,?,?,?,?,?,?,?,?,?)",id,conversation,waId,outbound?"OUT":"IN",type,body,media,status,source,at);
        mark("MESSAGE",waId,id);mark("CONVERSATION",peer,conversation);
        db.update("update conversation set updated_at=case when updated_at<? then ? else updated_at end where id=?",at,at,conversation);
        // History is never allowed to open the Cloud API customer-service window or inflate unread counts.
        for(var event:db.queryForList("select status from message_status_event where whatsapp_id=? order by event_at",waId))messages.updateStatus(waId,(String)event.get("status"));
    }
    private static void phone(String raw) { require(raw.matches("[1-9][0-9]{7,14}"),400,"Numéro de contact invalide"); }
}
