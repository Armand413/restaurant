package bf.restaurant;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static bf.restaurant.ApiErrors.require;

@Service
class MessageService {
    private final JdbcTemplate db;private final MetaClient meta;private final TransactionTemplate tx;
    MessageService(JdbcTemplate db,MetaClient meta,TransactionTemplate tx) { this.db=db;this.meta=meta;this.tx=tx; }
    Map<String,Object> send(UUID conversation,UUID requestKey,String body) {
        require(meta.configured(),503,"WhatsApp non configuré");
        UUID id=tx.execute(s -> {
            var c=db.queryForList("select * from conversation where id=? for update",conversation);require(!c.isEmpty(),404,"Discussion introuvable");
            var existing=db.queryForList("select id,conversation_id,body from message where request_key=?",requestKey);
            if(!existing.isEmpty()) { require(existing.getFirst().get("conversation_id").equals(conversation)&&existing.getFirst().get("body").equals(body),409,"Clé déjà utilisée pour un autre message");return null; }
            var last=c.getFirst().get("last_inbound_at");
            require(last!=null && asInstant(last).isAfter(Instant.now().minus(Duration.ofHours(24))),409,"Fenêtre de service fermée : un modèle approuvé est requis");
            UUID newId=UUID.randomUUID();db.update("insert into message(id,conversation_id,request_key,direction,type,body,status) values(?,?,?,'OUT','text',?,'PENDING')",newId,conversation,requestKey,body);db.update("update conversation set updated_at=CURRENT_TIMESTAMP where id=?",conversation);return newId;
        });
        if(id!=null) {
            String phone=(String)db.queryForMap("select c.phone from customer c join conversation v on v.customer_id=c.id where v.id=?",conversation).get("phone");
            dispatch(id,phone,"text",Map.of("body",body));
        }
        return db.queryForMap("select * from message where request_key=?",requestKey);
    }
    void dispatch(UUID id,String phone,String type,Object content) {
        try {
            String whatsappId=meta.send(phone,type,content);
            db.update("update message set whatsapp_id=?,status='ACCEPTED' where id=?",whatsappId,id);
            // Events may arrive before the HTTP response.
            for(var e:db.queryForList("select status from message_status_event where whatsapp_id=? order by event_at",whatsappId)) updateStatus(whatsappId,(String)e.get("status"));
        } catch(MetaClient.MetaFailure ex) { db.update("update message set status=?,error_code=? where id=?",ex.ambiguous?"UNKNOWN":"FAILED",ex.code,id); }
    }
    void updateStatus(String whatsappId,String status) {
        if(!Set.of("sent","delivered","read","failed").contains(status))return;
        String target=status.toUpperCase(Locale.ROOT);
        // Delivery/read confirmation cannot regress on late or duplicate events.
        db.update("update message set status=? where whatsapp_id=? and ((?='READ') or (?='DELIVERED' and status<>'READ') or (?='SENT' and status not in ('DELIVERED','READ')) or (?='FAILED' and status not in ('DELIVERED','READ')))",target,whatsappId,target,target,target,target);
    }
    static Instant asInstant(Object value) { if(value instanceof java.sql.Timestamp t)return t.toInstant();if(value instanceof OffsetDateTime t)return t.toInstant();return Instant.parse(value.toString()); }
}
